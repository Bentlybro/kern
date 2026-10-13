package dev.kern.app.runtime

import android.content.Context

/**
 * Reading the git working tree so the cockpit can show a diff and commit one handed.
 *
 * This is useful with no agent at all, which is a supported way to use Kern — so it does
 * not belong to the agent, and the next verb someone adds (pull, branch, stash) has an
 * obvious home. Cloning and init still live in [ProjectRepository], where a repository is
 * a project being created, and the credential helper in [GitHubAuth].
 */
object GitCommands {

    data class Status(
        val branch: String,
        val changed: Int,
        val files: List<String>,
        val isRepo: Boolean,
    )

    suspend fun status(context: Context, projectPath: String): Status {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 2
            # symbolic-ref, not rev-parse: in a repo with no commits yet rev-parse answers
            # "HEAD", which is what the cockpit showed for every freshly created project.
            echo "BRANCH:${'$'}(git symbolic-ref --short -q HEAD || git rev-parse --short HEAD 2>/dev/null)"
            git status --porcelain 2>/dev/null
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 20_000)
            ?: return Status("", 0, emptyList(), false)
        if (!r.ok) return Status("", 0, emptyList(), false)

        var branch = ""
        val files = mutableListOf<String>()
        r.stdout.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("BRANCH:") -> branch = line.removePrefix("BRANCH:").trim()
                line.isNotBlank() -> files += line.trim()
            }
        }
        return Status(branch, files.size, files, true)
    }

    /** Unified diff of the working tree — the format that reads well at phone width. */
    suspend fun diff(context: Context, projectPath: String, maxLines: Int = 400): List<String> {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            git --no-pager diff --no-color -U2
            git --no-pager diff --no-color -U2 --cached
            # New files, as all-added diffs. git diff ignores untracked files, so a project
            # whose only change was a new file showed "1 changed" over "No changes" - and
            # "Commit all" would commit something the review screen never showed.
            git ls-files --others --exclude-standard -z | while IFS= read -r -d '' f; do
              git --no-pager diff --no-color --no-index -- /dev/null "${'$'}f"
            done
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 30_000) ?: return emptyList()
        // Truncate here rather than piping through head: one less thing in the pipeline
        // that can swallow output, and the limit is a UI concern anyway.
        return r.stdout.split('\n').dropLastWhile { it.isBlank() }.take(maxLines)
    }

    /**
     * git's own wording for "I needed credentials and was not allowed to ask", turned into
     * what to do about it. Guest commands run with prompts off (see [LinuxRuntime.run]),
     * so this is how an unauthenticated remote now fails - promptly, but in words that
     * point at the terminal rather than at the sign-in button that fixes it.
     */
    internal fun explain(line: String?): String? = when {
        line == null -> null
        line.contains("terminal prompts disabled") ||
            line.contains("could not read Username") ||
            line.contains("Authentication failed") ->
            "GitHub needs you to sign in first — settings › GitHub."
        line.contains("Host key verification failed") ||
            line.contains("Permission denied (publickey)") ->
            "This remote uses SSH and no key is set up. Use the https:// URL, " +
                "or add a key in the terminal."
        line.contains("has no upstream branch") ->
            "This branch has never been pushed — push it once from the terminal with " +
                "git push -u origin HEAD."
        line.contains("No configured push destination") ||
            line.contains("does not appear to be a git repository") ->
            "This repository has no remote yet. Add one in the terminal: " +
                "git remote add origin <url>"
        else -> line
    }

    /**
     * Why [r] failed, in words. Matched against the whole output, not only its last line:
     * git's "no remote" explanation ends with "git push <name>", which on its own was what
     * the cockpit showed (caught on device).
     */
    private fun reason(r: LinuxRuntime.Result): String {
        val whole = r.stdout + "\n" + r.stderr
        val explained = explain(whole)
        return if (explained != whole) explained!! else explain(r.lastLine()) ?: "exit ${r.exitCode}"
    }

    /** What setup writes so git will commit at all; see [GuestConfig.polish]. */
    const val PLACEHOLDER_EMAIL = "kern@localhost"

    data class Identity(val name: String, val email: String) {
        val isPlaceholder: Boolean get() = email.isBlank() || email == PLACEHOLDER_EMAIL
    }

    /** Who commits made here are authored as. Null when the guest did not answer. */
    suspend fun identity(context: Context): Identity? {
        val r = LinuxRuntime.run(
            context,
            "echo \"N=${'$'}(git config --global user.name)\"; " +
                "echo \"E=${'$'}(git config --global user.email)\"",
            timeoutMs = 20_000,
        ) ?: return null
        fun field(key: String) = r.stdout.lineSequence()
            .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim().orEmpty()
        return Identity(field("N"), field("E"))
    }

    suspend fun setIdentity(context: Context, name: String, email: String): Boolean =
        LinuxRuntime.run(
            context,
            "git config --global user.name ${sq(name.trim())} && " +
                "git config --global user.email ${sq(email.trim())}",
            timeoutMs = 20_000,
        )?.ok == true

    suspend fun commitAll(context: Context, projectPath: String, message: String): String {
        val script = """
            set -o pipefail
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git add -A 2>&1
            git commit -m ${sq(message)} 2>&1
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 60_000)
            ?: return "Timed out"
        // Without this the cockpit's status text becomes "bash: line 3: git: command not found".
        if (r.exitCode == 3) return "git is not installed yet — check Status."
        if (r.ok) return "Committed — ${r.lastLine() ?: "done"}"
        return "Commit failed: ${reason(r)}"
    }

    /**
     * Fast-forward only, deliberately. A merge stopped halfway on conflicts leaves the
     * repository mid-merge with nothing on a phone able to finish or abort it — the
     * cockpit has no conflict UI and should not pretend to. When histories have diverged
     * this fails in one line, which the cockpit shows, and resolving belongs in the
     * terminal or the workbench.
     */
    suspend fun pull(context: Context, projectPath: String): String {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git pull --ff-only 2>&1
        """.trimIndent()
        val r = LinuxRuntime.run(context, "set -o pipefail\n$script", timeoutMs = 120_000)
            ?: return "Timed out"
        if (r.exitCode == 3) return "git is not installed yet — check Status."
        if (r.ok) return r.lastLine() ?: "Pulled"
        return "Pull failed: ${reason(r)}"
    }

    suspend fun push(context: Context, projectPath: String): String {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git push 2>&1
        """.trimIndent()
        val r = LinuxRuntime.run(context, "set -o pipefail\n$script", timeoutMs = 120_000)
            ?: return "Timed out"
        if (r.exitCode == 3) return "git is not installed yet — check Status."
        if (r.ok) return r.lastLine() ?: "Pushed"
        return "Push failed: ${reason(r)}"
    }
}
