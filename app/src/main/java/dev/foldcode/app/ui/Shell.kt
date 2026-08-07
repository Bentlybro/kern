package dev.foldcode.app.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.foldcode.app.runtime.UsageTracker
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
import dev.foldcode.app.session.SessionService
import dev.foldcode.app.session.SessionState

/**
 * The native fold shell (M2, decision D12).
 *
 * The workbench WebView is handed *only* an editor pane; all chrome — status, actions,
 * key row, and the posture-specific layout around it — is native Compose. IME insets are
 * owned here, so opening the keyboard resizes panes instead of glitching the web layout.
 */
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
            TerminalHost.detach()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        TopBar(
            state = state,
            mode = fold.mode,
            terminalShown = showTerminal,
            onToggleTerminal = { showTerminal = !showTerminal },
            onOpenProjects = { showProjects = true },
            onOpenCockpit = { showCockpit = true },
            onOpenStatus = { showStatus = true },
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                // Tabletop always splits at the crease: content up, terminal down.
                fold.mode == DisplayMode.Tabletop -> TabletopLayout(fold)

                // Compact: one surface at a time — a split would leave neither usable.
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
) {
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

        // Chips scroll rather than squeeze the posture label into wrapping.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip("agent", onClick = onOpenCockpit)
            ActionChip("proj", onClick = onOpenProjects)
            ActionChip("files") { WorkbenchWebView.Commands.toggleSidebar() }
            ActionChip("open") { WorkbenchWebView.Commands.quickOpen() }
            ActionChip("cmd") { WorkbenchWebView.Commands.commandPalette() }
            if (mode != DisplayMode.Tabletop) {
                ActionChip(
                    label = if (terminalShown) "editor" else "term",
                    highlighted = terminalShown,
                    onClick = onToggleTerminal,
                )
            }
            ActionChip("chat") { WorkbenchWebView.Commands.toggleChatPanel() }
            ActionChip("status", onClick = onOpenStatus)
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
    }
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
                "Server died — restarting…",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
