package dev.kern.app.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The coding key row. Keys are injected as REAL platform key events (see
 * [WorkbenchWebView.sendKey]) so the workbench receives them exactly as hardware keys.
 *
 * M4 turns this into the full input layer (configurable layouts, selection handles,
 * magnifier). It is docked above the IME by the shell's `imePadding()`.
 */
@Composable
fun KeyRow() {
    /** Route to whichever surface has focus — terminal or workbench. */
    fun key(code: Int) {
        if (TerminalSessions.hasFocus()) {
            TerminalSessions.sendKey(code, ModifierKeys.meta())
        } else {
            WorkbenchWebView.sendKey(code, ModifierKeys.meta())
        }
        ModifierKeys.clear()
    }

    fun char(c: Char) {
        if (TerminalSessions.hasFocus()) {
            TerminalSessions.write(c.toString())
        } else {
            val wv = WorkbenchWebView.current() ?: return
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(charArrayOf(c))
                ?.forEach { wv.dispatchKeyEvent(it) }
        }
        ModifierKeys.clear()
    }

    fun ctrlKey(code: Int) {
        ModifierKeys.ctrl = true
        key(code)
    }

    /** A macro types its text; a newline in it is Enter, sent as a key so both surfaces see one. */
    fun macro(text: String) {
        text.split('\n').forEachIndexed { i, part ->
            if (i > 0) key(KeyEvent.KEYCODE_ENTER)
            if (part.isNotEmpty()) {
                if (TerminalSessions.hasFocus()) TerminalSessions.write(part) else part.forEach(::char)
            }
        }
        ModifierKeys.clear()
    }

    val context = LocalContext.current
    val layout by KeyLayout.keys(context).collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            (layout ?: KeyLayout.DEFAULT).forEach { id ->
                val label = KeyLayout.label(id)
                when (id) {
                    // Explicit IME control: the guaranteed way in, when Monaco will not ask.
                    "kbd" -> Key(label) { focusedInputView()?.toggleIme() }
                    "esc" -> Key(label) { key(KeyEvent.KEYCODE_ESCAPE) }
                    "tab" -> Key(label) { key(KeyEvent.KEYCODE_TAB) }
                    "ctrl" -> Sticky(label, ModifierKeys.ctrl) { ModifierKeys.ctrl = !ModifierKeys.ctrl }
                    "alt" -> Sticky(label, ModifierKeys.alt) { ModifierKeys.alt = !ModifierKeys.alt }
                    "shift" -> Sticky(label, ModifierKeys.shift) { ModifierKeys.shift = !ModifierKeys.shift }
                    "left" -> Key(label) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                    "down" -> Key(label) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                    "up" -> Key(label) { key(KeyEvent.KEYCODE_DPAD_UP) }
                    "right" -> Key(label) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    "home" -> Key(label) { key(KeyEvent.KEYCODE_MOVE_HOME) }
                    "end" -> Key(label) { key(KeyEvent.KEYCODE_MOVE_END) }
                    "pgup" -> Key(label) { key(KeyEvent.KEYCODE_PAGE_UP) }
                    "pgdn" -> Key(label) { key(KeyEvent.KEYCODE_PAGE_DOWN) }
                    "ctrl-c" -> Key(label) { ctrlKey(KeyEvent.KEYCODE_C) }
                    "ctrl-d" -> Key(label) { ctrlKey(KeyEvent.KEYCODE_D) }
                    "ctrl-z" -> Key(label) { ctrlKey(KeyEvent.KEYCODE_Z) }
                    "ctrl-l" -> Key(label) { ctrlKey(KeyEvent.KEYCODE_L) }
                    "save" -> Key(label) { WorkbenchWebView.Commands.save(); ModifierKeys.clear() }
                    "find" -> Key(label) { WorkbenchWebView.Commands.find(); ModifierKeys.clear() }
                    else -> when {
                        id.startsWith("m:") -> Key(label) { macro(id.removePrefix("m:")) }
                        id.length == 1 -> Key(label) { char(id[0]) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, onTap: () -> Unit) {
    TextButton(
        onClick = onTap,
        modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Sticky(label: String, active: Boolean, onTap: () -> Unit) {
    TextButton(
        onClick = onTap,
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Shared modifier state so the key row can drive either the workbench or a terminal.
 *
 * Snapshot state, so [KeyRow] recomposes off the same fields the terminal's client reads —
 * there is no second copy to keep in step.
 */
object ModifierKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)

    fun meta(): Int =
        (if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    /**
     * Read a modifier for the keystroke in flight and drop it.
     *
     * A sticky modifier that outlives its keystroke is not sticky, it is stuck: leave ctrl
     * on, type on the soft keyboard, and every character arrives as a control code. The key
     * row was the only thing that ever cleared it, so anything typed on Gboard - or
     * anything at all in the cockpit, which has no key row and no lit chip to explain
     * itself - carried the modifier forever.
     */
    fun consumeCtrl(): Boolean = ctrl.also { ctrl = false }

    fun consumeAlt(): Boolean = alt.also { alt = false }

    fun consumeShift(): Boolean = shift.also { shift = false }

    fun clear() {
        ctrl = false
        alt = false
        shift = false
    }
}
