package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The terminal surface: Termux's real emulator and view over a local pty, with a tab
 * strip once there is more than one shell.
 *
 * The strip hides itself while a single shell is open. One tab is not a choice, and a row
 * of chrome that never changes is chrome that stops being read.
 */
@Composable
fun TerminalPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessions by TerminalSessions.sessions.collectAsStateWithLifecycle()
    val activeId by TerminalSessions.activeId.collectAsStateWithLifecycle()

    val shells = sessions.filter { it.kind == TerminalSessions.Kind.Shell }
    val active = shells.firstOrNull { it.id == activeId }
        ?: shells.firstOrNull()
        ?: TerminalSessions.activeShell(context)

    Column(modifier = modifier) {
        if (shells.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shells.forEach { shell ->
                    Tab(
                        title = shell.title,
                        selected = shell.id == active.id,
                        onSelect = { TerminalSessions.select(shell.id) },
                        onClose = { TerminalSessions.closeShell(shell.id) },
                    )
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // key() on the session id, so switching tabs replaces the whole AndroidView
            // rather than trying to rebind a live pty to a different emulator. Without
            // it Compose reuses the node and every tab shows the first shell.
            key(active.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        TerminalSessions.detachAll()
                        active.view
                    },
                )
            }
        }
    }
}

/** A new shell. Lives in the top bar's overflow, next to the terminal toggle. */
@Composable
fun rememberNewShell(): () -> Unit {
    val context = LocalContext.current
    return { TerminalSessions.openShell(context) }
}

@Composable
private fun Tab(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "x",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClose)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
