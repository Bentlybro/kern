package dev.kern.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.kern.app.runtime.AgentRepository
import dev.kern.app.runtime.RootfsInstaller
import dev.kern.app.runtime.UsageTracker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.session.SessionService
import dev.kern.app.session.SessionState

/**
 * The native fold shell (M2, decision D12).
 *
 * The workbench WebView is handed *only* an editor pane; all chrome â€” status, actions,
 * key row, and the posture-specific layout around it â€” is native Compose. IME insets are
 * owned here, so opening the keyboard resizes panes instead of glitching the web layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(state: SessionState) {
    val fold = rememberFoldState()
    var showTerminal by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    // Always open on the IDE. The cockpit is a place you choose to go (the "agent" chip),
    // not something that greets you.
    var showCockpit by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showStatus && !showSettings) { showStatus = false }
    BackHandler(enabled = showProjects) { showProjects = false }
    BackHandler(enabled = showCockpit && !showProjects) { showCockpit = false }
    BackHandler(enabled = !showProjects && !showCockpit) { WorkbenchWebView.Commands.escape() }

    // M5a: record where session time actually goes, so decision D12 can be settled with
    // a number instead of a hunch.
    val context = LocalContext.current
    LaunchedEffect(showCockpit, showProjects, showTerminal) {
        UsageTracker.enter(
            context,
            when {
                showProjects -> UsageTracker.Surface.Projects
                showCockpit -> UsageTracker.Surface.Cockpit
                showTerminal -> UsageTracker.Surface.Terminal
                else -> UsageTracker.Surface.Editor
            },
        )
    }
    DisposableEffect(Unit) { onDispose { UsageTracker.flush(context) } }

    if (showSettings) {
        SettingsScreen(
            onDismiss = { showSettings = false },
            // Deleting the guest invalidates the whole session; drop back to setup.
            onGuestDeleted = {
                showSettings = false
                SessionService.stop(context)
            },
        )
        return
    }

    if (showStatus) {
        StatusScreen(
            onDismiss = { showStatus = false },
            onOpenSettings = { showStatus = false; showSettings = true },
        )
        return
    }

    if (showCockpit && !showProjects) {
        CockpitScreen(onDismiss = { showCockpit = false })
        return
    }

    if (showProjects) {
        ProjectsScreen(
            onOpenFolder = { path ->
                WorkbenchWebView.openFolder(path)
                showProjects = false
            },
            onDismiss = { showProjects = false },
        )
        return
    }

    DisposableEffect(Unit) {
        onDispose {
            WorkbenchWebView.detach()
            TerminalSessions.detachAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            // imeAnimationTarget rather than imePadding: the animated version resizes on
            // every frame of the keyboard animation, and each resize makes the WebView
            // re-lay out the entire workbench, which is what made opening and closing the
            // keyboard feel like it was struggling. This takes the final size at once and
            // lets the keyboard animate over a layout that has already settled.
            .windowInsetsPadding(WindowInsets.imeAnimationTarget),
    ) {
        TopBar(
            state = state,
            mode = fold.mode,
            terminalShown = showTerminal,
            onToggleTerminal = { showTerminal = !showTerminal },
            onOpenProjects = { showProjects = true },
            onOpenCockpit = { showCockpit = true },
            onOpenStatus = { showStatus = true },
            onOpenSettings = { showSettings = true },
            onNewShell = { showTerminal = true },
        )

        SetupStrip()

        Box(modifier = Modifier.weight(1f)) {
            when {
                // Tabletop always splits at the crease: content up, terminal down.
                fold.mode == DisplayMode.Tabletop -> TabletopLayout(fold)

                // Compact: one surface at a time â€” a split would leave neither usable.
                fold.mode == DisplayMode.Cover ->
                    if (showTerminal) TerminalPane(Modifier.fillMaxSize())
                    else EditorPane(Modifier.fillMaxSize())

                showTerminal -> Column(Modifier.fillMaxSize()) {
                    EditorPane(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.6f),
                    )
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    TerminalPane(
                        Modifier
                            .fillMaxWidth()
                            .weight(0.4f),
                    )
                }

                else -> EditorPane(Modifier.fillMaxSize())
            }
            if (state is SessionState.Reconnecting) ReconnectOverlay()
        }

        KeyRow()
    }
}

/**
 * Setup's second half, reported from inside the editor.
 *
 * The toolchain finishes installing after the IDE has opened, so this is the only place
 * the user would otherwise learn that git is still on its way â€” and, just as usefully,
 * that it has arrived. Two lines of chrome, and it removes itself when there is nothing
 * left to say.
 */
@Composable
private fun SetupStrip() {
    val stage by RootfsInstaller.stage.collectAsStateWithLifecycle()
    val label = when (val current = stage) {
        is RootfsInstaller.Stage.Working -> current.what
        is RootfsInstaller.Stage.Downloading -> "Downloading ${current.what}"
        else -> null
    } ?: return

    Column(Modifier.fillMaxWidth()) {
        Text(
            "$label â€” you can keep working",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

/** Tabletop: content on the upright half, controls on the flat half (docs/05). */
@Composable
private fun TabletopLayout(fold: FoldState) {
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

@Composable
private fun EditorPane(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> WorkbenchWebView.acquire(ctx) },
        )
        // Native selection handles ride on top of the workbench (M4 part 2).
        SelectionOverlay()
    }
}

@Composable
private fun TopBar(
    state: SessionState,
    mode: DisplayMode,
    terminalShown: Boolean,
    onToggleTerminal: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenCockpit: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewShell: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Re-read when the menu opens, so choosing an agent in settings is reflected without
    // needing a restart.
    val agentConfigured = remember(menuOpen) { AgentRepository.isConfigured(context) }


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = when (state) {
            is SessionState.Healthy -> Color(0xFF6FAE7F)
            is SessionState.Reconnecting -> Color(0xFFC99A4E)
            else -> Color(0xFF8F929A)
        }
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(8.dp))

        // Only the four surfaces worth a permanent thumb target. Everything else lives
        // behind "more" â€” reachable, but not competing for the bar. Still scrollable so
        // the cover display cannot wrap the posture label.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip("files") { WorkbenchWebView.Commands.toggleSidebar() }
            ActionChip("git") { WorkbenchWebView.Commands.toggleSourceControl() }
            ActionChip("project", onClick = onOpenProjects)
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
            if (agentConfigured) ActionChip("agent", onClick = onOpenCockpit)
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
                MenuAction("status", { menuOpen = false }, onOpenStatus)
                MenuAction("settings", { menuOpen = false }, onOpenSettings)
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
private fun ActionChip(
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

@Composable
private fun ReconnectOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                "Server died â€” restartingâ€¦",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
