package dev.kern.app.ui

import dev.kern.app.projects.ProjectRepository
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import dev.kern.app.agent.AgentRepository
import dev.kern.app.core.AppScope
import dev.kern.app.editor.EditorChrome
import dev.kern.app.editor.EditorMode
import dev.kern.app.workbench.WorkbenchFiles
import dev.kern.app.editor.NativeEditor
import dev.kern.app.terminal.Runner
import dev.kern.app.input.InputTargets
import dev.kern.app.session.SessionState
import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.workbench.WorkbenchWebView
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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

    val project = ProjectRepository.currentFolder(context).substringAfterLast('/').ifEmpty { "/" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = when (state) {
            is SessionState.Healthy -> KernColors.Ok
            is SessionState.Reconnecting -> KernColors.Warn
            else -> MaterialTheme.colorScheme.secondary
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(10.dp))
        // Where you are, which nothing else on screen says: the project, tappable to switch.
        Text(
            project,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNavigate(ShellDestination.Projects) }
                .padding(vertical = 8.dp),
        )

        // Only what gets tapped repeatedly WHILE editing; everything else is one tap away
        // behind the overflow. Icons, because five words across a phone's width left no
        // room for anything else and read as a sentence rather than as controls.
        // Run the file being edited, when Kern knows how. The edit-run loop is most of
        // programming on a phone, and it should be one tap.
        val activeFile by NativeEditor.active.collectAsStateWithLifecycle()
        if (native && Runner.canRun(activeFile)) {
            HeaderIcon(KernIcons.Play, "Run", {
                NativeEditor.saveAll()
                Runner.run(context, activeFile!!)
            }, tint = KernColors.Ok)
        }
        HeaderIcon(KernIcons.Folder, "Files", { InputTargets.toggleFiles(context) })
        // The native editor has no source-control view of its own; the cockpit's diff and
        // commit is the same job, already native.
        HeaderIcon(KernIcons.Branch, "Source control", {
            if (native) onNavigate(ShellDestination.Cockpit) else WorkbenchWebView.Commands.toggleSourceControl()
        })
        if (mode != DisplayMode.Tabletop) {
            HeaderIcon(
                if (terminalShown) KernIcons.Code else KernIcons.Terminal,
                if (terminalShown) "Show editor" else "Show terminal",
                onToggleTerminal,
                tint = if (terminalShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        // Undo keeps a place on the bar and redo does not: it is what you reach for the
        // instant the soft keyboard autocorrects an identifier.
        HeaderIcon(KernIcons.Undo, "Undo", { InputTargets.undo(context) })
        // Only when there is an agent to open; the diff half is in the menu regardless.
        if (agentCommand.isNotBlank()) {
            HeaderIcon(KernIcons.Agent, "Agent", { onNavigate(ShellDestination.Cockpit) })
        }

        Box {
            HeaderIcon(KernIcons.More, "More", { menuOpen = true })
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                // Ordered by how often it is reached for, not by category. Redo sits at the
                // top because someone who has just used the undo chip and overshot is the
                // likeliest person to open this menu at all.
                MenuAction("redo", { menuOpen = false }) { InputTargets.redo(context) }
                MenuAction("find", { menuOpen = false }) { InputTargets.find(context) }
                MenuAction("go to file", { menuOpen = false }) {
                    if (native) EditorChrome.quickOpen.value = true else WorkbenchWebView.Commands.quickOpen()
                }
                if (!native) {
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
                    if (native) {
                        NativeEditor.saveAll()
                        // Carry the file being edited across, so the switch lands where
                        // the user was rather than on an empty workbench.
                        val file = NativeEditor.active.value
                        val app = context.applicationContext
                        if (file != null) AppScope.start { WorkbenchFiles.open(app, file) }
                    }
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
                label.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = {
            dismiss()
            onClick()
        },
    )
}
