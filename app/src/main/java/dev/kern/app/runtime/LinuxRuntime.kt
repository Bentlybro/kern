package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wrap [value] as a single-quoted shell word bash cannot re-interpret.
 *
 * The quotes are PART OF THE RESULT. Call sites read `cd ${sq(path)}` — never
 * `cd '${sq(path)}'`. A bare `'$x'` in a guest script template is then visibly
 * wrong and greppable.
 */
internal fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * The app's Linux backend: an Ubuntu filesystem inside app-private storage, entered
 * through the bundled PRoot. Everything the IDE runs — code-server, git, compilers,
 * language servers, agents — runs in here. No second app is involved.
 *
 * Why this is permitted at a modern targetSdk, given Android forbids exec from writable
 * storage: PRoot itself lives in `nativeLibraryDir`, which is read-only and therefore
 * exempt, and binaries inside the rootfs are never handed to the kernel's execve —
 * PRoot's loader stub maps them. PRoot also fakes uid 0, which is what lets `apt` work.
 *
 * See docs/11-embedded-linux.md for the flags that matter and why.
 */
object LinuxRuntime {

    private const val TAG = "Kern"

    private val commandCounter = AtomicLong(0)

    /**
     * Bumped whenever the guest is created or destroyed.
     *
     * The app's top-level routing needs this. Whether Linux is installed is a question
     * about the filesystem, not something Compose can observe, so a plain `remember {}`
     * of it is computed once and never again — which left the app sitting on "starting
     * linux" forever after the environment was deleted out from under it.
     */
    private val _installChanges = MutableStateFlow(0)
    val installChanges: StateFlow<Int> = _installChanges.asStateFlow()

    /** Call after the rootfs is installed or deleted, so the UI re-reads the world. */
    fun notifyInstallChanged() {
        _installChanges.value++
    }

    fun rootfsDir(context: Context): File = File(context.filesDir, "linux")

    /** PRoot's own scratch dir, on the **host** side, handed to it as `PROOT_TMP_DIR`. */
    fun prootTmpDir(context: Context): File = File(context.filesDir, "tmp").apply { mkdirs() }

    /** `/tmp` as seen from inside the guest. Not the same directory as [prootTmpDir]. */
    fun guestTmpDir(context: Context): File = File(rootfsDir(context), "tmp").apply { mkdirs() }

    /**
     * Where PRoot parks the real file behind each translated hard link.
     *
     * This must live *inside* the rootfs. PRoot replaces a hard link with a symlink
     * pointing here, and that target is later resolved from inside the guest — so a
     * directory outside the rootfs produces a dangling link. It fails in a thoroughly
     * misleading way: `dpkg` unpacks perl-base, chowns `perl5.38.2.dpkg-new` (a hard
     * link to `perl`), and chown follows the broken symlink to report
     * "No such file or directory" about a file that is plainly there.
     */
    private fun l2sDir(context: Context): File =
        File(rootfsDir(context), ".l2s").apply { mkdirs() }

    private fun nativeLibDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    internal fun prootBinary(context: Context): File = File(nativeLibDir(context), "libproot.so")

    /** Guest home; also where cloned projects live. */
    fun projectsDir(context: Context): File =
        File(rootfsDir(context), "root/projects")

    fun isInstalled(context: Context): Boolean {
        val root = rootfsDir(context)
        return File(root, "etc/os-release").exists() &&
            (File(root, "bin/bash").exists() || File(root, "usr/bin/bash").exists())
    }

    /**
     * Everything the IDE needs to open. The app's whole top-level route turns on this, so
     * it lives here rather than being spelled out at each of the three places that ask —
     * a third condition added to two of three hands the user the IDE while the setup
     * screen still thinks there is work to do.
     */
    fun isReady(context: Context): Boolean = isInstalled(context) && CodeServer.isInstalled(context)

    /** How the guest describes itself, e.g. "Ubuntu 26.04 LTS". */
    suspend fun osPrettyName(context: Context): String? =
        run(
            context,
            ". /etc/os-release 2>/dev/null; echo \"\$PRETTY_NAME\"",
            timeoutMs = 25_000,
        )?.stdout?.trim()?.takeIf { it.isNotBlank() }

    internal fun prootEnv(context: Context): Map<String, String> {
        val nativeLib = nativeLibDir(context)
        return mapOf(
            // proot's baked RUNPATH points at Termux's prefix, which we do not have.
            "LD_LIBRARY_PATH" to nativeLib.absolutePath,
            "PROOT_LOADER" to File(nativeLib, "libproot_loader.so").absolutePath,
            "PROOT_LOADER32" to File(nativeLib, "libproot_loader32.so").absolutePath,
            // Without this proot tries Termux's prefix and warns on every launch.
            "PROOT_TMP_DIR" to prootTmpDir(context).absolutePath,
            "PROOT_L2S_DIR" to l2sDir(context).absolutePath,
            "TERM" to "xterm-256color",
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "DEBIAN_FRONTEND" to "noninteractive",
        )
    }

    /**
     * argv to enter the guest.
     *
     * `-0` fakes root; `-l` translates hard links to symlinks, which is **mandatory** —
     * SELinux forbids hard links in app storage and dpkg depends on them, so without it
     * the first `apt install` fails.
     */
    internal fun prootArgs(
        context: Context,
        guestCommand: List<String>,
        workingDir: String = "/root",
    ): List<String> {
        val args = mutableListOf(
            prootBinary(context).absolutePath,
            "-0",
            "-l",
            "-r", rootfsDir(context).absolutePath,
        )
        val l2s = l2sDir(context).absolutePath
        listOf(
            // The hard-link farm, bound onto itself.
            //
            // PRoot replaces a hard link with a symlink and writes the *host* path of the
            // real file as its target — so that path must also resolve from inside the
            // guest, where the root is the rootfs and /data/user/0/... otherwise means
            // nothing. Binding the directory at its own path is what makes it resolve.
            //
            // Ubuntu 26.04 makes this fatal rather than cosmetic: its coreutils is a
            // single multi-call binary sitting behind ~115 hard links, so one dangling
            // link takes out every core utility at once — `ls`, `cat`, `head`, the lot.
            "$l2s:$l2s",
            "/proc", "/sys", "/dev", "/dev/pts",
            "/proc/self/fd:/dev/fd",
            "/proc/self/fd/0:/dev/stdin",
            "/proc/self/fd/1:/dev/stdout",
            "/proc/self/fd/2:/dev/stderr",
            "/storage", "/sdcard",
        ).forEach { bind ->
            if (File(bind.substringBefore(':')).exists()) {
                args += "-b"
                args += bind
            }
        }
        args += "-w"
        args += workingDir
        args += guestCommand
        return args
    }

    /** The one way to start a process inside the guest. */
    internal fun spawnInGuest(
        context: Context,
        guestCommand: List<String>,
        workingDir: String = "/root",
        columns: Int = 200,
        rows: Int = 50,
    ): PtyProcess? = PtyProcess.spawn(
        command = prootBinary(context).absolutePath,
        argv = prootArgs(context, guestCommand, workingDir),
        env = prootEnv(context),
        cwd = context.filesDir.absolutePath,
        columns = columns,
        rows = rows,
    )

    // ---- running commands ---------------------------------------------------

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        val lines: List<String>
            get() = stdout.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** The last thing the command said, from either stream — what an error message wants. */
        fun lastLine(limit: Int = 160): String? =
            (stdout.lineSequence() + stderr.lineSequence())
                .map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(limit)
    }

    /**
     * Run a shell script in the guest and collect its output.
     *
     * Output is redirected to files inside the rootfs rather than read off the pty: the
     * rootfs is our own private storage, so Kotlin can read those files directly, and
     * that avoids terminal echo and CR/LF mangling corrupting the result.
     */
    suspend fun run(
        context: Context,
        script: String,
        timeoutMs: Long = 30_000,
        workingDir: String = "/root",
        /**
         * Called with each whole line as it is written, for commands worth watching.
         * The output file is ours, so this is a cheap tail rather than another pipe.
         */
        onLine: ((String) -> Unit)? = null,
    ): Result? = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) return@withContext null

        val id = commandCounter.incrementAndGet()
        val guestTmp = guestTmpDir(context)
        val scriptFile = File(guestTmp, "fc-$id.sh")
        val outFile = File(guestTmp, "fc-$id.out")
        val errFile = File(guestTmp, "fc-$id.err")

        val rcFile = File(guestTmp, "fc-$id.rc")
        try {
            scriptFile.writeText(script)
            // The wrapper records its own exit code. We watch for that file rather than
            // calling waitpid: PRoot does not exit until every process it traces has
            // exited, so a command that leaves anything running in the background would
            // block forever — and a blocking native wait cannot be cancelled by a
            // coroutine timeout.
            val wrapper =
                "bash /tmp/fc-$id.sh > /tmp/fc-$id.out 2> /tmp/fc-$id.err; " +
                    "echo \$? > /tmp/fc-$id.rc"

            val process = spawnInGuest(
                context,
                listOf("/bin/bash", "-lc", wrapper),
                workingDir,
            ) ?: return@withContext null

            val exit = try {
                withTimeoutOrNull(timeoutMs) {
                    var seen = 0
                    while (!rcFile.exists()) {
                        if (onLine != null) seen = tail(outFile, seen, onLine)
                        delay(if (onLine != null) 250 else 100)
                    }
                    // One last pass, or the closing lines are never reported.
                    if (onLine != null) tail(outFile, seen, onLine)
                    rcFile.readText().trim().toIntOrNull() ?: 0
                }
            } finally {
                process.close()
            } ?: return@withContext null

            Result(
                exitCode = exit,
                stdout = outFile.takeIf { it.exists() }?.readText().orEmpty(),
                stderr = errFile.takeIf { it.exists() }?.readText().orEmpty(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "guest command failed", e)
            null
        } finally {
            listOf(scriptFile, outFile, errFile, rcFile).forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Report whole lines appended since [from]; returns the new offset.
     *
     * Only complete lines are emitted, so a half-written line is never shown and is
     * picked up on the next pass. Carriage returns count as breaks because apt redraws
     * its progress that way, and each redraw is a line worth seeing in its own right.
     */
    private fun tail(file: File, from: Int, onLine: (String) -> Unit): Int {
        if (!file.exists()) return from
        val text = runCatching { file.readText() }.getOrNull() ?: return from
        if (text.length <= from) return from

        val fresh = text.substring(from)
        val cut = fresh.lastIndexOfAny(charArrayOf('\n', '\r'))
        if (cut < 0) return from

        fresh.substring(0, cut)
            .split('\n', '\r')
            .forEach { line -> if (line.isNotBlank()) onLine(line) }
        return from + cut + 1
    }

    /**
     * Spawn an arbitrary command on its own pty.
     *
     * Backs the agent cockpit. A login shell so the guest's profile is loaded — agents
     * are usually installed by a package manager that puts them somewhere only a login
     * shell knows about — and `exec` so the command replaces bash rather than leaving a
     * shell waiting behind it, which would keep the pty open after the agent exits.
     */
    fun spawnCommand(
        context: Context,
        command: String,
        columns: Int,
        rows: Int,
        workingDir: String = ProjectRepository.currentFolder(context),
    ): PtyProcess? = spawnInGuest(
        context,
        // not sq(): the user's own agent command line must stay a command, not a word.
        listOf("/bin/bash", "-lc", "exec $command"),
        workingDir,
        columns = columns,
        rows = rows,
    )

    /** Spawn an interactive login shell on its own pty — this backs the terminal. */
    fun spawnShell(
        context: Context,
        columns: Int,
        rows: Int,
        /**
         * Distinct per terminal. `tmux new-session -A` attaches to an existing session of
         * the same name, so a shared name meant every new tab attached to the first one
         * and showed identical output — several terminals that were all the same terminal.
         */
        sessionName: String = "kern",
        /**
         * The open project, not the home directory. A terminal that opens somewhere other
         * than the thing you are working on just means typing `cd` before every session.
         */
        workingDir: String = ProjectRepository.currentFolder(context),
    ): PtyProcess? = spawnInGuest(
        context,
        // tmux keeps the session alive across terminal detach/reattach. `-c` so the
        // session it creates starts in the project too, not just the bash that ran it.
        listOf(
            "/bin/bash",
            "-lc",
            "tmux new-session -A -s ${sq(sessionName)} -c ${sq(workingDir)} || exec bash -l",
        ),
        workingDir,
        columns = columns,
        rows = rows,
    )

    /**
     * Prove the bundled PRoot runs, independent of whether a rootfs exists. A failure
     * here means the native layer is wrong rather than the guest.
     */
    suspend fun probe(context: Context): String = withContext(Dispatchers.IO) {
        val proot = prootBinary(context)
        if (!proot.exists()) return@withContext "libproot.so missing"
        withTimeoutOrNull(15_000) {
            // not spawnInGuest(): no -r and no binds on purpose, so this still answers
            // with no rootfs present — a failure here is the native layer, not the guest.
            val process = PtyProcess.spawn(
                command = proot.absolutePath,
                argv = listOf(proot.absolutePath, "--version"),
                env = prootEnv(context),
                cwd = context.filesDir.absolutePath,
            ) ?: return@withTimeoutOrNull "spawn failed"
            try {
                process.readUntilClosed()
            } finally {
                process.close()
            }
        } ?: "timed out"
    }
}
