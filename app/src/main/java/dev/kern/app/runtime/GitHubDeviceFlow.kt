package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Driving `gh auth login` through GitHub's device flow, and reading back what happened.
 *
 * Everything here is about the mechanism — how `gh` is fed, how its screen is read, how a
 * flow is abandoned. Which of those the user is shown is [GitHubAuth]'s business.
 *
 * Driving `gh` turned out to have one sharp edge worth recording. Its prompts are drawn
 * by a TUI that asks the terminal where the cursor is (`ESC[6n`) and *blocks until the
 * terminal answers*, so feeding keystrokes to a bare pty deadlocks. The fix is to run it
 * under tmux, which is a real terminal emulator and answers on our behalf, and to read
 * the screen back with `capture-pane` as plain text rather than parsing escape codes.
 *
 * tmux then imposes its own constraint: a server started under one PRoot instance is
 * unreachable from another ("access not allowed"), because PRoot's fake ownership lives
 * in process memory and never reaches the socket on disk. So the whole flow stays inside
 * a single PRoot, and the script mirrors the pane to a file — which the app can simply
 * read, the rootfs being its own private storage.
 */
internal object GitHubDeviceFlow {

    private const val TAG = "Kern"

    private const val WORK_DIR = "kern-gh"
    private const val SCRIPT_NAME = "kern-ghlogin.sh"

    /** How a flow ended. Cancelled is not a failure and must never be shown as one. */
    sealed interface Outcome {
        data object Ok : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** The in-flight login, kept so the UI can cancel a flow the user abandons. */
    @Volatile
    private var loginProcess: PtyProcess? = null

    /** True while a login is in flight, so nothing else clears the step under it. */
    val running: Boolean get() = loginProcess != null

    /**
     * A backstop on the whole flow, a minute past the fifteen the script allows itself.
     *
     * It should never be what ends a login: the script writes an rc of its own on timeout.
     * It is here for the case where the script never ran at all.
     */
    private const val FLOW_TIMEOUT_MS = 960_000L

    /**
     * The result file's contents, or null while it is absent or still empty.
     *
     * Empty and missing mean the same thing - not finished - and neither may be confused
     * with a result, because "" fails the EXIT=0 test and reads as a failed sign-in.
     */
    private fun readResult(rcFile: File): String? =
        runCatching { rcFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Anchored on gh's own wording so it cannot match a stray token on screen. */
    private val CODE = Regex("""one-time code:\s*([A-Z0-9]{4}-[A-Z0-9]{4})""")

    /**
     * Run one login to completion. [onCode] is called with each one-time code that appears
     * on gh's screen, which is the only thing the user has to act on while this polls.
     */
    suspend fun run(
        context: Context,
        onCode: (String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val guestTmp = LinuxRuntime.guestTmpDir(context)
        val workDir = File(guestTmp, WORK_DIR)
        val paneFile = File(workDir, "pane")
        val rcFile = File(workDir, "rc")

        try {
            File(guestTmp, SCRIPT_NAME).writeText(LOGIN_SCRIPT)
        } catch (e: Exception) {
            return@withContext Outcome.Failed("Could not prepare the guest: ${e.message}")
        }

        val process = LinuxRuntime.spawnInGuest(
            context,
            listOf("/bin/bash", "/tmp/$SCRIPT_NAME"),
            columns = 120,
            rows = 40,
        )
        if (process == null) {
            return@withContext Outcome.Failed("Could not start the Linux guest.")
        }
        loginProcess = process

        // The script says little, but an unread pty eventually fills and would stall it.
        // The drain reaching EIO is also the one honest signal that the script is gone -
        // see [died] in the loop below.
        // Atomic rather than a plain local: it is written on the drain thread and read on
        // this one, and a captured `var` gives no guarantee the read ever sees the write.
        val died = java.util.concurrent.atomic.AtomicBoolean(false)
        process.drainInBackground("KernGhAuth") { died.set(true) }

        var pane = ""
        var cancelled = false
        var rc = ""
        val deadline = android.os.SystemClock.elapsedRealtime() + FLOW_TIMEOUT_MS
        try {
            while (true) {
                // cancel() drops the handle. That is a deliberate stop, not a failure,
                // and must not be reported as one.
                if (loginProcess !== process) {
                    cancelled = true
                    break
                }
                if (paneFile.exists()) {
                    pane = runCatching { paneFile.readText() }.getOrDefault(pane)
                    CODE.find(pane)?.groupValues?.get(1)?.let(onCode)
                }
                // Content, not existence. `echo "EXIT=$?" > rc` creates the file by
                // truncation before it writes into it, so there is a window where it is
                // there and empty - and reading it then produced "", which fails the
                // EXIT=0 test below and told a user whose sign-in had just succeeded that
                // it had failed. The same race as LinuxRuntime's exit-code file.
                val result = readResult(rcFile)
                if (result != null) {
                    rc = result
                    break
                }

                // Nothing else ends this wait. The script self-terminates after fifteen
                // minutes, but if it never ran - a PRoot that failed to spawn, a guest
                // with no tmux - the rc file is never written by anyone, and this loop
                // used to poll a file that would never appear for as long as the app
                // lived, with the UI on "Contacting GitHub" behind it.
                if (died.get()) break
                if (android.os.SystemClock.elapsedRealtime() > deadline) break
                delay(1_000)
            }
        } finally {
            loginProcess = null
            runCatching { process.close() }
        }

        // One last look: the script writes rc immediately before exiting, so a drain that
        // reported death a moment early would otherwise lose a completed login.
        if (rc.isEmpty()) rc = readResult(rcFile).orEmpty()
        runCatching { workDir.deleteRecursively() }
        runCatching { File(guestTmp, SCRIPT_NAME).delete() }

        if (cancelled) return@withContext Outcome.Cancelled

        if (!rc.contains("EXIT=0")) {
            Log.w(TAG, "gh auth login failed: rc=$rc pane=${pane.takeLast(300)}")
            return@withContext Outcome.Failed(reasonFor(pane, rc))
        }

        Outcome.Ok
    }

    /**
     * Abandon a login the user no longer wants to finish.
     *
     * Dropping the handle is what [run]'s loop watches for, so this is the whole of the
     * stop: [run] returns [Outcome.Cancelled] and its caller knows to say nothing.
     */
    fun cancel() {
        val process = loginProcess
        loginProcess = null
        runCatching { process?.close() }
    }

    /**
     * The most useful thing on screen, preferring whatever gh complained about. gh's own
     * prompts stay on the pane after they are answered, so they are filtered out —
     * otherwise a failure gets reported as "Press Enter to open github.com".
     */
    internal fun reasonFor(pane: String, rc: String): String {
        if (rc.contains("timeout")) return "The code expired before it was approved."
        // No rc at all means the helper never got as far as writing one - it is not that
        // GitHub refused, it is that the script did not run. Saying "sign-in did not
        // complete" there sends the user back to GitHub, which is the wrong place.
        if (rc.isEmpty()) {
            return "The sign-in helper stopped before it could ask GitHub. " +
                "Check that git, gh and tmux are installed on the Status screen."
        }
        val lines = pane.lines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("?") &&
                    !it.startsWith("!") &&
                    !it.startsWith("Press Enter") &&
                    !CODE.containsMatchIn(it)
            }
        return lines.lastOrNull { it.contains("error", true) || it.contains("failed", true) }
            ?.take(160)
            ?: lines.lastOrNull()?.take(160)
            ?: "GitHub sign-in did not complete."
    }

    /**
     * Runs entirely inside one PRoot instance and mirrors gh's screen to a file.
     *
     * gh asks two questions before it starts polling GitHub, and both are answered here
     * rather than from Kotlin — inside tmux the answer is a keystroke, and this is the
     * only process that can reach the tmux server. Each answer is sent once; the prompt
     * stays on screen afterwards, so a flag file guards against pressing Enter again
     * into whatever has focus by then.
     */
    private val LOGIN_SCRIPT = """
        #!/bin/bash
        set -u
        DIR=/tmp/$WORK_DIR
        rm -rf "${'$'}DIR"; mkdir -p "${'$'}DIR"
        export BROWSER=true NO_COLOR=1 GH_NO_UPDATE_NOTIFIER=1

        SESSION=kern-ghauth
        tmux kill-session -t "${'$'}SESSION" 2>/dev/null
        tmux new-session -d -s "${'$'}SESSION" -x 120 -y 40 bash -c \
          'gh auth login --hostname github.com --git-protocol https --web; echo "EXIT=${'$'}?" > /tmp/$WORK_DIR/rc'

        # 900 seconds, which is how long a GitHub device code stays valid.
        for _ in ${'$'}(seq 1 900); do
          tmux capture-pane -t "${'$'}SESSION" -p > "${'$'}DIR/pane" 2>/dev/null || break

          if [ ! -f "${'$'}DIR/.credentials" ] && grep -q 'credentials? (Y/n)' "${'$'}DIR/pane"; then
            tmux send-keys -t "${'$'}SESSION" Enter && touch "${'$'}DIR/.credentials"
          fi
          if [ ! -f "${'$'}DIR/.browser" ] && grep -q 'Press Enter to open' "${'$'}DIR/pane"; then
            tmux send-keys -t "${'$'}SESSION" Enter && touch "${'$'}DIR/.browser"
          fi

          [ -f "${'$'}DIR/rc" ] && break
          sleep 1
        done

        tmux capture-pane -t "${'$'}SESSION" -p > "${'$'}DIR/pane" 2>/dev/null
        [ -f "${'$'}DIR/rc" ] || echo "EXIT=timeout" > "${'$'}DIR/rc"
        tmux kill-session -t "${'$'}SESSION" 2>/dev/null
    """.trimIndent()
}
