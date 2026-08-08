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
        process.drainInBackground("KernGhAuth")

        var pane = ""
        var cancelled = false
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
                if (rcFile.exists()) break
                delay(1_000)
            }
        } finally {
            loginProcess = null
            runCatching { process.close() }
        }

        val rc = runCatching { rcFile.readText().trim() }.getOrDefault("")
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
    private fun reasonFor(pane: String, rc: String): String {
        if (rc.contains("timeout")) return "The code expired before it was approved."
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
