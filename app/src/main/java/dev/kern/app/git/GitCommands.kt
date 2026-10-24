package dev.kern.app.git

import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.guest.sq
import android.content.Context
import android.os.SystemClock

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

    /**
     * The branch and changed files, or null when the guest did not answer - a timeout, a
     * PRoot that would not start. Null is not "not a repository": the cockpit keeps what it
     * last showed rather than flipping to an empty, non-repo state on one slow poll, which
     * also disabled the commit button under someone about to press it.
     */
    suspend fun status(context: Context, projectPath: String): Status? {
        val r = LinuxRuntime.run(context, STATUS_SCRIPT_HEAD.format(sq(projectPath)), timeoutMs = 20_000)
            ?: return null
        return parseStatus(r, r.stdout)
    }

    data class Changes(val status: Status, val diff: List<String>)

    /**
     * [status] and [diff] in one guest command. The Changes tab polls both every few
     * seconds, and as two scripts that was two PRoot spawns per poll for the same few
     * milliseconds of git. Null when the guest did not answer, as for [status].
     */
    suspend fun changes(context: Context, projectPath: String, maxLines: Int = 400): Changes? {
        val script = STATUS_SCRIPT_HEAD.format(sq(projectPath)) + "\n" +
            "echo '$DIFF_MARKER'\n" + diffScript(maxLines)
        val r = LinuxRuntime.run(context, script, timeoutMs = 30_000, maxOutputBytes = MAX_DIFF_BYTES)
            ?: return null
        val marker = r.stdout.indexOf(DIFF_MARKER)
        val head = if (marker < 0) r.stdout else r.stdout.substring(0, marker)
        val status = parseStatus(r, head) ?: return null
        val diff = if (marker < 0) {
            emptyList()
        } else {
            diffLines(r.stdout.substring(marker + DIFF_MARKER.length), maxLines)
        }
        return Changes(status, diff)
    }

    /** Unified diff of the working tree — the format that reads well at phone width. */
    suspend fun diff(context: Context, projectPath: String, maxLines: Int = 400): List<String> {
        val script = "cd ${sq(projectPath)} 2>/dev/null || exit 1\n" + diffScript(maxLines)
        val r = LinuxRuntime.run(context, script, timeoutMs = 30_000, maxOutputBytes = MAX_DIFF_BYTES)
            ?: return emptyList()
        return diffLines(r.stdout, maxLines)
    }

    /** Between the status and the diff in [changes]. No porcelain line can look like it. */
    private const val DIFF_MARKER = "@@KERN-DIFF@@"

    /**
     * A ceiling on what is read back at all. [diffScript] already stops at [maxLines], so
     * this only matters for a few enormous lines - a minified bundle, a lockfile on one
     * line - which would otherwise be read whole to show a fraction of.
     */
    private const val MAX_DIFF_BYTES = 512 * 1024

    /** Untracked files shown as new-file diffs, at most; each one is a git process. */
    private const val MAX_UNTRACKED = 200

    private const val STATUS_SCRIPT_HEAD = """cd %s 2>/dev/null || exit 1
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 2
# symbolic-ref, not rev-parse: in a repo with no commits yet rev-parse answers
# "HEAD", which is what the cockpit showed for every freshly created project.
echo "BRANCH:$(git symbolic-ref --short -q HEAD || git rev-parse --short HEAD 2>/dev/null)"
git status --porcelain 2>/dev/null"""

    /**
     * The diff, capped in the guest. It used to be produced whole - every untracked file
     * included - and written to a file the app then read whole, only to keep 400 lines of
     * it; a project with a build directory nobody had ignored yet made that megabytes, on
     * every four-second poll. head stops git once the screen is full, and the untracked
     * loop stops after [MAX_UNTRACKED] files.
     */
    private fun diffScript(maxLines: Int): String = """
        {
          git --no-pager diff --no-color -U2
          git --no-pager diff --no-color -U2 --cached
          # New files, as all-added diffs. git diff ignores untracked files, so a project
          # whose only change was a new file showed "1 changed" over "No changes" - and
          # "Commit all" would commit something the review screen never showed.
          n=0
          git ls-files --others --exclude-standard -z | while IFS= read -r -d '' f; do
            n=${'$'}((n + 1))
            [ "${'$'}n" -gt $MAX_UNTRACKED ] && break
            git --no-pager diff --no-color --no-index -- /dev/null "${'$'}f"
          done
        } 2>/dev/null | head -n ${maxLines + 1}
    """.trimIndent()

    private fun diffLines(text: String, maxLines: Int): List<String> =
        text.split('\n').dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.take(maxLines)

    /**
     * Exit 1 and 2 are the script saying "no such folder" and "not a repository"; anything
     * else that is not success is git failing in some way that says nothing about either,
     * so it is treated like no answer.
     */
    private fun parseStatus(r: LinuxRuntime.Result, text: String): Status? {
        if (r.exitCode == 1 || r.exitCode == 2) return Status("", 0, emptyList(), false)
        if (!r.ok) return null
        var branch = ""
        val files = mutableListOf<String>()
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("BRANCH:") -> branch = line.removePrefix("BRANCH:").trim()
                line.isNotBlank() -> files += line.trim()
            }
        }
        return Status(branch, files.size, files, true)
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

    /**
     * The guard every git script starts with: be in the project, and have git. Exit 3 is
     * reserved for "git is missing", which [GIT_MISSING] turns into words - without it the
     * status text became "bash: line 3: git: command not found".
     */
    internal const val REQUIRE_GIT = "command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }"
    internal const val GIT_MISSING_EXIT = 3
    const val GIT_MISSING = "git is not installed yet — check Status, it may still be setting up."

    /**
     * Run [commands] in [projectPath] and say how it went: [done] for a success with nothing
     * to show, otherwise [verb] + " failed: " + the reason in words.
     */
    private suspend fun runGit(
        context: Context,
        projectPath: String,
        commands: String,
        verb: String,
        done: String,
        timeoutMs: Long = 120_000,
        success: (LinuxRuntime.Result) -> String = { it.lastLine() ?: done },
    ): String {
        val script = "set -o pipefail\ncd ${sq(projectPath)} 2>/dev/null || exit 1\n" +
            "$REQUIRE_GIT\n$commands"
        val started = SystemClock.elapsedRealtime()
        // run() answers null for a timeout and for a guest that never started alike. Only
        // one of those took the whole time, so the clock tells them apart for free.
        val r = LinuxRuntime.run(context, script, timeoutMs = timeoutMs)
            ?: return if (SystemClock.elapsedRealtime() - started >= timeoutMs) {
                "Timed out"
            } else {
                "$verb failed: could not run git in Linux"
            }
        if (r.exitCode == GIT_MISSING_EXIT) return GIT_MISSING
        if (r.ok) return success(r)
        return "$verb failed: ${reason(r)}"
    }

    suspend fun commitAll(context: Context, projectPath: String, message: String): String =
        runGit(
            context, projectPath,
            "git add -A 2>&1\ngit commit -m ${sq(message)} 2>&1",
            verb = "Commit", done = "done", timeoutMs = 60_000,
            success = { "Committed — ${it.lastLine() ?: "done"}" },
        )

    /**
     * Fast-forward only, deliberately. A merge stopped halfway on conflicts leaves the
     * repository mid-merge with nothing on a phone able to finish or abort it — the
     * cockpit has no conflict UI and should not pretend to. When histories have diverged
     * this fails in one line, which the cockpit shows, and resolving belongs in the
     * terminal or the workbench.
     */
    suspend fun pull(context: Context, projectPath: String): String =
        runGit(context, projectPath, "git pull --ff-only 2>&1", verb = "Pull", done = "Pulled")

    suspend fun push(context: Context, projectPath: String): String =
        runGit(context, projectPath, "git push 2>&1", verb = "Push", done = "Pushed")
}
