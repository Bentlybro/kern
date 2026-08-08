package dev.kern.app.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * browser. Driving that flow is [GitHubDeviceFlow]'s job; what is left here is the state
 * the UI watches.
 */
object GitHubAuth {

    /** Where the one-time code is entered. */
    const val DEVICE_URL = "https://github.com/login/device"

    /** gh keeps the token and the account name here, inside the guest. */
    private const val HOSTS = "/root/.config/gh/hosts.yml"

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

    /** Everything needed for git over HTTPS to work unattended. */
    val REQUIRED_TOOLS = listOf("git", "gh", "tmux")

    fun reset() {
        if (!GitHubDeviceFlow.running) _step.value = Step.Idle
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
        Apt.install(context, listOf("git", "gh", "tmux", "ca-certificates"))

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

    suspend fun signIn(context: Context) {
        _step.value = Step.Working("Contacting GitHub")

        val outcome = GitHubDeviceFlow.run(context) { code ->
            if ((_step.value as? Step.AwaitingApproval)?.code != code) {
                _step.value = Step.AwaitingApproval(code)
            }
        }
        when (outcome) {
            // cancel() has already put the UI back to Idle. Posting a failure on top would
            // tell the user something went wrong when they are the one who stopped it.
            GitHubDeviceFlow.Outcome.Cancelled -> return
            is GitHubDeviceFlow.Outcome.Failed -> {
                _step.value = Step.Failed(outcome.message)
                return
            }
            GitHubDeviceFlow.Outcome.Ok -> Unit
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
        GitHubDeviceFlow.cancel()
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
}
