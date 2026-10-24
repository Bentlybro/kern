package dev.kern.app.input

import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The coding key row. Keys are injected as REAL platform key events, routed by
 * [InputTargets] to whichever surface holds the keyboard, so each receives them exactly as
 * it would hardware keys.
 *
 * M4 turns this into the full input layer (configurable layouts, selection handles,
 * magnifier). It is docked above the IME by the shell's `imePadding()`.
 */
@Composable
fun KeyRow() {
    val context = LocalContext.current

    fun key(code: Int) {
        InputTargets.sendKey(context, code, ModifierKeys.meta())
        ModifierKeys.clear()
    }

    fun char(c: Char) {
        InputTargets.type(context, c.toString())
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
                InputTargets.type(context, part)
            }
        }
        ModifierKeys.clear()
    }

    val layout by KeyLayout.keys(context).collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            (layout ?: KeyLayout.DEFAULT).forEach { id ->
                val label = KeyLayout.label(id)
                val spoken = spokenName(id)
                when (id) {
                    // Explicit IME control: the guaranteed way in, when Monaco will not ask.
                    "kbd" -> Key(label, spoken) { focusedInputView(context)?.toggleIme() }
                    "esc" -> Key(label, spoken) { key(KeyEvent.KEYCODE_ESCAPE) }
                    "tab" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_TAB) }
                    "ctrl" -> Sticky(label, spoken, ModifierKeys.ctrl) { ModifierKeys.ctrl = !ModifierKeys.ctrl }
                    "alt" -> Sticky(label, spoken, ModifierKeys.alt) { ModifierKeys.alt = !ModifierKeys.alt }
                    "shift" -> Sticky(label, spoken, ModifierKeys.shift) { ModifierKeys.shift = !ModifierKeys.shift }
                    "left" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                    "down" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                    "up" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_DPAD_UP) }
                    "right" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    "home" -> Key(label, spoken) { key(KeyEvent.KEYCODE_MOVE_HOME) }
                    "end" -> Key(label, spoken) { key(KeyEvent.KEYCODE_MOVE_END) }
                    "pgup" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_PAGE_UP) }
                    "pgdn" -> Key(label, spoken, repeats = true) { key(KeyEvent.KEYCODE_PAGE_DOWN) }
                    "ctrl-c" -> Key(label, spoken) { ctrlKey(KeyEvent.KEYCODE_C) }
                    "ctrl-d" -> Key(label, spoken) { ctrlKey(KeyEvent.KEYCODE_D) }
                    "ctrl-z" -> Key(label, spoken) { ctrlKey(KeyEvent.KEYCODE_Z) }
                    "ctrl-l" -> Key(label, spoken) { ctrlKey(KeyEvent.KEYCODE_L) }
                    "save" -> Key(label, spoken) { InputTargets.save(context); ModifierKeys.clear() }
                    "find" -> Key(label, spoken) { InputTargets.find(context); ModifierKeys.clear() }
                    else -> when {
                        id.startsWith("m:") -> Key(label, spoken) { macro(id.removePrefix("m:")) }
                        id.length == 1 -> Key(label, spoken) { char(id[0]) }
                    }
                }
            }
        }
    }
}

/**
 * What a screen reader says for a key whose label is a glyph or an abbreviation - "←" is
 * read as "leftwards arrow" and "^C" as "caret C". Null where the label already says it.
 */
private fun spokenName(id: String): String? = when (id) {
    "kbd" -> "Show or hide keyboard"
    "esc" -> "Escape"
    "left" -> "Left arrow"
    "down" -> "Down arrow"
    "up" -> "Up arrow"
    "right" -> "Right arrow"
    "pgup" -> "Page up"
    "pgdn" -> "Page down"
    "ctrl" -> "Control"
    "ctrl-c" -> "Control C"
    "ctrl-d" -> "Control D"
    "ctrl-z" -> "Control Z"
    "ctrl-l" -> "Control L"
    else -> null
}

@Composable
private fun Key(label: String, spoken: String?, repeats: Boolean = false, onTap: () -> Unit) =
    KeyCap(label, spoken, active = null, repeats = repeats, onTap = onTap)

@Composable
private fun Sticky(label: String, spoken: String?, active: Boolean, onTap: () -> Unit) =
    KeyCap(label, spoken, active, repeats = false, onTap = onTap)

/** Held this long, a repeating key starts repeating, as a hardware key does. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

/**
 * One key: a rounded cap a step above the bar, so each reads as something to press rather
 * than as a word in a sentence. A sticky modifier that is on takes the accent.
 *
 * The touch target is the cap plus the gap around it, which takes it to 44 x 46 dp
 * without making the row any taller: the row sits between the code and the keyboard, and
 * every dp it grows comes out of the editor. The press ripple stays on the cap itself.
 *
 * [active] is null for a key that is not sticky, so only modifiers report on or off.
 */
@Composable
private fun KeyCap(
    label: String,
    spoken: String?,
    active: Boolean?,
    repeats: Boolean,
    onTap: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val lit = active == true
    val interaction = remember { MutableInteractionSource() }
    // The gesture loop outlives recompositions, so it reads the latest action through this
    // rather than holding the one it started with.
    val currentTap by rememberUpdatedState(onTap)
    val press = if (repeats) {
        Modifier
            .repeatingPress(interaction) { currentTap() }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { currentTap(); true }
            }
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = onTap,
        )
    }
    Box(
        modifier = Modifier
            .then(press)
            .semantics {
                spoken?.let { contentDescription = it }
                active?.let { stateDescription = if (it) "On" else "Off" }
            }
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .defaultMinSize(minWidth = 40.dp, minHeight = 38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (lit) colors.primaryContainer else colors.surfaceContainerHighest)
            .indication(interaction, ripple())
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (lit) colors.onPrimaryContainer else colors.onSurface,
            // The key's description replaces the glyph rather than being read after it.
            modifier = if (spoken != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/**
 * A tap sends once, on release, like a click; held past [REPEAT_DELAY_MS] the key repeats
 * until it is let go, as arrows and Tab do on a hardware keyboard. Nothing is sent on the
 * way down, so a touch that turns into scrolling the row - which takes the gesture away
 * from here - types nothing.
 */
private fun Modifier.repeatingPress(
    interaction: MutableInteractionSource,
    onTap: () -> Unit,
): Modifier = pointerInput(interaction) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown()
            val pressed = PressInteraction.Press(down.position)
            interaction.tryEmit(pressed)
            var fired = false
            val repeater = launch {
                delay(REPEAT_DELAY_MS)
                while (isActive) {
                    fired = true
                    onTap()
                    delay(REPEAT_INTERVAL_MS)
                }
            }
            val up = waitForUpOrCancellation()
            repeater.cancel()
            if (up != null) {
                up.consume()
                if (!fired) onTap()
                interaction.tryEmit(PressInteraction.Release(pressed))
            } else {
                interaction.tryEmit(PressInteraction.Cancel(pressed))
            }
        }
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
