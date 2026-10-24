package dev.kern.app

import dev.kern.app.workbench.CodeServer
import dev.kern.app.core.AppScope
import dev.kern.app.editor.EditorMode
import dev.kern.app.core.UsageTracker
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.input.focusedInputView
import dev.kern.app.projects.IncomingFiles
import dev.kern.app.session.SessionService
import dev.kern.app.session.SessionState
import dev.kern.app.setup.SetupScreen
import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.ui.KernTheme
import dev.kern.app.ui.Shell
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Terminals outlive this Activity - they are process scoped so a fold or a
        // recreation does not throw away a running shell - so they are built against a
        // wrapper rather than against `this`, and it has to be pointed at the current
        // Activity before anything asks for one. Without it every recreation leaked this
        // whole window. WorkbenchWebView does the same thing at its own acquire().
        TerminalSessions.rebind(this)

        // Eager start: once Linux is set up there is nothing to decide, so begin booting
        // code-server the moment the app opens rather than waiting for a button. Starting
        // it is what costs seconds, and doing it now overlaps with the UI drawing.
        if (LinuxRuntime.isReady(this)) {
            SessionService.start(this)
        }

        setContent {
            KernTheme {
                AppRoot()
            }
        }

        // Only a fresh launch: a recreation re-delivers the original intent, and the file
        // it carried was already imported the first time round.
        if (savedInstanceState == null) importFrom(intent)
    }

    /** singleTask: a share while Kern is already open arrives here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importFrom(intent)
    }

    private fun importFrom(intent: Intent?) {
        if (!IncomingFiles.accepts(intent)) return
        val app = applicationContext
        AppScope.start {
            IncomingFiles.handle(app, intent!!)
        }
    }

    /**
     * Stop and start the usage clock with the window, not with the composition.
     *
     * [UsageTracker] measures which surface the user is actually in,
     * and nothing was stopping it when the app left the screen - so a phone in a pocket
     * kept banking time against whatever was last open. See UsageTracker.pause.
     */
    override fun onStart() {
        super.onStart()
        UsageTracker.resume(this)
    }

    /**
     * Back after being away: if the session was stopped meanwhile - Stop on the
     * notification - and VS Code is the editor, start it again.
     *
     * The activity outlives that Stop, so neither onCreate's eager start nor AppRoot's
     * keyed effect runs again, and the workbench sat on "starting VS Code" over a session
     * nobody was starting. Here rather than whenever the state reads Idle: Stop pressed
     * from the shade over a visible app is a request to stop, and the booting screen
     * offers Start for that instead of overruling it. Quit finishes the activity, so it
     * never comes back through here.
     */
    override fun onRestart() {
        super.onRestart()
        if (SessionService.state.value is SessionState.Idle &&
            !EditorMode.isNative(this) &&
            LinuxRuntime.isReady(this)
        ) {
            SessionService.start(this)
        }
    }

    override fun onStop() {
        UsageTracker.pause(this)
        super.onStop()
    }

    /**
     * Hardware-keyboard chords (Ctrl+P, Ctrl+Shift+P, …) are otherwise consumed as system
     * shortcuts before the WebView sees them. Forward them to whichever surface has
     * focus so a Bluetooth keyboard behaves like it does on the desktop.
     *
     * The suppression is not a shortcut taken. `dispatchKeyShortcutEvent` is declared on
     * android.app.Activity, which is where this overrides it and where it is public API;
     * androidx's ComponentActivity happens to carry a @RestrictTo on its own override, and
     * lint attributes that to any subclass calling `super`. There is no non-restricted way
     * to override a platform method whose androidx ancestor is annotated, and not calling
     * super would swallow every shortcut androidx handles itself. Scoped to this member so
     * it cannot quietly cover anything else.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        val target = focusedInputView(this)
        if (target != null && target.dispatchKeyEvent(event)) return true
        return super.dispatchKeyShortcutEvent(event)
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val sessionState by SessionService.state.collectAsStateWithLifecycle()

    // Only show setup when there is genuinely something to set up. When Linux is ready
    // the shell owns the loading state, so the app opens straight into the IDE instead
    // of a screen whose only job is a button.
    //
    // Keyed on installChanges: this is a question about the filesystem, which Compose
    // cannot observe on its own. Remembered unconditionally, it was answered once at
    // launch and never revisited, so deleting the environment stranded the app on
    // "starting linux" instead of returning it to setup.
    val installChanges by LinuxRuntime.installChanges.collectAsStateWithLifecycle()
    val ready = remember(installChanges) { LinuxRuntime.isReady(context) }
    val editorMode by EditorMode.flow(context).collectAsStateWithLifecycle()
    val nativeEditor = editorMode == EditorMode.Kind.Native

    // An environment with no session running should start one. This is what carries the
    // app from setup finishing straight into the IDE, without the user pressing a second
    // button — and it is what stops the loading screen ever being shown with nothing
    // actually loading behind it. Starting twice is harmless: the runtime returns early
    // when a server is already up.
    LaunchedEffect(ready) {
        if (ready && SessionService.state.value is SessionState.Idle) {
            SessionService.start(context)
        }
    }

    when {
        // Checked first: with no environment there is nothing for the IDE or the
        // loading screen to be about, whatever the session last reported.
        !ready -> SetupScreen(sessionState, onStart = { SessionService.start(context) })

        // The native editor reads the guest's files directly and needs no server, so it
        // opens the moment Linux is installed rather than after code-server's cold start.
        nativeEditor -> Shell(sessionState)

        sessionState is SessionState.Healthy || sessionState is SessionState.Reconnecting ->
            Shell(sessionState)

        sessionState !is SessionState.Failed -> BootingScreen(sessionState)

        else -> SetupScreen(sessionState, onStart = { SessionService.start(context) })
    }
}

/**
 * Shown for the second or two between opening the app and the workbench being live.
 *
 * It has a way out because it is not always a second or two: a session stopped from the
 * notification leaves nothing starting behind it, and a code-server that will not come up
 * spins here until the supervisor gives up. The native editor needs no server at all, so
 * it is always one tap away.
 */
@Composable
private fun BootingScreen(state: SessionState) {
    val context = LocalContext.current
    // Idle for a moment is normal - the start onCreate asked for has not reached the
    // service yet - so only an Idle that lasts reads as stopped, or every cold launch
    // would flash "not running" before it started.
    val stopped by produceState(initialValue = false, state) {
        value = false
        if (state is SessionState.Idle) {
            delay(1_500)
            value = true
        }
    }

    // A file check, off the main thread, and once per state rather than on every
    // recomposition. Assumed installed until it answers - the common case by far.
    val installed by produceState(initialValue = true, state) {
        value = withContext(Dispatchers.IO) { CodeServer.isInstalled(context) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!stopped) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            when {
                stopped -> "VS Code is not running"
                state is SessionState.Reconnecting -> "reconnecting"
                installed -> "starting VS Code"
                else -> "installing VS Code - this takes a minute"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 20.dp),
        ) {
            // Only when stopped: while a start is under way the service keeps the
            // supervisor it has and a second start does nothing, so a Retry there would be
            // a button that cannot do anything. A start that fails ends on the setup
            // screen, which has its own.
            if (stopped) {
                Button(onClick = { SessionService.start(context) }) { Text("Start VS Code") }
            }
            TextButton(onClick = { EditorMode.set(context, EditorMode.Kind.Native) }) {
                Text("Use native editor")
            }
        }
    }
}
