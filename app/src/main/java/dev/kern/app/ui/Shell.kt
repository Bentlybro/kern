package dev.kern.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.kern.app.runtime.RootfsInstaller
import dev.kern.app.runtime.UsageTracker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.session.SessionService
import dev.kern.app.session.SessionState

/**
 * Where the shell is. One slot rather than a flag per screen: precedence used to be
 * written down once per notation — five back handlers, five `if` blocks and one usage
 * effect — and the copies had already drifted apart.
 *
 * The terminal is deliberately not a destination. It is a pane inside [Editor], shown
 * beside the workbench on the wide postures, so it stays a boolean.
 */
sealed interface ShellDestination {

    /** Which bucket time spent here belongs to (M5a). */
    val surface: UsageTracker.Surface

    data object Editor : ShellDestination {
        override val surface = UsageTracker.Surface.Editor
    }

    data object Projects : ShellDestination {
        override val surface = UsageTracker.Surface.Projects
    }

    data object Cockpit : ShellDestination {
        override val surface = UsageTracker.Surface.Cockpit
    }

    // Status and Settings are chrome, not work. Counting them as editor time is what made
    // the editorShare number too flattering to settle D12 with.
    data object Status : ShellDestination {
        override val surface = UsageTracker.Surface.Chrome
    }

    data object Settings : ShellDestination {
        override val surface = UsageTracker.Surface.Chrome
    }
}

/**
 * The native fold shell (M2, decision D12).
 *
 * The workbench WebView is handed *only* an editor pane; all chrome — status, actions,
 * key row, and the posture-specific layout around it — is native Compose. IME insets are
 * owned here, so opening the keyboard resizes panes instead of glitching the web layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(state: SessionState) {
    val fold = rememberFoldState()
    var showTerminal by remember { mutableStateOf(false) }
    // Always open on the IDE. The cockpit is a place you choose to go (the "agent" chip),
    // not something that greets you.
    var destination by remember { mutableStateOf<ShellDestination>(ShellDestination.Editor) }

    // The two conditions are each other's negation, so precedence cannot be got wrong.
    // Compose gives priority to the most recently registered enabled handler, and while
    // precedence was spelled out by hand the escape handler outranked the settings and
    // status handlers declared above it and they never fired — back sent ESC into a
    // detached WebView and those screens simply ignored the gesture.
    BackHandler(enabled = destination != ShellDestination.Editor) {
        destination = ShellDestination.Editor
    }
    BackHandler(enabled = destination == ShellDestination.Editor) {
        WorkbenchWebView.Commands.escape()
    }

    // M5a: record where session time actually goes, so decision D12 can be settled with
    // a number instead of a hunch.
    val context = LocalContext.current
    LaunchedEffect(destination, showTerminal) {
        val onTerminal = destination == ShellDestination.Editor && showTerminal
        UsageTracker.enter(
            context,
            if (onTerminal) UsageTracker.Surface.Terminal else destination.surface,
        )
    }
    DisposableEffect(Unit) { onDispose { UsageTracker.flush(context) } }

    // The strip sits above the destination, not inside the editor layout: Projects is the
    // screen that fails *because* the toolchain has not landed yet, and while the strip
    // lived under the editor's top bar that was the one screen unable to say so. Owning the
    // system-bar inset here costs the screens below nothing - their ScreenSurface finds it
    // already consumed and adds none of its own.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        SetupStrip()

        when (destination) {
            ShellDestination.Settings -> {
                SettingsScreen(
                    onDismiss = { destination = ShellDestination.Editor },
                    // Deleting the guest invalidates the whole session; drop back to setup.
                    onGuestDeleted = {
                        destination = ShellDestination.Editor
                        SessionService.stop(context)
                    },
                )
                return@Column
            }

            ShellDestination.Status -> {
                StatusScreen(
                    onDismiss = { destination = ShellDestination.Editor },
                    onOpenSettings = { destination = ShellDestination.Settings },
                )
                return@Column
            }

            ShellDestination.Cockpit -> {
                CockpitScreen(onDismiss = { destination = ShellDestination.Editor })
                return@Column
            }

            ShellDestination.Projects -> {
                ProjectsScreen(
                    onOpenFolder = { path ->
                        WorkbenchWebView.openFolder(path)
                        destination = ShellDestination.Editor
                    },
                    onDismiss = { destination = ShellDestination.Editor },
                )
                return@Column
            }

            // Falls through to the layout below; every other destination has taken over the
            // rest of the window and returned.
            ShellDestination.Editor -> Unit
        }

        DisposableEffect(Unit) {
            onDispose {
                WorkbenchWebView.detach()
                // Only what this layout was showing. Compose applies every change before it
                // dispatches remember observers, so the cockpit has already attached the
                // agent by the time this runs and a global detach undid it.
                TerminalSessions.detachShells()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
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
                onNavigate = { destination = it },
                onNewShell = { showTerminal = true },
                onQuit = {
                    // Stops the supervisor, which takes code-server and the guest with it,
                    // then closes the app. Without this the only way to shut the session
                    // down was the notification action, which is not where anyone looks.
                    SessionService.stop(context)
                    (context as? android.app.Activity)?.finish()
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    // Tabletop always splits at the crease: content up, terminal down.
                    fold.mode == DisplayMode.Tabletop -> TabletopLayout(fold)

                    // Compact: one surface at a time — a split would leave neither usable.
                    fold.mode == DisplayMode.Cover ->
                        if (showTerminal) TerminalPane(Modifier.fillMaxSize())
                        else EditorPane(Modifier.fillMaxSize())

                    showTerminal -> {
                        // The pane the keyboard is typing into is the pane that needs the
                        // room. A fixed 60/40 split shares the keyboard's cost between
                        // both panes, which left the terminal ~10 rows tall on the
                        // unfolded screen — under thumbs that were covering the editor
                        // anyway. So while the IME is up and a terminal holds focus, the
                        // editor collapses to a sliver and the terminal takes the rest;
                        // either putting the keyboard away or tapping the editor restores
                        // the split. The editor stays in composition on purpose: removing
                        // it would detach the WebView and run the layout's teardown.
                        val terminalFocused by TerminalSessions.focused
                            .collectAsStateWithLifecycle()
                        val imeUp =
                            WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
                        // Snapped, not animated: each step of a weight animation re-lays
                        // out the workbench, which is the struggle imeAnimationTarget
                        // exists to avoid.
                        val editorWeight = if (imeUp && terminalFocused) 0.12f else 0.6f
                        Column(Modifier.fillMaxSize()) {
                            EditorPane(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(editorWeight),
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
                                    .weight(1f - editorWeight),
                            )
                        }
                    }

                    else -> EditorPane(Modifier.fillMaxSize())
                }
                if (state is SessionState.Reconnecting) ReconnectOverlay()
            }

            KeyRow()
        }
    }
}

/**
 * Setup's second half, reported from wherever the user happens to be standing.
 *
 * The toolchain finishes installing after the IDE has opened, so this is the only place
 * the user would otherwise learn that git is still on its way — and, just as usefully,
 * that it has arrived. Two lines of chrome, and it removes itself when there is nothing
 * left to say.
 */
@Composable
private fun SetupStrip() {
    val context = LocalContext.current
    val stage by RootfsInstaller.stage.collectAsStateWithLifecycle()
    val current = stage
    // Failed has to say so here. The toolchain installs behind the running editor, so this
    // strip is the only surface watching when it breaks, and falling through to null left
    // the user with an editor whose git, gh and tmux never arrived and nothing on screen
    // that ever mentioned it.
    val failure = (current as? RootfsInstaller.Stage.Failed)?.message
        ?.let { Diagnostics.explain(context, it) }
    val label = when (current) {
        is RootfsInstaller.Stage.Working -> current.what
        is RootfsInstaller.Stage.Downloading -> "Downloading ${current.what}"
        else -> null
    }
    if (label == null && failure == null) return

    Column(Modifier.fillMaxWidth()) {
        Text(
            failure ?: "$label — you can keep working",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = if (failure != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
        if (failure == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        } else {
            // The editor is already open by the time the toolchain breaks, so the setup
            // screen is behind the user and this strip is the only place the report can
            // still be taken from.
            CopyDiagnosticsButton(Modifier.height(30.dp))
        }
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
