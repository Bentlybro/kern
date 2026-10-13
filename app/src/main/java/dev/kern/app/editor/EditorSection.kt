package dev.kern.app.editor

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.core.Prefs

/** The native editor's own preferences, applied live to the open editor. */
object EditorPrefs {
    const val DEFAULT_SP = 12f
    const val MIN_SP = 9f
    const val MAX_SP = 22f

    fun fontSp(context: Context): Float =
        Prefs.of(context).getFloat(Prefs.KEY_EDITOR_FONT_SP, DEFAULT_SP).coerceIn(MIN_SP, MAX_SP)

    fun setFontSp(context: Context, sp: Float) {
        Prefs.of(context).edit().putFloat(Prefs.KEY_EDITOR_FONT_SP, sp).apply()
        NativeEditor.setTextSize(sp)
    }

    fun wrap(context: Context): Boolean = Prefs.of(context).getBoolean(Prefs.KEY_EDITOR_WRAP, true)

    fun setWrap(context: Context, on: Boolean) {
        Prefs.of(context).edit().putBoolean(Prefs.KEY_EDITOR_WRAP, on).apply()
        NativeEditor.current()?.isWordwrap = on
    }
}

/** Settings > Editor: which editor, and how the native one draws text. */
@Composable
fun EditorSection() {
    val context = LocalContext.current
    val mode by EditorMode.flow(context).collectAsStateWithLifecycle()
    var size by remember { mutableFloatStateOf(EditorPrefs.fontSp(context)) }
    var wrap by remember { mutableStateOf(EditorPrefs.wrap(context)) }

    Choice(
        selected = mode == EditorMode.Kind.Native,
        title = "Kern editor",
        detail = "Native and instant. Opens files straight from Linux, with Android selection and keyboard.",
    ) { EditorMode.set(context, EditorMode.Kind.Native) }
    Choice(
        selected = mode == EditorMode.Kind.Workbench,
        title = "VS Code",
        detail = "The full workbench: language servers, extensions, debugger. Takes a few seconds to start.",
    ) {
        NativeEditor.saveAll()
        EditorMode.set(context, EditorMode.Kind.Workbench)
    }

    if (mode == EditorMode.Kind.Native) {
        Text("Text size  ${size.toInt()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Slider(
            value = size,
            onValueChange = { size = it },
            onValueChangeFinished = { EditorPrefs.setFontSp(context, size) },
            valueRange = EditorPrefs.MIN_SP..EditorPrefs.MAX_SP,
            steps = (EditorPrefs.MAX_SP - EditorPrefs.MIN_SP).toInt() - 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wrap long lines", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = wrap, onCheckedChange = {
                wrap = it
                EditorPrefs.setWrap(context, it)
            })
        }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, detail: String, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
