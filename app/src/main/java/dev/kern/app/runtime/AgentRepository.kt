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
}
