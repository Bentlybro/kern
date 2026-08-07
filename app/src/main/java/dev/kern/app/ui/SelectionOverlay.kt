package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Native selection handles over the workbench (M4 part 2).
 *
 * Monaco renders a selection but gives touch users no way to adjust it — the request has
 * sat in Microsoft's backlog since 2019 and the one community PR was closed unreviewed.
 * Since the host app owns the window, we draw Android-style handles at the selection's
 * rendered bounds (reported by the injected bridge) and replay drags as mouse gestures,
 * which Monaco has always handled correctly.
 */
@Composable
fun SelectionOverlay(modifier: Modifier = Modifier) {
    var selection by remember { mutableStateOf<WorkbenchBridge.SelectionInfo?>(null) }
    var dragStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var dragNow by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val density = LocalDensity.current

    DisposableEffect(Unit) {
        WorkbenchWebView.selectionListener = { selection = it }
        onDispose { WorkbenchWebView.selectionListener = null }
    }

    val sel = selection
    Box(modifier = modifier.fillMaxSize()) {
        if (sel != null) {
            // Handles sit just below each end of the selection, Android-style.
            Handle(
                xCss = sel.startX,
                yCss = sel.startBottom,
                density = density.density,
                onDrag = { dx, dy ->
                    val from = dragStart ?: (sel.startX to sel.startBottom).also { dragStart = it }
                    val cur = dragNow ?: from
                    dragNow = (cur.first + dx / density.density) to (cur.second + dy / density.density)
                },
                onEnd = {
                    dragNow?.let { WorkbenchWebView.dragSelect(it.first, it.second, sel.endX, sel.endY + 1) }
                    dragStart = null
                    dragNow = null
                },
            )
            Handle(
                xCss = sel.endX,
                yCss = sel.endBottom,
                density = density.density,
                onDrag = { dx, dy ->
                    val from = dragStart ?: (sel.endX to sel.endBottom).also { dragStart = it }
                    val cur = dragNow ?: from
                    dragNow = (cur.first + dx / density.density) to (cur.second + dy / density.density)
                },
                onEnd = {
                    dragNow?.let { WorkbenchWebView.dragSelect(sel.startX, sel.startY + 1, it.first, it.second) }
                    dragStart = null
                    dragNow = null
                },
            )

            // Action bar: the operations touch users actually want on a selection.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Action("copy") { WorkbenchWebView.copySelection() }
                Action("paste") { WorkbenchWebView.pasteClipboard() }
                Action("expand") { WorkbenchWebView.expandSelection() }
                Action("all") { WorkbenchWebView.selectAll() }
            }
        }
    }
}

@Composable
private fun Handle(
    xCss: Float,
    yCss: Float,
    density: Float,
    onDrag: (Float, Float) -> Unit,
    onEnd: () -> Unit,
) {
    val sizeDp = 22.dp
    Box(
        modifier = Modifier
            .offset(
                x = (xCss.dp) - sizeDp / 2,
                y = (yCss.dp),
            )
            .size(sizeDp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(xCss, yCss) {
                detectDragGestures(
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            },
    )
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
