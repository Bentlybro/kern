package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Signing the guest in to GitHub.
 *
 * The valuable part is not `gh` itself but `gh auth setup-git`, which registers gh as
 * git's credential helper. After that, plain `git clone`, `git pull` and `git push` over
 * HTTPS authenticate on their own — the editor's Git panel, the terminal, and any CLI
 * agent running in the guest all inherit it without knowing this screen exists.
 *
 * The mechanism is GitHub's device flow, chosen because it suits a phone: no password
 * and no personal access token to type on a touch keyboard. GitHub issues an
 * eight-character code, the app copies it to the clipboard, and approval happens in a
 * browser.
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
object GitHubAuth {

    private const val TAG = "Kern"

    /** Where the one-time code is entered. */
    const val DEVICE_URL = "https://github.com/login/device"

    /** gh keeps the token and the account name here, inside the guest. */
    private const val HOSTS = "/root/.config/gh/hosts.yml"

    private const val WORK_DIR = "kern-gh"
    private const val SCRIPT_NAME = "kern-ghlogin.sh"

    sealed interface Account {
        /**
         * The guest did not answer — still starting, busy under `apt`, or timed out. It
         * says nothing about what is installed, so it must never be reported as tools
         * missing: that offers a long re-install of packages that are already there.
         */
        data object Unavailable : Account
        /** git, gh or tmux are not installed yet. */
        data class ToolsMissing(val missing: List<String>) : Account
        data object SignedOut : Account
        data class SignedIn(val login: String, val email: String?) : Account
    }

    sealed interface Step {
        data object Idle : Step
        data class Working(val what: String) : Step
        /** GitHub is waiting for [code] to be entered at [DEVICE_URL]. */
        data class AwaitingApproval(val code: String) : Step
        data class Done(val login: String) : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** The in-flight login, kept so the UI can cancel a flow the user abandons. */
    @Volatile
    private var loginProcess: PtyProcess? = null

    /** Anchored on gh's own wording so it cannot match a stray token on screen. */
    private val CODE = Regex("""one-time code:\s*([A-Z0-9]{4}-[A-Z0-9]{4})""")

    /** Everything needed for git over HTTPS to work unattended. */
    val REQUIRED_TOOLS = listOf("git", "gh", "tmux")

    fun reset() {
        if (loginProcess == null) _step.value = Step.Idle
    }

    // ---- state --------------------------------------------------------------

    suspend fun account(context: Context): Account {
        val result = LinuxRuntime.run(
            context,
            """
            for tool in ${REQUIRED_TOOLS.joinToString(" ")}; do
              command -v "${'$'}tool" >/dev/null 2>&1 || echo "MISSING=${'$'}tool"
            done
            # Read the account out of gh's own config rather than asking the API, so
            # this still answers correctly with no network.
            if grep -q oauth_token $HOSTS 2>/dev/null; then
              echo "LOGIN=${'$'}(sed -n 's/^[[:space:]]*user:[[:space:]]*//p' $HOSTS | head -1)"
              echo "EMAIL=${'$'}(git config --global user.email 2>/dev/null)"
            fi
            """.trimIndent(),
            timeoutMs = 30_000,
        ) ?: return Account.Unavailable

        val missing = result.lines
            .filter { it.startsWith("MISSING=") }
            .map { it.removePrefix("MISSING=") }
        if (missing.isNotEmpty()) return Account.ToolsMissing(missing)

        val login = field(result.stdout, "LOGIN") ?: return Account.SignedOut
        return Account.SignedIn(login, field(result.stdout, "EMAIL"))
    }

    // ---- installing ---------------------------------------------------------

    /**
     * All of these live in Ubuntu's own repositories, so no extra apt source is needed.
     *
     * `ca-certificates` is not optional: without a trust store gh's Go TLS stack rejects
     * github.com outright with "certificate signed by unknown authority".
     */
    suspend fun installTools(context: Context): Boolean {
        _step.value = Step.Working("Installing git and the GitHub CLI")
        LinuxRuntime.run(context, "apt-get update -qq", timeoutMs = 300_000)
        LinuxRuntime.run(
            context,
            "apt-get install -y git gh tmux ca-certificates && update-ca-certificates",
            timeoutMs = 900_000,
        )

        val account = account(context)
        val ok = account !is Account.ToolsMissing
        _step.value = if (ok) {
            Step.Idle
        } else {
            Step.Failed(
                "Could not install: ${(account as Account.ToolsMissing).missing.joinToString(", ")}.",
            )
        }
        return ok
    }

    // ---- signing in ---------------------------------------------------------

    suspend fun signIn(context: Context) = withContext(Dispatchers.IO) {
        _step.value = Step.Working("Contacting GitHub")

        val guestTmp = LinuxRuntime.guestTmpDir(context)
        val workDir = File(guestTmp, WORK_DIR)
        val paneFile = File(workDir, "pane")
        val rcFile = File(workDir, "rc")

        try {
            File(guestTmp, SCRIPT_NAME).writeText(LOGIN_SCRIPT)
        } catch (e: Exception) {
            _step.value = Step.Failed("Could not prepare the guest: ${e.message}")
            return@withContext
        }

        val process = PtyProcess.spawn(
            command = LinuxRuntime.prootBinary(context).absolutePath,
            argv = LinuxRuntime.prootArgs(
                context,
                listOf("/bin/bash", "/tmp/$SCRIPT_NAME"),
            ),
            env = LinuxRuntime.prootEnv(context),
            cwd = context.filesDir.absolutePath,
            columns = 120,
            rows = 40,
        )
        if (process == null) {
            _step.value = Step.Failed("Could not start the Linux guest.")
            return@withContext
        }
        loginProcess = process

        // The script says little, but an unread pty eventually fills and would stall it.
        Thread {
            runCatching {
                val sink = ByteArray(4096)
                while (process.input.read(sink) >= 0) Unit
            }
        }.apply { isDaemon = true }.start()

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
                    CODE.find(pane)?.groupValues?.get(1)?.let { code ->
                        if ((_step.value as? Step.AwaitingApproval)?.code != code) {
                            _step.value = Step.AwaitingApproval(code)
                        }
                    }
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

        // cancel() has already put the UI back to Idle. Posting a failure on top would
        // tell the user something went wrong when they are the one who stopped it.
        if (cancelled) return@withContext

        if (!rc.contains("EXIT=0")) {
            Log.w(TAG, "gh auth login failed: rc=$rc pane=${pane.takeLast(300)}")
            _step.value = Step.Failed(reasonFor(pane, rc))
            return@withContext
        }

        _step.value = Step.Working("Configuring git")
        val login = configureGit(context)
        _step.value = if (login != null) {
            Step.Done(login)
        } else {
            Step.Failed("Signed in, but git could not be configured.")
        }
    }

    /** Abandon a login the user no longer wants to finish. */
    fun cancel() {
        val process = loginProcess
        loginProcess = null
        runCatching { process?.close() }
        _step.value = Step.Idle
    }

    suspend fun signOut(context: Context) {
        LinuxRuntime.run(
            context,
            "gh auth logout --hostname github.com >/dev/null 2>&1; rm -f $HOSTS",
            timeoutMs = 60_000,
        )
        _step.value = Step.Idle
    }

    /**
     * Hand the credentials to git itself, and replace the placeholder identity that
     * setup leaves behind — otherwise every commit made on the phone is authored by
     * "Kern <kern@localhost>".
     */
    private suspend fun configureGit(context: Context): String? {
        val result = LinuxRuntime.run(
            context,
            """
            gh auth setup-git --hostname github.com >/dev/null 2>&1

            login=${'$'}(sed -n 's/^[[:space:]]*user:[[:space:]]*//p' $HOSTS | head -1)
            [ -n "${'$'}login" ] || exit 1

            name=${'$'}(gh api user --jq '.name // .login' 2>/dev/null)
            [ -n "${'$'}name" ] || name="${'$'}login"

            # Accounts that keep their address private report no email; GitHub's
            # no-reply form still attributes the commit to the right person.
            email=${'$'}(gh api user --jq '.email // empty' 2>/dev/null)
            if [ -z "${'$'}email" ]; then
              id=${'$'}(gh api user --jq .id 2>/dev/null)
              email="${'$'}id+${'$'}login@users.noreply.github.com"
            fi

            git config --global user.name "${'$'}name"
            git config --global user.email "${'$'}email"
            echo "LOGIN=${'$'}login"
            """.trimIndent(),
            timeoutMs = 120_000,
        )
        return field(result?.stdout.orEmpty(), "LOGIN")
    }

    // ---- helpers ------------------------------------------------------------

    private fun field(output: String, key: String): String? =
        output.lineSequence()
            .firstOrNull { it.trimStart().startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

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
