package dev.foldcode.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldcode.app.session.SessionService
import dev.foldcode.app.session.SessionState
import dev.foldcode.app.ui.FoldCodeTheme
import dev.foldcode.app.ui.SetupScreen
import dev.foldcode.app.ui.Shell
import dev.foldcode.app.ui.TerminalHost
import dev.foldcode.app.ui.WorkbenchWebView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoldCodeTheme {
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
        val target: View? = when {
            TerminalHost.hasFocus() -> TerminalHost.current()
            else -> WorkbenchWebView.current()
        }
        if (target != null && target.dispatchKeyEvent(event)) return true
        return super.dispatchKeyShortcutEvent(event)
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val sessionState by SessionService.state.collectAsStateWithLifecycle()
    when (sessionState) {
        is SessionState.Healthy, is SessionState.Reconnecting -> Shell(sessionState)
        else -> SetupScreen(sessionState, onStart = { SessionService.start(context) })
    }
}
