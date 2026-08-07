package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.kern.app.runtime.AgentRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.session.SessionState

@Composable
internal fun TopBar(
    state: SessionState,
    mode: DisplayMode,
    terminalShown: Boolean,
    onToggleTerminal: () -> Unit,
    onNavigate: (ShellDestination) -> Unit,
    onNewShell: () -> Unit,
    onQuit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val agentCommand by AgentRepository.commandFlow(context).collectAsStateWithLifecycle()


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = when (state) {
            is SessionState.Healthy -> KernColors.Ok
            is SessionState.Reconnecting -> KernColors.Warn
            else -> MaterialTheme.colorScheme.secondary
        }
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(8.dp))

        // The surfaces worth a permanent thumb target, plus undo and redo. Everything
        // else lives behind "more" - reachable, but not competing for the bar. Still
        // scrollable so the cover display cannot wrap the posture label.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Undo leads because the row scrolls: on the cover display only the first few
            // chips are on screen, and this is the one you reach for the instant Gboard
            // autocorrects an identifier. It is also the only way to send Ctrl+Z at all.
            ActionChip("undo") { WorkbenchWebView.Commands.undo() }
            ActionChip("redo") { WorkbenchWebView.Commands.redo() }
            ActionChip("files") { WorkbenchWebView.Commands.toggleSidebar() }
            ActionChip("search") { WorkbenchWebView.Commands.toggleSearch() }
            ActionChip("git") { WorkbenchWebView.Commands.toggleSourceControl() }
            ActionChip("project") { onNavigate(ShellDestination.Projects) }
            if (mode != DisplayMode.Tabletop) {
                ActionChip(
                    label = if (terminalShown) "editor" else "term",
                    highlighted = terminalShown,
                    onClick = onToggleTerminal,
                )
            }
            // Only when there is an agent to open. Someone who never wanted one should
            // not be carrying a permanent chip for it, and the cockpit's diff and commit
            // half is still reachable through "more".
            if (agentCommand.isNotBlank()) {
                ActionChip("agent") { onNavigate(ShellDestination.Cockpit) }
            }
        }

        Spacer(Modifier.width(6.dp))
        Text(
            mode.name.lowercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            ActionChip("more") { menuOpen = true }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                // The command palette earns its place here rather than being dropped:
                // it is the way into every VS Code command that has no chip.
                MenuAction("go to file", { menuOpen = false }) {
                    WorkbenchWebView.Commands.quickOpen()
                }
                MenuAction("command palette", { menuOpen = false }) {
                    WorkbenchWebView.Commands.commandPalette()
                }
                MenuAction("chat", { menuOpen = false }) {
                    WorkbenchWebView.Commands.toggleChatPanel()
                }
                MenuAction("new terminal", { menuOpen = false }) {
                    TerminalSessions.openShell(context)
                    onNewShell()
                }
                MenuAction("status", { menuOpen = false }) {
                    onNavigate(ShellDestination.Status)
                }
                MenuAction("settings", { menuOpen = false }) {
                    onNavigate(ShellDestination.Settings)
                }
                MenuAction("quit", { menuOpen = false }, onQuit)
            }
        }
    }
}

@Composable
private fun MenuAction(label: String, dismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = {
            dismiss()
            onClick()
        },
    )
}

@Composable
internal fun ActionChip(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
