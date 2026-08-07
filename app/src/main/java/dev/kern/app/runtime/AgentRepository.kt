package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Runs a CLI coding agent inside the Linux guest and reads the state of its work — its
 * output and the git working tree — so the native cockpit (M5) can show what is going on
 * and let you steer it one-handed.
 *
 * Deliberately tool-agnostic (decision D7): it drives whatever command the user names and
 * reads the terminal and git, not any particular agent's API. `pi`, Claude Code, aider,
 * codex or a plain script all work the same way, and having no agent at all is a
 * supported choice — the git half of the cockpit is useful on its own.
 *
 * **Why it owns a process rather than using tmux.** It used to read a tmux session by
 * name through [LinuxRuntime.run], which spawns a fresh PRoot for every call — and a tmux
 * server started under one PRoot instance is unreachable from another. The socket is
 * plainly there on disk and `tmux list-sessions` still answers `no server running`. So
 * the cockpit could never see the terminal it was supposedly watching; it was not that it
 * only worked when an agent happened to be open, it never worked. The agent now runs on a
 * pty this object holds for its lifetime, exactly as code-server does, and output is read
 * from that pty rather than asked for across a process boundary that cannot be crossed.
 */
object AgentRepository {

    private const val TAG = "Kern"
    private const val PREFS = "kern"
    private const val KEY_COMMAND = "agent_command"

    /** How much scrollback the cockpit can show. */
    private const val BUFFER_LINES = 600

    // ---- which agent --------------------------------------------------------

    data class Preset(val label: String, val command: String)

    /**
     * Offered in settings. The list is a convenience, not a restriction — anything on
     * PATH in the guest can be typed in instead.
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

    // ---- the running agent --------------------------------------------------

    @Volatile
    private var process: PtyProcess? = null

    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    private val pending = StringBuilder()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Start the configured agent in [workingDir]. Idempotent. */
    fun start(context: Context, workingDir: String): Boolean {
        if (process != null) return true
        val command = command(context)
        if (command.isBlank()) return false

        // -lc so the guest's profile is loaded: agents are usually installed by a package
        // manager that puts them somewhere only a login shell knows about.
        val spawned = PtyProcess.spawn(
            command = LinuxRuntime.prootBinary(context).absolutePath,
            argv = LinuxRuntime.prootArgs(
                context,
                listOf("/bin/bash", "-lc", command),
                workingDir,
            ),
            env = LinuxRuntime.prootEnv(context),
            cwd = context.applicationContext.filesDir.absolutePath,
            columns = 100,
            rows = 30,
        )
        if (spawned == null) {
            Log.w(TAG, "agent: could not start '$command'")
            return false
        }

        synchronized(lock) {
            buffer.clear()
            pending.setLength(0)
        }
        process = spawned
        _running.value = true
        append("$ $command")

        Thread { drain(spawned) }.apply { isDaemon = true; name = "kern-agent" }.start()
        return true
    }

    fun stop() {
        val current = process ?: return
        process = null
        _running.value = false
        runCatching { current.close() }
        append("— agent stopped —")
    }

    /**
     * Read the pty until the agent exits.
     *
     * A pty master reports EIO rather than EOF once its child is gone, so the end of a
     * perfectly normal run arrives as an exception.
     */
    private fun drain(pty: PtyProcess) {
        val chunk = ByteArray(4096)
        try {
            while (true) {
                val read = pty.input.read(chunk)
                if (read < 0) break
                absorb(String(chunk, 0, read, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Expected: the child exited.
        } finally {
            if (process === pty) {
                process = null
                _running.value = false
                append("— agent exited —")
            }
        }
    }

    /** Accumulate raw pty bytes into whole display lines. */
    private fun absorb(text: String) {
        synchronized(lock) {
            pending.append(text)
            // Carriage returns count as breaks: agents redraw progress that way, and each
            // redraw is worth showing as its own line rather than being lost.
            var index = pending.indexOfFirst { it == '\n' || it == '\r' }
            while (index >= 0) {
                addLine(pending.substring(0, index))
                pending.delete(0, index + 1)
                index = pending.indexOfFirst { it == '\n' || it == '\r' }
            }
            // Guard against an agent that never emits a newline.
            if (pending.length > 4096) {
                addLine(pending.toString())
                pending.setLength(0)
            }
        }
    }

    private fun addLine(raw: String) {
        val clean = ANSI.replace(raw, "").trimEnd()
        if (clean.isBlank() && buffer.lastOrNull()?.isBlank() == true) return
        buffer.addLast(clean)
        while (buffer.size > BUFFER_LINES) buffer.removeFirst()
    }

    private fun append(line: String) {
        synchronized(lock) { addLine(line) }
    }

    /** Colour and cursor control read as noise once the pane is native. */
    private val ANSI = Regex("\\[[0-9;?]*[a-zA-Z]|\\][^]*|[()][B0]")

    private fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int {
        for (i in indices) if (predicate(this[i])) return i
        return -1
    }

    // ---- what the cockpit reads ---------------------------------------------

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

    /** The agent's recent output. */
    fun capture(lines: Int = 40): List<String> = synchronized(lock) {
        val extra = pending.toString().let { if (it.isBlank()) null else ANSI.replace(it, "") }
        val all = if (extra == null) buffer.toList() else buffer.toList() + extra
        all.takeLast(lines)
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
        val tail = capture(lines)
        return Snapshot(tail, classify(tail))
    }

    /** Send a line of input to the agent. */
    suspend fun send(context: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        val current = process ?: return@withContext false
        runCatching {
            current.output.write((text + "\n").toByteArray(Charsets.UTF_8))
            current.output.flush()
            true
        }.getOrDefault(false)
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
