package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    private const val TAG = "Kern"

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
     * Setup runs in this scope rather than a caller's.
     *
     * A composable's scope dies with the composable, and the setup screen is replaced as
     * soon as the environment starts to look usable — code-server is installed before the
     * toolchain, so that happens *during* setup. Running the install in the screen's own
     * scope therefore cancelled it partway and left a guest with code-server and none of
     * the tools, which is exactly what a half-finished setup looks like.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * A rolling tail of what setup is actually doing.
     *
     * Setup takes minutes, and a single line like "Installing tools" for three of them
     * is indistinguishable from being stuck. This is the real output, so it is obvious
     * that something is happening and obvious what.
     */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private const val LOG_LINES = 400

    private fun logLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        // apt redraws these dozens of times a second and they say nothing useful.
        if (text.startsWith("(Reading database") || text.startsWith("Selecting previously")) {
            return
        }
        _log.value = (_log.value + text).takeLast(LOG_LINES)
    }

    /**
     * Begin setup unless it is already running. Safe to call repeatedly — [install] is
     * idempotent, so this doubles as the resume path for a setup that was interrupted.
     */
    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        job = scope.launch { install(app) }
    }

    /**
     * Roughly how much this will cost the user, shown before they commit. Measured on
     * device rather than guessed: the rootfs is ~34 MB, the code-server .deb alone is
     * 218 MB, and the rest is packages. The finished guest measures ~1.2 GB.
     */
    const val ESTIMATED_DOWNLOAD_MB = 400
    const val ESTIMATED_DISK_MB = 1400

    /**
     * git, gh and tmux are load-bearing: cloning and pushing, signing in to GitHub, and
     * session persistence. ca-certificates is too — without a trust store, anything
     * speaking TLS from Go (gh) or curl fails with "certificate signed by unknown
     * authority". The rest just make the IDE useful straight away.
     */
    private const val TOOLS =
        "ca-certificates git gh tmux curl ripgrep python3 python3-pip"

    /**
     * Setup, in two halves.
     *
     * The first half is only what the editor cannot open without: a filesystem and
     * code-server. The second is the toolchain, which is worth having but blocks nobody
     * from writing code — so the app is told the environment is usable in between, and
     * the rest installs while the user is already working.
     *
     * Idempotent throughout: each step checks whether it is needed, which is what makes
     * this double as Repair and as the resume path for an interrupted setup.
     */
    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val essential = coroutineScope {
                val deb = File(context.cacheDir, "code-server.deb")
                var fetch: Deferred<Boolean>? = null

                if (!LinuxRuntime.isInstalled(context)) {
                    val archive = File(context.cacheDir, "ubuntu-base.tar.gz")
                    if (!archive.exists() || archive.length() < 20L * 1024 * 1024) {
                        _stage.value = Stage.Downloading("Ubuntu base", 0, 0)
                        logLine("Downloading Ubuntu $UBUNTU_RELEASE base image")
                        LinuxRuntime.download(ROOTFS_URL, archive) { got, total ->
                            _stage.value = Stage.Downloading("Ubuntu base", got, total)
                        }
                    }

                    // Start the big download now rather than later. code-server is 218 MB
                    // against the base image's 34, and unpacking is CPU bound, so fetching
                    // it here costs almost nothing on the clock instead of minutes of its
                    // own once unpacking has finished.
                    if (!LinuxRuntime.isCodeServerInstalled(context)) {
                        fetch = async { fetchServer(deb) }
                    }

                    _stage.value = Stage.Working("Unpacking Ubuntu")
                    logLine("Unpacking the filesystem")
                    if (!extract(context, archive)) {
                        fetch?.cancel()
                        return@coroutineScope fail("Could not unpack the filesystem")
                    }
                    archive.delete()

                    _stage.value = Stage.Working("Configuring")
                    configure(context)
                    logLine("Configured apt, DNS and dpkg")
                }

                if (!LinuxRuntime.isCodeServerInstalled(context)) {
                    _stage.value = Stage.Working("Updating package lists")
                    LinuxRuntime.run(
                        context,
                        "apt-get update 2>&1",
                        timeoutMs = 300_000,
                        onLine = ::logLine,
                    )

                    if (fetch == null) fetch = async { fetchServer(deb) }
                    _stage.value = Stage.Working("Downloading code-server")
                    if (fetch.await() != true) {
                        return@coroutineScope fail("Could not download code-server")
                    }

                    _stage.value = Stage.Working("Installing code-server")
                    val staged = File(LinuxRuntime.rootfsDir(context), "tmp/code-server.deb")
                    if (!moveInto(deb, staged)) {
                        return@coroutineScope fail("Could not stage the code-server package")
                    }

                    // apt (not dpkg) so dependencies resolve.
                    val result = LinuxRuntime.run(
                        context,
                        "apt-get install -y /tmp/code-server.deb 2>&1; rm -f /tmp/code-server.deb",
                        timeoutMs = 900_000,
                        onLine = ::logLine,
                    )
                    if (!LinuxRuntime.isCodeServerInstalled(context)) {
                        return@coroutineScope fail(
                            result?.stdout?.lines()?.lastOrNull { it.isNotBlank() }
                                ?: "code-server did not install",
                        )
                    }
                }
                true
            }
            if (!essential) return@withContext false

            // Hand over: from here the editor can open, and the rest happens behind it.
            LinuxRuntime.applyWorkbenchSettings(context)
            LinuxRuntime.run(context, "mkdir -p /root/projects", timeoutMs = 20_000)
            LinuxRuntime.notifyInstallChanged()
            logLine("Editor ready — installing the toolchain in the background")

            _stage.value = Stage.Working("Installing tools")
            LinuxRuntime.run(
                context,
                "apt-get install -y $TOOLS 2>&1; update-ca-certificates 2>&1",
                timeoutMs = 1_200_000,
                onLine = ::logLine,
            )

            _stage.value = Stage.Working("Finishing up")
            polish(context)
            logLine("Setup complete")

            _stage.value = Stage.Done
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Fetched into the app's cache rather than the rootfs, so it can download while
     * PRoot is busy unpacking into that same directory tree.
     */
    private suspend fun fetchServer(target: File): Boolean {
        if (target.exists() && target.length() > 100L * 1024 * 1024) return true
        var reported = -1
        return runCatching {
            LinuxRuntime.download(CODE_SERVER_URL, target) { got, total ->
                if (total > 0) {
                    val percent = (got * 100 / total).toInt()
                    // Every 10%: often enough to look alive, rarely enough to read.
                    if (percent >= reported + 10) {
                        reported = percent
                        logLine("code-server download $percent%")
                    }
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "code-server download failed", it)
            false
        }
    }

    /** Cache and rootfs share a filesystem, so this is a rename, not a 218 MB copy. */
    private fun moveInto(source: File, target: File): Boolean {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (source.renameTo(target)) return true
        return runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        }.getOrDefault(false)
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
              git config --global user.name Kern
            git config --global --get user.email >/dev/null 2>&1 || \
              git config --global user.email kern@localhost
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
            File(root, "etc/apt/apt.conf.d/99kern"),
            buildString {
                // apt drops privileges to _apt by default, which cannot work under PRoot.
                appendLine("APT::Sandbox::User \"root\";")
                // Pipelining and pdiffs are the classic causes of apt hanging under PRoot.
                appendLine("Acquire::http::Pipeline-Depth \"0\";")
                appendLine("Acquire::PDiffs \"false\";")
                appendLine("Acquire::Retries \"3\";")
                appendLine("APT::Install-Recommends \"false\";")
                // Translated descriptions are several MB of download that nothing here
                // ever reads.
                appendLine("Acquire::Languages \"none\";")
            },
        )
        write(
            File(root, "etc/dpkg/dpkg.cfg.d/01-kern"),
            buildString {
                // dpkg fsyncs after every extracted file, which on phone storage is the
                // single largest cost of installing anything. Container images disable it
                // for exactly this reason. The exposure is a half-written install if the
                // device loses power mid-apt, and Repair already recovers from that.
                appendLine("force-unsafe-io")
                // Nothing on a phone reads man pages or package docs, and skipping them
                // saves both time and a surprising amount of space.
                appendLine("path-exclude=/usr/share/doc/*")
                appendLine("path-exclude=/usr/share/man/*")
                appendLine("path-exclude=/usr/share/info/*")
                appendLine("path-exclude=/usr/share/groff/*")
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
