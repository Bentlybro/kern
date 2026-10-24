package dev.kern.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.delay

/**
 * Find (and replace) in the open file, on sora's own searcher - matches are highlighted in
 * the editor and next/previous move the selection to them. Plain text, case-insensitive:
 * the case worth one thumb, with the terminal's ripgrep there for everything else.
 */
@Composable
fun FindBar(onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(-1) }

    // Debounced so each keystroke does not rescan a large file.
    LaunchedEffect(query) {
        delay(150)
        val searcher = NativeEditor.searcher() ?: return@LaunchedEffect
        if (query.isEmpty()) {
            searcher.stopSearch()
        } else {
            searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
        }
    }
    // The count from sora's own event, not read once after a guess at how long the search
    // takes: it runs on a background thread and reports 0 until done, which on a large file
    // was all the bar ever showed. sora searches again after every edit, a Replace and a tab
    // switch, and publishes each time, so the count follows those too. The selection moves
    // on next and previous, which is what "n of m" counts.
    DisposableEffect(Unit) {
        val view = NativeEditor.current()
        fun refresh() {
            val searcher = view?.searcher
            if (searcher == null || !searcher.hasQuery()) {
                count = 0
                index = -1
                return
            }
            count = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
            index = runCatching { searcher.currentMatchedPositionIndex }.getOrDefault(-1)
        }
        val results = view?.subscribeAlways(PublishSearchResultEvent::class.java) { refresh() }
        val selection = view?.subscribeAlways(SelectionChangeEvent::class.java) { refresh() }
        onDispose {
            results?.unsubscribe()
            selection?.unsubscribe()
            NativeEditor.searcher()?.stopSearch()
        }
    }

    fun next() = runCatching { NativeEditor.searcher()?.gotoNext() }
    fun previous() = runCatching { NativeEditor.searcher()?.gotoPrevious() }

    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Find", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { next() }),
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    query.isEmpty() -> ""
                    index >= 0 -> "${index + 1} of $count"
                    else -> "$count"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            TextButton(onClick = { previous() }) { Text("↑") }
            TextButton(onClick = { next() }) { Text("↓") }
            TextButton(onClick = { replacing = !replacing }) { Text(if (replacing) "−" else "⇄") }
            TextButton(onClick = onClose) { Text("✕") }
        }
        if (replacing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    singleLine = true,
                    placeholder = { Text("Replace", fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = query.isNotEmpty(),
                    onClick = { runCatching { NativeEditor.searcher()?.replaceCurrentMatch(replacement) } },
                ) { Text("One") }
                TextButton(
                    enabled = query.isNotEmpty(),
                    onClick = { runCatching { NativeEditor.searcher()?.replaceAll(replacement) } },
                ) { Text("All") }
            }
        }
    }
}
