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

    private const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/${GuestConfig.UBUNTU_RELEASE}/release/" +
            "ubuntu-base-${GuestConfig.UBUNTU_RELEASE}-base-arm64.tar.gz"
    private const val CODE_SERVER_VERSION = "4.131.0"
    private const val CODE_SERVER_URL =
        "https://github.com/coder/code-server/releases/download/v$CODE_SERVER_VERSION/code-server_${CODE_SERVER_VERSION}_arm64.deb"

    private const val ARCHIVE_NAME = "ubuntu-base.tar.gz"
    private const val DEB_NAME = "code-server.deb"

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
    private val TOOLS = listOf(
        "ca-certificates", "git", "gh", "tmux", "curl", "ripgrep", "python3", "python3-pip",
    )

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
                val deb = File(context.cacheDir, DEB_NAME)
                var fetch: Deferred<Boolean>? = null

                if (!LinuxRuntime.isInstalled(context)) {
                    val archive = File(context.cacheDir, ARCHIVE_NAME)
                    // Existence alone: download renames only a transfer that arrived whole
                    // into this name, so anything here is complete. The size guess this
                    // replaces took 25 MB of the 34 MB image for a finished one.
                    if (!archive.exists()) {
                        _stage.value = Stage.Downloading("Ubuntu base", 0, 0)
                        logLine("Downloading Ubuntu ${GuestConfig.UBUNTU_RELEASE} base image")
                        download(ROOTFS_URL, archive) { got, total ->
                            _stage.value = Stage.Downloading("Ubuntu base", got, total)
                        }
                    }

                    // Start the big download now rather than later. code-server is 218 MB
                    // against the base image's 34, and unpacking is CPU bound, so fetching
                    // it here costs almost nothing on the clock instead of minutes of its
                    // own once unpacking has finished.
                    if (!CodeServer.isInstalled(context)) {
                        fetch = async { fetchServer(deb) }
                    }

                    _stage.value = Stage.Working("Unpacking Ubuntu")
                    logLine("Unpacking the filesystem")
                    if (!extract(context, archive)) {
                        fetch?.cancel()
                        return@coroutineScope fail("Could not unpack the filesystem")
                    }
                    archive.delete()
                }

                // Outside the branch above on purpose: this is the Repair path too, and an
                // apt upgrade that puts Ubuntu's own sources.list.d back, or a lost 99kern
                // that re-enables the _apt sandbox, leaves a guest that cannot install
                // anything and no way to fix it from the UI. Applying it is idempotent.
                _stage.value = Stage.Working("Configuring")
                GuestConfig.apply(context)
                logLine("Configured apt, DNS and dpkg")

                if (!CodeServer.isInstalled(context)) {
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
                    val staged = File(LinuxRuntime.guestTmpDir(context), "code-server.deb")
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
                    if (!CodeServer.isInstalled(context)) {
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
            CodeServer.applyWorkbenchSettings(context)
            LinuxRuntime.run(context, "mkdir -p /root/projects", timeoutMs = 20_000)
            LinuxRuntime.notifyInstallChanged()
            logLine("Editor ready — installing the toolchain in the background")

            _stage.value = Stage.Working("Installing tools")
            // Checked, not fired and forgotten: apt losing the connection partway leaves a
            // guest with no git, gh or tmux, and reporting Done over that sends the user
            // hunting for the problem everywhere except where it is.
            if (!Apt.install(context, TOOLS, ::logLine)) {
                return@withContext fail(
                    "The editor is ready, but the toolchain did not finish installing - " +
                        "git, gh and tmux are missing. Retry, or use Repair in settings.",
                )
            }

            _stage.value = Stage.Working("Finishing up")
            GuestConfig.polish(context)
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
        if (target.exists()) return true
        var reported = -1
        return runCatching {
            download(CODE_SERVER_URL, target) { got, total ->
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

    /**
     * The setup downloads. They live in the cache rather than the rootfs so an install
     * that failed can resume without fetching a quarter of a gigabyte again - which also
     * means anything clearing up after a failed install has to reach them. Matched by
     * prefix so the `.part` of a transfer that never finished is caught too.
     */
    private fun cachedDownloads(context: Context): List<File> =
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(ARCHIVE_NAME) || it.name.startsWith(DEB_NAME) }
            ?: emptyList()

    /**
     * Whether a failed setup left bytes behind. The download dies before the rootfs is
     * unpacked far more often than after, and in that state `isInstalled` is false while
     * the cache holds most of the payload - so the UI cannot decide from the guest alone
     * whether there is anything to throw away.
     */
    fun hasCachedDownloads(context: Context): Boolean = cachedDownloads(context).isNotEmpty()

    fun clearCachedDownloads(context: Context) {
        cachedDownloads(context).forEach { runCatching { it.delete() } }
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

        // not LinuxRuntime.spawnInGuest(): `-r /` and these two binds on purpose, so
        // Android's /system/bin/tar is reachable before a rootfs exists.
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
        process.drain()
        process.waitFor()
        process.close()

        // toybox tar warns about metadata it cannot apply; judge by the result.
        return LinuxRuntime.isInstalled(context)
    }
}
