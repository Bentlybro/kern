package dev.kern.app.input

import dev.kern.app.core.Prefs
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which keys the key row shows, in what order - the user's, not ours.
 *
 * A key is stored by id. Built-in ids name a fixed action ([BUILT_INS]); one character is
 * that character; `m:` followed by text is a macro that types the text, with `\n` meaning
 * Enter, so `git status\n` is one tap. Unknown ids are dropped on load rather than
 * crashing a row built by a newer version.
 */
object KeyLayout {

    /** The fixed keys, by id, with the label they show. Order here is the picker's order. */
    val BUILT_INS: List<Pair<String, String>> = listOf(
        "kbd" to "⌨", "esc" to "esc", "tab" to "tab",
        "ctrl" to "ctrl", "alt" to "alt", "shift" to "shift",
        "left" to "←", "down" to "↓", "up" to "↑", "right" to "→",
        "home" to "home", "end" to "end", "pgup" to "pgup", "pgdn" to "pgdn",
        "ctrl-c" to "^C", "ctrl-d" to "^D", "ctrl-z" to "^Z", "ctrl-l" to "^L",
        "save" to "save", "find" to "find",
    )

    const val SYMBOLS = "|/\\-_=+;:'\"`{}[]()<>$#%&*!?~^@"

    /** What Kern shipped with before the row could be changed. */
    val DEFAULT: List<String> = listOf(
        "kbd", "esc", "tab", "ctrl", "alt", "shift", "left", "down", "up", "right",
        "home", "end", "pgup", "pgdn", "ctrl-c", "ctrl-d", "save", "find",
    ) + SYMBOLS.map { it.toString() }

    private const val SEP = "\u001F"

    private val _keys = MutableStateFlow<List<String>?>(null)

    fun keys(context: Context): StateFlow<List<String>?> {
        if (_keys.value == null) _keys.value = load(context)
        return _keys.asStateFlow()
    }

    fun set(context: Context, keys: List<String>) {
        val clean = keys.filter(::isValid)
        Prefs.of(context).edit().putString(Prefs.KEY_KEY_ROW, clean.joinToString(SEP)).apply()
        _keys.value = clean
    }

    fun reset(context: Context) {
        Prefs.of(context).edit().remove(Prefs.KEY_KEY_ROW).apply()
        _keys.value = DEFAULT
    }

    private fun load(context: Context): List<String> =
        Prefs.of(context).getString(Prefs.KEY_KEY_ROW, null)
            ?.split(SEP)
            ?.filter(::isValid)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT

    fun isValid(id: String): Boolean =
        BUILT_INS.any { it.first == id } || id.length == 1 || (id.startsWith("m:") && id.length > 2)

    fun label(id: String): String = when {
        id.startsWith("m:") -> id.removePrefix("m:").replace("\n", "⏎").let {
            if (it.length > 12) it.take(11) + "…" else it
        }
        else -> BUILT_INS.firstOrNull { it.first == id }?.second ?: id
    }

    /** A macro id from what was typed in settings, where `\n` is written literally. */
    fun macro(text: String): String = "m:" + text.replace("\\n", "\n")
}
