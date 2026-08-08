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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * strip once there is more than one shell or a closed one waiting to be taken back.
 *
 * The strip hides itself while a single shell is open and nothing is detached. One tab is
 * not a choice, and a row of chrome that never changes is chrome that stops being read.
 */
@Composable
fun TerminalPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessions by TerminalSessions.sessions.collectAsStateWithLifecycle()
    val activeId by TerminalSessions.activeId.collectAsStateWithLifecycle()
    val detached by TerminalSessions.detached.collectAsStateWithLifecycle()
    val stuck by TerminalSessions.shellStuck.collectAsStateWithLifecycle()

    val shells = sessions.filter { it.kind == TerminalSessions.Kind.Shell }
    val active = shells.firstOrNull { it.id == activeId } ?: shells.firstOrNull()

    // Asking for the shell from composition is how this pane took part in the respawn
    // loop: every frame without one asked for another. The live values rather than the
    // collected ones, because the two flows do not necessarily land in the same frame.
    LaunchedEffect(active?.id, stuck) {
        if (TerminalSessions.shells().isEmpty() && !TerminalSessions.shellStuck.value) {
            TerminalSessions.openShell(context)
        }
    }

    Column(modifier = modifier) {
        if (shells.size > 1 || detached.isNotEmpty()) {
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
                        selected = shell.id == active?.id,
                        closable = shells.size > 1,
                        onSelect = { TerminalSessions.select(shell.id) },
                        onClose = { TerminalSessions.closeShell(shell.id) },
                    )
                }
                detached.forEach { slot ->
                    DetachedTab(slot) { TerminalSessions.reattach(context, slot) }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                active != null -> TerminalSurface(active, Modifier.fillMaxSize())
                stuck -> ShellStuck { TerminalSessions.retryShell(context) }
            }
        }
    }
}

/**
 * What a shell that will not stay up looks like, in place of another spawn.
 *
 * Usually the guest was killed to free memory, which on a phone is routine rather than
 * exceptional, and asking again a moment later works.
 */
@Composable
private fun ShellStuck(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "The shell keeps exiting.",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "It closed twice within a few seconds, so Kern stopped reopening it. Android " +
                "may have killed the Linux guest to free memory.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text("Try again", fontSize = 13.sp) }
    }
}

/**
 * Host a session's live emulator. Detaches every terminal before reparenting, and keys on
 * the session id — without the key Compose reuses the node and rebinds a live pty to the
 * wrong emulator, so every tab shows the first shell. The same key is what gets a restarted
 * agent a fresh view rather than the old one rebound to a dead pty.
 */
@Composable
internal fun TerminalSurface(entry: TerminalSessions.Entry, modifier: Modifier = Modifier) {
    key(entry.id) {
        AndroidView(
            modifier = modifier,
            factory = {
                TerminalSessions.detachAll()
                entry.view
            },
        )
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
    closable: Boolean,
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
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = if (closable) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (closable) {
            // A whole 48dp for the glyph. Drawn it is a few dp wide, it sits against the
            // tab you were aiming for, and one thumb-width of overshoot closed a shell.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "x",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A shell whose tab was closed, still running on the tmux server.
 *
 * Without this the strip is the only record that it existed, so closing a tab hid a build
 * that kept going with nothing left in the UI that could reach it.
 */
@Composable
private fun DetachedTab(slot: Int, onReattach: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onReattach)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "sh $slot",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "reopen",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
