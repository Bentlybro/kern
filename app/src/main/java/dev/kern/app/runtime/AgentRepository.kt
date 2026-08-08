package dev.kern.app.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        Prefs.of(context).getString(Prefs.KEY_AGENT_COMMAND, "").orEmpty()

    /** Null until the first read — empty is a real answer here, so it cannot mean "unread". */
    private val _command = MutableStateFlow<String?>(null)

    /**
     * The same fact as [command], live.
     *
     * A pref is not observable, so every reader used to re-read it on a key of its own
     * invention — the top bar on its overflow menu opening, the cockpit on a refresh
     * counter — and picking an agent in settings then showed up anywhere between at once
     * and never. Collect this instead and there is one answer at a time.
     */
    fun commandFlow(context: Context): StateFlow<String> {
        if (_command.value == null) _command.value = command(context)
        // Non-null from here on: seeded above, and setCommand only ever writes a String.
        @Suppress("UNCHECKED_CAST")
        return _command as StateFlow<String>
    }

    fun setCommand(context: Context, value: String) {
        Prefs.of(context).edit().putString(Prefs.KEY_AGENT_COMMAND, value.trim()).apply()
        // Published as typed rather than trimmed: the settings field edits this flow
        // directly now, and trimming each keystroke would eat the space in a two-word
        // command. What the next process reads is still the trimmed form.
        _command.value = value
    }
}
