package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.terminal.TerminalPane
import dev.kern.app.terminal.TerminalSessions

/**
 * Editor and terminal together, arranged for the screen's shape.
 *
 * Wide and short - a phone in landscape, an unfolded inner screen held sideways, a tablet
 * - puts them side by side: stacked there, each got about nine lines. Tall puts the
 * terminal under the editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorTerminalSplit() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 640.dp && maxWidth > maxHeight * 1.3f
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                EditorPane(Modifier.weight(0.56f).fillMaxHeight())
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                TerminalPane(Modifier.weight(0.44f).fillMaxHeight())
            }
            return@BoxWithConstraints
        }
        // The pane the keyboard is typing into is the pane that needs the room. A fixed
        // 60/40 split shares the keyboard's cost between both panes, which left the
        // terminal ~10 rows tall on the unfolded screen - under thumbs that were covering
        // the editor anyway. So while the IME is up and a terminal holds focus, the editor
        // collapses to a sliver and the terminal takes the rest; either putting the
        // keyboard away or tapping the editor restores the split. The editor stays in
        // composition on purpose: removing it would detach the WebView and run the
        // layout's teardown.
        val terminalFocused by TerminalSessions.focused.collectAsStateWithLifecycle()
        val imeUp = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
        // Snapped, not animated: each step of a weight animation re-lays out the
        // workbench, which is the struggle imeAnimationTarget exists to avoid.
        val editorWeight = if (imeUp && terminalFocused) 0.12f else 0.6f
        Column(Modifier.fillMaxSize()) {
            EditorPane(Modifier.fillMaxWidth().weight(editorWeight))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            TerminalPane(Modifier.fillMaxWidth().weight(1f - editorWeight))
        }
    }
}

/** Tabletop: content on the upright half, controls on the flat half (docs/05). */
@Composable
internal fun TabletopLayout(fold: FoldState) {
    val density = LocalDensity.current
    val hingeDp = fold.hingeBounds?.let { with(density) { (it.bottom - it.top).toDp() } } ?: 0.dp

    Column(modifier = Modifier.fillMaxSize()) {
        EditorPane(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        // Nothing interactive may sit on the crease.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (hingeDp > 0.dp) hingeDp else 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        // Flat half: the terminal, reachable without reaching across the crease.
        TerminalPane(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

