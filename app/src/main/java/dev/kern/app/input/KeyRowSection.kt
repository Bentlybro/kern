package dev.kern.app.input

import dev.kern.app.ui.SectionTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Edit the key row: tap a key in the row to select it, then move it or take it out; tap
 * anything in "add" to append it. Macros type their text, so a command run twenty times a
 * day becomes one key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyRowSection() {
    val context = LocalContext.current
    val keys by KeyLayout.keys(context).collectAsStateWithLifecycle()
    val row = keys ?: KeyLayout.DEFAULT
    var selected by remember { mutableStateOf<Int?>(null) }
    var macroText by remember { mutableStateOf("") }

    fun save(next: List<String>) = KeyLayout.set(context, next)

    Text(
        "Tap a key to move or remove it. Macros type their text; write \\n for Enter.",
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEachIndexed { i, id ->
            InputChip(
                selected = selected == i,
                onClick = { selected = if (selected == i) null else i },
                label = { Text(KeyLayout.label(id), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            )
        }
    }
    selected?.takeIf { it in row.indices }?.let { i ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = i > 0, onClick = {
                save(row.toMutableList().apply { add(i - 1, removeAt(i)) })
                selected = i - 1
            }) { Text("◀ left") }
            TextButton(enabled = i < row.lastIndex, onClick = {
                save(row.toMutableList().apply { add(i + 1, removeAt(i)) })
                selected = i + 1
            }) { Text("right ▶") }
            TextButton(onClick = {
                save(row.toMutableList().apply { removeAt(i) })
                selected = null
            }) { Text("remove", color = MaterialTheme.colorScheme.error) }
        }
    }

    SectionTitle("add")
    val missing = KeyLayout.BUILT_INS.map { it.first }.filter { it !in row } +
        KeyLayout.SYMBOLS.map { it.toString() }.filter { it !in row }
    if (missing.isEmpty()) {
        Text("Every built-in key is already in the row.", fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            missing.forEach { id ->
                AssistChip(
                    onClick = { save(withAdded(row, id)) },
                    label = { Text(KeyLayout.label(id), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = macroText,
            onValueChange = { macroText = it },
            singleLine = true,
            label = { Text("macro, e.g. git status\\n", fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        Button(enabled = macroText.isNotBlank(), onClick = {
            save(withAdded(row, KeyLayout.macro(macroText)))
            macroText = ""
        }) { Text("Add") }
    }
    TextButton(onClick = { KeyLayout.reset(context); selected = null }) { Text("Reset to default") }
}


/**
 * [id] placed just before the run of single-character symbols, not after it. Appended, a
 * new key or macro landed behind thirty-odd symbols at the far end of a scrolling row -
 * added, and then effectively out of reach (caught on device). A symbol joins the end.
 */
internal fun withAdded(row: List<String>, id: String): List<String> {
    if (id.length == 1) return row + id
    val at = row.indexOfFirst { it.length == 1 }.takeIf { it >= 0 } ?: row.size
    return row.toMutableList().apply { add(at, id) }
}
