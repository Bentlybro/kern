package dev.kern.app.ui

import dev.kern.app.agent.AgentRepository
import dev.kern.app.editor.EditorMode
import dev.kern.app.editor.NativeEditor
import dev.kern.app.input.InputTargets
import dev.kern.app.session.SessionState
import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.workbench.WorkbenchWebView
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
    var confirmQuit by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val agentCommand by AgentRepository.commandFlow(context).collectAsStateWithLifecycle()
    val editorMode by EditorMode.flow(context).collectAsStateWithLifecycle()
    val native = editorMode == EditorMode.Kind.Native


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

        // Only what gets tapped repeatedly WHILE editing. The bar had grown to eight chips
        // by accretion, one per feature, which is how a toolbar stops being scannable: past
        // about four the eye reads it as a wall and stops picking anything out. The test
        // applied here is frequency during a session, not importance in the abstract -
        // opening a project happens once, so it moved to the menu, while toggling the file
        // tree happens constantly, so it stayed. Everything dropped is still one tap away
        // behind "more".
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip("files") { InputTargets.toggleFiles(context) }
            // The native editor has no source-control view of its own; the cockpit's diff
            // and commit is the same job, already native.
            ActionChip("git") {
                if (native) onNavigate(ShellDestination.Cockpit) else WorkbenchWebView.Commands.toggleSourceControl()
            }
            if (mode != DisplayMode.Tabletop) {
                ActionChip(
                    label = if (terminalShown) "editor" else "term",
                    highlighted = terminalShown,
                    onClick = onToggleTerminal,
                )
            }
            // Undo keeps a chip while redo does not, and the asymmetry is deliberate. It is
            // the most frequent action in any editor, it is what you reach for the instant
            // the soft keyboard autocorrects an identifier, and without it there is no way
            // to send Ctrl+Z at all. Redo is comparatively rare, so it went to the menu.
            ActionChip("undo") { InputTargets.undo(context) }
            // Only when there is an agent to open. Someone who never wanted one should
            // not be carrying a permanent chip for it, and the cockpit's diff and commit
            // half is still reachable through "more".
            if (agentCommand.isNotBlank()) {
                ActionChip("agent") { onNavigate(ShellDestination.Cockpit) }
            }
        }

        Spacer(Modifier.width(6.dp))

        Box {
            ActionChip("more") { menuOpen = true }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                // Ordered by how often it is reached for, not by category. Redo sits at the
                // top because someone who has just used the undo chip and overshot is the
                // likeliest person to open this menu at all.
                MenuAction("redo", { menuOpen = false }) { InputTargets.redo(context) }
                MenuAction("find", { menuOpen = false }) { InputTargets.find(context) }
                if (!native) {
                    MenuAction("go to file", { menuOpen = false }) {
                        WorkbenchWebView.Commands.quickOpen()
                    }
                    MenuAction("find in files", { menuOpen = false }) {
                        WorkbenchWebView.Commands.toggleSearch()
                    }
                    // The command palette earns its place here rather than being dropped:
                    // it is the way into every VS Code command that has no chip.
                    MenuAction("command palette", { menuOpen = false }) {
                        WorkbenchWebView.Commands.commandPalette()
                    }
                }
                // The comment on the agent chip promised this and nothing delivered it:
                // with no agent set, the diff-and-commit cockpit had no way in at all.
                MenuAction("diff & commit", { menuOpen = false }) {
                    onNavigate(ShellDestination.Cockpit)
                }
                MenuAction("projects", { menuOpen = false }) {
                    onNavigate(ShellDestination.Projects)
                }
                MenuAction("new terminal", { menuOpen = false }) {
                    TerminalSessions.openShell(context)
                    onNewShell()
                }
                if (!native) {
                    MenuAction("chat", { menuOpen = false }) {
                        WorkbenchWebView.Commands.toggleChatPanel()
                    }
                }
                // Both editors work on the same files, so switching is free at any moment:
                // VS Code for language servers and extensions, native for speed and touch.
                MenuAction(if (native) "open VS Code" else "native editor", { menuOpen = false }) {
                    if (native) NativeEditor.saveAll()
                    EditorMode.set(context, if (native) EditorMode.Kind.Workbench else EditorMode.Kind.Native)
                }
                MenuAction("status", { menuOpen = false }) {
                    onNavigate(ShellDestination.Status)
                }
                MenuAction("settings", { menuOpen = false }) {
                    onNavigate(ShellDestination.Settings)
                }
                MenuAction("quit", { menuOpen = false }) { confirmQuit = true }
            }
        }
    }

    // One stray tap in the overflow used to end every shell and whatever was building in
    // them. tmux does not survive this - it is a child of the guest that quit stops.
    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("Quit Kern?") },
            text = {
                Text(
                    "This stops the Linux environment. Anything running in a terminal " +
                        "stops with it. Saved files are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmQuit = false; onQuit() }) { Text("Quit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmQuit = false }) { Text("Cancel") }
            },
        )
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
