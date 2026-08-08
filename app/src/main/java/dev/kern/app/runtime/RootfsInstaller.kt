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

    private const val CDIMAGE_BASE =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/${GuestConfig.UBUNTU_RELEASE}/release/"

    /**
     * The name cdimage serves today, used only when SHA256SUMS cannot be read.
     *
     * Canonical prunes this plain name once point releases ship: 24.04's is already a 404
     * and its SHA256SUMS lists only 24.04.3 and 24.04.4. 26.04 will follow, and a URL
     * built from the release alone is therefore a bomb with Canonical's finger on the
     * timer - every new install dying at step one, fixable only by shipping an app.
     */
    private const val ROOTFS_FALLBACK_NAME =
        "ubuntu-base-${GuestConfig.UBUNTU_RELEASE}-base-arm64.tar.gz"

    private const val CODE_SERVER_VERSION = "4.131.0"
    private const val CODE_SERVER_URL =
        "https://github.com/coder/code-server/releases/download/v$CODE_SERVER_VERSION/code-server_${CODE_SERVER_VERSION}_arm64.deb"

    /**
     * Pinned, unlike the base image's - and correct here for the reason it is wrong there.
     * This URL names an immutable GitHub release asset, so the bytes behind it cannot
     * change unless the version does. Taken from the release API's own `digest` for
     * code-server_4.131.0_arm64.deb, 228,520,934 bytes. Change it in the same commit as
     * [CODE_SERVER_VERSION], or setup will refuse a package that is perfectly good.
     */
    private const val CODE_SERVER_SHA256 =
        "b0758c3692f3fc2a7311d6ab58c4f91efc4bc277938cff4a4164f7f9683542cf"

    private const val ARCHIVE_NAME = "ubuntu-base.tar.gz"

    /**
     * The version is in the name because a file at the final name is trusted on sight.
     * Without it, the 218 MB package left by an older Kern would be handed to apt as if
     * it were this one, and its `.part` would be resumed against a different URL.
     */
    private const val DEB_NAME = "code-server-$CODE_SERVER_VERSION.deb"

    /** Both staged names, and the `.part` of either, matched by prefix. */
    private val DOWNLOAD_PREFIXES = listOf("ubuntu-base", "code-server")

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
                val deb = File(downloadDir(context), DEB_NAME)
                var fetch: Deferred<String?>? = null

                if (!LinuxRuntime.isInstalled(context)) {
                    val archive = File(downloadDir(context), ARCHIVE_NAME)
                    // Existence alone: download renames only a transfer that arrived whole
                    // and matched its checksum into this name, so anything here is
                    // complete. The size guess this replaces took 25 MB of the 34 MB image
                    // for a finished one.
                    if (!archive.exists()) {
                        _stage.value = Stage.Downloading("Ubuntu base", 0, 0)
                        logLine("Downloading Ubuntu ${GuestConfig.UBUNTU_RELEASE} base image")
                        val source = resolveRootfs()
                        logLine(
                            if (source.sha256 != null) {
                                "${source.name}, checked against SHA256SUMS"
                            } else {
                                "${source.name} - SHA256SUMS was unreachable, so this one " +
                                    "cannot be checked"
                            },
                        )
                        download(source.url, archive, source.sha256) { got, total ->
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
                    val failure = fetch.await()
                    if (failure != null) return@coroutineScope fail(failure)

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
     * Which base image to fetch, and what it should hash to, asked of cdimage rather than
     * assumed.
     *
     * Deliberately not a compile-time hash constant. Pinning one would turn Canonical's
     * routine rename into a checksum mismatch - Kern accusing itself of shipping a
     * tampered mirror, on Canonical's schedule, fixable only by shipping a new app.
     * SHA256SUMS is one fetch answering both questions at once, and it cannot rot that
     * way. If it is unreachable, today's literal name still gets a try: a cdimage
     * restructure should cost verification, not the install.
     */
    private fun resolveRootfs(): RootfsSource = selectRootfs(
        runCatching { fetchText(CDIMAGE_BASE + "SHA256SUMS") }
            .onFailure { Log.w(TAG, "could not read SHA256SUMS", it) }
            .getOrNull(),
    )

    /**
     * The newest ubuntu-base of our own series named in [sums], with the digest listed
     * beside it - or today's literal name and no digest when [sums] is unreadable or names
     * nothing we can use.
     *
     * Split from the fetch above, and internal rather than private, so the choice can be
     * tested against real SHA256SUMS text without reaching cdimage.
     */
    internal fun selectRootfs(sums: String?): RootfsSource {
        val fallback = RootfsSource(
            ROOTFS_FALLBACK_NAME,
            CDIMAGE_BASE + ROOTFS_FALLBACK_NAME,
            null,
        )
        if (sums == null) return fallback

        var best: RootfsSource? = null
        var bestVersion = emptyList<Int>()
        for (line in sums.lineSequence()) {
            val match = ROOTFS_LINE.matchEntire(line.trim()) ?: continue
            val (digest, name, version) = match.destructured
            // Point releases of our own series only. 26.04.1 supersedes 26.04, but 26.10
            // is a different Ubuntu and the codename GuestConfig writes into sources.list
            // would no longer describe it.
            val ours = version == GuestConfig.UBUNTU_RELEASE ||
                version.startsWith("${GuestConfig.UBUNTU_RELEASE}.")
            if (!ours) continue
            val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
            if (best == null || newer(parts, bestVersion)) {
                best = RootfsSource(name, CDIMAGE_BASE + name, digest.lowercase())
                bestVersion = parts
            }
        }
        return best ?: fallback
    }

    // internal, not private, only so [selectRootfs] can be read back in a unit test.
    internal class RootfsSource(val name: String, val url: String, val sha256: String?)

    /** `<digest> *ubuntu-base-26.04-base-arm64.tar.gz`, with the point release captured. */
    private val ROOTFS_LINE =
        Regex("""([0-9a-fA-F]{64})\s+\*?(ubuntu-base-([0-9.]+)-base-arm64\.tar\.gz)""")

    /** Number by number, because 26.04.10 is newer than 26.04.9 and sorts before it. */
    private fun newer(candidate: List<Int>, incumbent: List<Int>): Boolean {
        for (i in 0 until maxOf(candidate.size, incumbent.size)) {
            val left = candidate.getOrElse(i) { 0 }
            val right = incumbent.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    /**
     * Fetched outside the rootfs, so it can download while PRoot is busy unpacking into
     * that same directory tree. Returns null when the package is there, otherwise why it
     * is not: a checksum mismatch and a dead connection ask very different things of the
     * user, and both used to arrive as "Could not download code-server".
     */
    private suspend fun fetchServer(target: File): String? {
        if (target.exists()) return null
        var reported = -1
        return runCatching {
            download(CODE_SERVER_URL, target, CODE_SERVER_SHA256) { got, total ->
                if (total > 0) {
                    val percent = (got * 100 / total).toInt()
                    // Every 10%: often enough to look alive, rarely enough to read.
                    if (percent >= reported + 10) {
                        reported = percent
                        logLine("code-server download $percent%")
                    }
                }
            }
            null
        }.getOrElse {
            Log.w(TAG, "code-server download failed", it)
            it.message ?: "Could not download code-server"
        }
    }

    /**
     * Where the two setup downloads are staged.
     *
     * Not the cache: Android empties that when the device runs low on space, which is
     * exactly the device that cannot afford to fetch a quarter of a gigabyte twice, and it
     * can do it mid-transfer. `noBackupFilesDir` sits on the same filesystem as the rootfs,
     * so [moveInto] is still a rename rather than a 218 MB copy, and it keeps
     * re-downloadable bytes out of cloud backup where they have no business being.
     */
    private fun downloadDir(context: Context): File = context.noBackupFilesDir

    /**
     * The setup downloads. They live outside the rootfs so an install that failed can
     * resume without fetching a quarter of a gigabyte again - which also means anything
     * clearing up after a failed install has to reach them. Matched by prefix so the
     * `.part` of a transfer that never finished is caught too, and the cache is still
     * swept because installs before v0.1.1 staged there.
     */
    private fun cachedDownloads(context: Context): List<File> =
        listOf(downloadDir(context), context.cacheDir).flatMap { dir ->
            dir.listFiles()
                ?.filter { file -> DOWNLOAD_PREFIXES.any { file.name.startsWith(it) } }
                ?: emptyList()
        }

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

    /** Staging and rootfs share a filesystem, so this is a rename, not a 218 MB copy. */
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

        // toybox tar warns about metadata it cannot apply, so its exit status is no use;
        // judge by the result. Not by asking isInstalled, which now wants the marker this
        // is about to write - and not by the two files it used to ask for either, because
        // a tar that ran out of space partway leaves those behind and nothing downstream
        // could tell the difference.
        if (!LinuxRuntime.rootfsLooksComplete(context)) return false
        LinuxRuntime.markInstalled(context)
        return true
    }
}
