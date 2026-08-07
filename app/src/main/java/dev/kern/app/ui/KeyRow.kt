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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }

    // Mirror into shared state so the terminal's client can read sticky modifiers.
    KeyRowState.ctrl = ctrl
    KeyRowState.alt = alt
    KeyRowState.shift = shift

    fun meta(): Int =
        (if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    fun clearModifiers() {
        ctrl = false
        alt = false
        shift = false
        KeyRowState.clear()
    }

    /** Route to whichever surface has focus — terminal or workbench. */
    fun key(code: Int) {
        if (TerminalSessions.hasFocus()) {
            TerminalSessions.sendKey(code, meta())
        } else {
            WorkbenchWebView.sendKey(code, meta())
        }
        clearModifiers()
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
        clearModifiers()
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            // Explicit IME control: the guaranteed way in, when Monaco will not ask.
            Key("⌨") {
                if (TerminalSessions.hasFocus()) TerminalSessions.toggleKeyboard()
                else WorkbenchWebView.toggleKeyboard()
            }
            Key("esc") { key(KeyEvent.KEYCODE_ESCAPE) }
            Key("tab") { key(KeyEvent.KEYCODE_TAB) }
            Sticky("ctrl", ctrl) { ctrl = !ctrl }
            Sticky("alt", alt) { alt = !alt }
            Sticky("shift", shift) { shift = !shift }
            Key("←") { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            Key("↓") { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            Key("↑") { key(KeyEvent.KEYCODE_DPAD_UP) }
            Key("→") { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Key("home") { key(KeyEvent.KEYCODE_MOVE_HOME) }
            Key("end") { key(KeyEvent.KEYCODE_MOVE_END) }
            Key("pgup") { key(KeyEvent.KEYCODE_PAGE_UP) }
            Key("pgdn") { key(KeyEvent.KEYCODE_PAGE_DOWN) }
            Key("^C") {
                ctrl = true
                key(KeyEvent.KEYCODE_C)
            }
            Key("^D") {
                ctrl = true
                key(KeyEvent.KEYCODE_D)
            }
            Key("save") { WorkbenchWebView.Commands.save(); clearModifiers() }
            Key("find") { WorkbenchWebView.Commands.find(); clearModifiers() }

            "|/\\-_=+;:'\"`{}[]()<>$#%&*!?~^@".forEach { c -> Key(c.toString()) { char(c) } }
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
