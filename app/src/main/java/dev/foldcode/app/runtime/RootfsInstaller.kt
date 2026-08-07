package dev.foldcode.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One-button setup: downloads Ubuntu, unpacks it into app-private storage, configures it,
 * and installs code-server plus a starter toolchain. After this the app is self-contained.
 *
 * Two Android-specific constraints shape the implementation:
 *
 *  1. **Hard links are forbidden** in app storage by SELinux, and Ubuntu's tarball and
 *     `dpkg` both use them — so everything runs under PRoot with `-l`, which rewrites
 *     them to symlinks. Skipping that flag fails on the very first package.
 *  2. **We have no tar of our own**, so extraction uses Android's `/system/bin/tar`,
 *     run under PRoot so it also gets fake-root for ownership metadata.
 */
object RootfsInstaller {

    private const val TAG = "FoldCode"

    /**
     * Ubuntu 26.04 LTS, verified on-device against this PRoot build: fake root, apt,
     * dpkg's hard-link handling, and code-server all behave. Worth knowing that 26.04
     * ships uutils (Rust) coreutils rather than GNU — it caused no trouble in testing,
     * but it is the newest moving part if something odd ever turns up in a package's
     * install scripts.
     *
     * The codename lives next to the version because [configure] writes it into
     * sources.list, and the two drifting apart produces a rootfs that cannot install
     * anything.
     */
    const val UBUNTU_RELEASE = "26.04"
    private const val UBUNTU_CODENAME = "resolute"
    private const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_RELEASE/release/" +
            "ubuntu-base-$UBUNTU_RELEASE-base-arm64.tar.gz"
    private const val CODE_SERVER_VERSION = "4.131.0"
    private const val CODE_SERVER_URL =
        "https://github.com/coder/code-server/releases/download/v$CODE_SERVER_VERSION/code-server_${CODE_SERVER_VERSION}_arm64.deb"

    sealed interface Stage {
        data object Idle : Stage
        data class Downloading(val what: String, val bytes: Long, val total: Long) : Stage {
            val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
        }
        data class Working(val what: String) : Stage
        data object Done : Stage
        data class Failed(val message: String) : Stage
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    /**
     * Roughly how much this will cost the user, shown before they commit. Measured on
     * device rather than guessed: the rootfs is ~34 MB, the code-server .deb alone is
     * 218 MB, and the rest is packages. The finished guest measures ~1.2 GB.
     */
    const val ESTIMATED_DOWNLOAD_MB = 400
    const val ESTIMATED_DISK_MB = 1400

    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!LinuxRuntime.isInstalled(context)) {
                val archive = File(context.cacheDir, "ubuntu-base.tar.gz")
                if (!archive.exists() || archive.length() < 20L * 1024 * 1024) {
                    _stage.value = Stage.Downloading("Ubuntu base", 0, 0)
                    LinuxRuntime.download(ROOTFS_URL, archive) { got, total ->
                        _stage.value = Stage.Downloading("Ubuntu base", got, total)
                    }
                }

                _stage.value = Stage.Working("Unpacking Ubuntu")
                if (!extract(context, archive)) {
                    return@withContext fail("Could not unpack the filesystem")
                }
                archive.delete()

                _stage.value = Stage.Working("Configuring")
                configure(context)
            }

            if (!LinuxRuntime.isCodeServerInstalled(context)) {
                _stage.value = Stage.Working("Updating package lists")
                val update = LinuxRuntime.run(context, "apt-get update", timeoutMs = 300_000)
                if (update?.ok != true) {
                    Log.w(TAG, "apt-get update: ${update?.stderr?.take(300)}")
                }

                val deb = File(LinuxRuntime.rootfsDir(context), "tmp/code-server.deb")
                _stage.value = Stage.Downloading("code-server", 0, 0)
                LinuxRuntime.download(CODE_SERVER_URL, deb) { got, total ->
                    _stage.value = Stage.Downloading("code-server", got, total)
                }

                _stage.value = Stage.Working("Installing code-server")
                // apt (not dpkg) so dependencies resolve.
                val install = LinuxRuntime.run(
                    context,
                    "apt-get install -y /tmp/code-server.deb && rm -f /tmp/code-server.deb",
                    timeoutMs = 900_000,
                )
                if (!LinuxRuntime.isCodeServerInstalled(context)) {
                    return@withContext fail(
                        install?.stderr?.lines()?.lastOrNull { it.isNotBlank() }
                            ?: "code-server did not install",
                    )
                }
            }

            _stage.value = Stage.Working("Installing tools")
            // git, gh and tmux are load-bearing: cloning and pushing, signing in to
            // GitHub, and session persistence. ca-certificates is too — without a trust
            // store, anything speaking TLS from Go (gh) or curl fails with
            // "certificate signed by unknown authority". The rest just make the IDE
            // useful straight away. Idempotent, so re-running repairs a partial setup.
            LinuxRuntime.run(
                context,
                "apt-get install -y ca-certificates git gh tmux curl ripgrep " +
                    "python3 python3-pip && update-ca-certificates",
                timeoutMs = 1_200_000,
            )

            _stage.value = Stage.Working("Finishing up")
            polish(context)

            LinuxRuntime.applyWorkbenchSettings(context)
            LinuxRuntime.run(context, "mkdir -p /root/projects", timeoutMs = 20_000)

            _stage.value = Stage.Done
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(message: String): Boolean {
        _stage.value = Stage.Failed(message)
        return false
    }

    private fun extract(context: Context, archive: File): Boolean {
        val target = LinuxRuntime.rootfsDir(context).apply { mkdirs() }
        val proot = LinuxRuntime.prootBinary(context)

        val process = PtyProcess.spawn(
            command = proot.absolutePath,
            argv = listOf(
                proot.absolutePath,
                "-0",
                // Mandatory: the tarball contains hard links, which SELinux forbids here.
                "-l",
                "-r", "/",
                "-b", "${target.absolutePath}:${target.absolutePath}",
                "-b", "${archive.parent}:${archive.parent}",
                "-w", target.absolutePath,
                "/system/bin/tar", "-xzf", archive.absolutePath, "-C", target.absolutePath,
            ),
            env = LinuxRuntime.prootEnv(context),
            cwd = context.filesDir.absolutePath,
            columns = 200,
            rows = 50,
        ) ?: return false

        // tar is chatty on the pty; drain it so a full buffer cannot stall extraction.
        runCatching { process.input.readBytes() }
        process.waitFor()
        process.close()

        // toybox tar warns about metadata it cannot apply; judge by the result.
        return LinuxRuntime.isInstalled(context)
    }

    /**
     * Quieten the guest login.
     *
     * Android hands its own supplementary group IDs to every process, and they come
     * through PRoot into the guest, where `/etc/group` has no matching entries — so each
     * login prints "groups: cannot find name for group ID …". Naming them once fixes it,
     * and the IDs must be read from inside the guest because they belong to the running
     * process, not to the filesystem.
     */
    private suspend fun polish(context: Context) {
        LinuxRuntime.run(
            context,
            """
            for g in ${'$'}(awk '/^Groups:/{${'$'}1=""; print}' /proc/self/status 2>/dev/null); do
              grep -q ":x:${'$'}g:" /etc/group 2>/dev/null || echo "android_${'$'}g:x:${'$'}g:" >> /etc/group
            done
            mkdir -p /root/projects
            git config --global --get init.defaultBranch >/dev/null 2>&1 || \
              git config --global init.defaultBranch main
            git config --global --get user.name >/dev/null 2>&1 || \
              git config --global user.name FoldCode
            git config --global --get user.email >/dev/null 2>&1 || \
              git config --global user.email foldcode@localhost
            """.trimIndent(),
            timeoutMs = 60_000,
        )
    }

    private fun configure(context: Context) {
        val root = LinuxRuntime.rootfsDir(context)

        write(File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write(
            File(root, "etc/hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
        )
        // arm64 lives on ports.ubuntu.com, not archive.ubuntu.com.
        write(
            File(root, "etc/apt/sources.list"),
            listOf(
                UBUNTU_CODENAME,
                "$UBUNTU_CODENAME-updates",
                "$UBUNTU_CODENAME-security",
            ).joinToString("\n") {
                "deb http://ports.ubuntu.com/ubuntu-ports $it main universe restricted multiverse"
            } + "\n",
        )
        // 24.04 also ships the same repositories in deb822 form. Leaving both in place
        // makes apt warn about every target being configured twice, so the one we do
        // not control goes; the list written above covers the same components.
        runCatching { File(root, "etc/apt/sources.list.d/ubuntu.sources").delete() }
        write(
            File(root, "etc/apt/apt.conf.d/99foldcode"),
            buildString {
                // apt drops privileges to _apt by default, which cannot work under PRoot.
                appendLine("APT::Sandbox::User \"root\";")
                // Pipelining and pdiffs are the classic causes of apt hanging under PRoot.
                appendLine("Acquire::http::Pipeline-Depth \"0\";")
                appendLine("Acquire::PDiffs \"false\";")
                appendLine("Acquire::Retries \"3\";")
                appendLine("APT::Install-Recommends \"false\";")
            },
        )
        write(
            File(root, "etc/environment"),
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\nLANG=C.UTF-8\n",
        )
        File(root, "root/projects").mkdirs()
        File(root, "tmp").mkdirs()
    }

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "could not write ${file.absolutePath}: ${it.message}") }
    }
}
