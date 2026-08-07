package dev.kern.app.runtime

import android.content.Context

/**
 * Reads the state of work happening inside the Linux guest — the agent's terminal and the
 * git working tree — so the native cockpit (M5) can show what is going on and let you
 * steer it one-handed.
 *
 * Deliberately tool-agnostic (decision D7): it reads the tmux pane and git, not any
 * particular agent's API, so it works with Claude Code, `pi`, aider, or a plain build.
 */
object AgentRepository {

    private const val SESSION = "kern"

    data class Snapshot(
        val tail: List<String>,
        val state: State,
    )

    enum class State {
        /** Something is actively producing output. */
        Working,

        /** Output has stopped at what looks like a shell prompt. */
        Idle,

        /** Output has stopped at what looks like a question awaiting an answer. */
        AwaitingInput,

        Unknown,
    }

    /** Grab the visible pane contents from the persistent tmux session. */
    suspend fun capture(context: Context, lines: Int = 40): List<String> {
        val result = LinuxRuntime.run(
            context,
            "tmux capture-pane -p -t $SESSION 2>/dev/null | tail -n $lines",
            timeoutMs = 10_000,
        ) ?: return emptyList()
        return result.stdout.split('\n').dropLastWhile { it.isBlank() }
    }

    /**
     * Classify the tail. Heuristic by necessity — agents do not announce their state —
     * but it only drives notifications and a hint chip, so a wrong guess is cheap.
     */
    fun classify(tail: List<String>): State {
        val last = tail.lastOrNull { it.isNotBlank() }?.trim() ?: return State.Unknown
        val questionish = Regex(
            "(\\?\\s*$)|(\\[y/n\\])|(\\(y/N\\))|(yes/no)|(continue\\??)|(approve)|(permission)",
            RegexOption.IGNORE_CASE,
        )
        return when {
            questionish.containsMatchIn(last) -> State.AwaitingInput
            last.endsWith("$") || last.endsWith("#") || last.endsWith("%") ||
                Regex("[~\\w/\\-.]+\\s*\\$\\s*$").containsMatchIn(last) -> State.Idle
            else -> State.Working
        }
    }

    suspend fun snapshot(context: Context, lines: Int = 40): Snapshot {
        val tail = capture(context, lines)
        return Snapshot(tail, classify(tail))
    }

    /** Send a line of input to the agent's terminal without stealing focus. */
    suspend fun send(context: Context, text: String): Boolean {
        val escaped = text.replace("'", "'\\''")
        val r = LinuxRuntime.run(
            context,
            "tmux send-keys -t $SESSION '$escaped' Enter 2>/dev/null && echo SENT",
            timeoutMs = 10_000,
        )
        return r?.ok == true
    }

    // ---- git ---------------------------------------------------------------

    data class GitStatus(
        val branch: String,
        val changed: Int,
        val files: List<String>,
        val isRepo: Boolean,
    )

    suspend fun gitStatus(context: Context, projectPath: String): GitStatus {
        val script = """
            cd '$projectPath' 2>/dev/null || exit 1
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
            cd '$projectPath' 2>/dev/null || exit 1
            git --no-pager diff --no-color -U2
            git --no-pager diff --no-color -U2 --cached
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 30_000) ?: return emptyList()
        // Truncate here rather than piping through head: one less thing in the pipeline
        // that can swallow output, and the limit is a UI concern anyway.
        return r.stdout.split('\n').dropLastWhile { it.isBlank() }.take(maxLines)
    }

    suspend fun gitCommitAll(context: Context, projectPath: String, message: String): String {
        val msg = message.replace("'", "'\\''")
        val script = """
            cd '$projectPath' 2>/dev/null || exit 1
            git add -A 2>&1
            git commit -m '$msg' 2>&1 | tail -n 3
        """.trimIndent()
        val r = LinuxRuntime.run(context, script, timeoutMs = 60_000)
            ?: return "Timed out"
        return r.stdout.split('\n').lastOrNull { it.isNotBlank() }?.trim()
            ?: r.stderr.take(140).ifBlank { "Committed" }
    }

    suspend fun gitPush(context: Context, projectPath: String): String {
        val r = LinuxRuntime.run(
            context,
            "cd '$projectPath' 2>/dev/null && git push 2>&1 | tail -n 3",
            timeoutMs = 120_000,
        ) ?: return "Timed out"
        return (r.stdout + r.stderr).split('\n').lastOrNull { it.isNotBlank() }?.trim()
            ?: "Pushed"
    }
}
