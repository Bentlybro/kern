package dev.kern.app.runtime

import android.content.Context

/**
 * Which CLI agent the cockpit runs, and the state of the work it is doing.
 *
 * The agent's *terminal* is not here. It is a session in `TerminalSessions`, like any
 * other, which is what fixed the cockpit rendering TUI agents as gibberish: this used to
 * keep agent output as a list of lines, and a TUI does not emit lines. It repaints a
 * screen with cursor movement, and no amount of escape stripping turns that into text.
 * Giving it the same emulator the terminal uses was the whole fix.
 *
 * What remains here is the choice of agent, and reading the git working tree so the
 * cockpit can show a diff and commit one handed. That half is useful with no agent at
 * all, which is a supported way to use Kern.
 */
object AgentRepository {

    private const val PREFS = "kern"
    private const val KEY_COMMAND = "agent_command"

    // ---- which agent --------------------------------------------------------

    data class Preset(val label: String, val command: String)

    /**
     * Offered in settings. A convenience, not a restriction — anything on PATH in the
     * guest can be typed instead.
     */
    val PRESETS = listOf(
        Preset("pi", "pi"),
        Preset("Claude Code", "claude"),
        Preset("Aider", "aider"),
        Preset("Codex", "codex"),
        Preset("OpenCode", "opencode"),
    )

    /** Empty means no agent, which is a normal way to use Kern. */
    fun command(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMMAND, "").orEmpty()

    fun setCommand(context: Context, value: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMMAND, value.trim()).apply()
    }

    fun isConfigured(context: Context): Boolean = command(context).isNotBlank()

    // ---- git ---------------------------------------------------------------

    data class GitStatus(
        val branch: String,
        val changed: Int,
        val files: List<String>,
        val isRepo: Boolean,
    )

    suspend fun gitStatus(context: Context, projectPath: String): GitStatus {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 2
            echo "BRANCH:${'$'}(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
            git status --porcelain 2>/dev/null
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 20_000)
            ?: return GitStatus("", 0, emptyList(), false)
        if (!r.ok) return GitStatus("", 0, emptyList(), false)

        var branch = ""
        val files = mutableListOf<String>()
        r.stdout.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("BRANCH:") -> branch = line.removePrefix("BRANCH:").trim()
                line.isNotBlank() -> files += line.trim()
            }
        }
        return GitStatus(branch, files.size, files, true)
    }

    /** Unified diff of the working tree — the format that reads well at phone width. */
    suspend fun gitDiff(context: Context, projectPath: String, maxLines: Int = 400): List<String> {
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

    suspend fun gitCommitAll(context: Context, projectPath: String, message: String): String {
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
        return r.stdout.split('\n').lastOrNull { it.isNotBlank() }?.trim()
            ?: r.stderr.take(140).ifBlank { "Committed" }
    }

    suspend fun gitPush(context: Context, projectPath: String): String {
        val script = """
            cd ${sq(projectPath)} 2>/dev/null || exit 1
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git push 2>&1 | tail -n 3
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 120_000)
            ?: return "Timed out"
        if (r.exitCode == 3) return "git is not installed yet — check Status."
        return (r.stdout + r.stderr).split('\n').lastOrNull { it.isNotBlank() }?.trim()
            ?: "Pushed"
    }
}
