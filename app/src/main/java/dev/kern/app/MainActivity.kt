package dev.kern.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.LinuxRuntime
import dev.kern.app.session.SessionService
import dev.kern.app.session.SessionState
import dev.kern.app.ui.KernTheme
import dev.kern.app.ui.SetupScreen
import dev.kern.app.ui.Shell
import dev.kern.app.ui.focusedInputView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Terminals outlive this Activity - they are process scoped so a fold or a
        // recreation does not throw away a running shell - so they are built against a
        // wrapper rather than against `this`, and it has to be pointed at the current
        // Activity before anything asks for one. Without it every recreation leaked this
        // whole window. WorkbenchWebView does the same thing at its own acquire().
        dev.kern.app.ui.TerminalSessions.rebind(this)

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
    }

    /**
     * Hardware-keyboard chords (Ctrl+P, Ctrl+Shift+P, …) are otherwise consumed as system
     * shortcuts before the WebView sees them. Forward them to whichever surface has
     * focus so a Bluetooth keyboard behaves like it does on the desktop.
     */
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        val target = focusedInputView()
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

        sessionState is SessionState.Healthy || sessionState is SessionState.Reconnecting ->
            Shell(sessionState)

        sessionState !is SessionState.Failed -> BootingScreen(sessionState)

        else -> SetupScreen(sessionState, onStart = { SessionService.start(context) })
    }
}

/** Shown for the second or two between opening the app and the workbench being live. */
@Composable
private fun BootingScreen(state: SessionState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            if (state is SessionState.Reconnecting) "reconnecting" else "starting linux",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
