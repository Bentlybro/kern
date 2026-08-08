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
            echo "BRANCH:${'$'}(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
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
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 30_000) ?: return emptyList()
        // Truncate here rather than piping through head: one less thing in the pipeline
        // that can swallow output, and the limit is a UI concern anyway.
        return r.stdout.split('\n').dropLastWhile { it.isBlank() }.take(maxLines)
    }

    suspend fun commitAll(context: Context, projectPath: String, message: String): String {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git add -A 2>&1
            git commit -m ${sq(message)} 2>&1 | tail -n 3
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 60_000)
            ?: return "Timed out"
        // Without this the cockpit's status text becomes "bash: line 3: git: command not found".
        if (r.exitCode == 3) return "git is not installed yet — check Status."
        return r.lastLine() ?: "Committed"
    }

    suspend fun push(context: Context, projectPath: String): String {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git push 2>&1 | tail -n 3
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 120_000)
            ?: return "Timed out"
        if (r.exitCode == 3) return "git is not installed yet — check Status."
        return r.lastLine() ?: "Pushed"
    }
}
