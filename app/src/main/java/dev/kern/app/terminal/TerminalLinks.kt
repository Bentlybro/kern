package dev.kern.app.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.kern.app.ui.KernIcons
import kotlinx.coroutines.delay

/**
 * The newest URL a terminal is showing, offered as one tap.
 *
 * A dev server announces itself as `http://localhost:3000` in the terminal, and on a phone
 * there is no way to click that, select it precisely, or paste it anywhere quick. The
 * browser shares the device's loopback with the guest, so opening the URL there reaches
 * the server directly.
 */
object TerminalLinks {

    private val URL = Regex("""https?://[\w.\-]+(?::\d+)?(?:/[^\s'"<>)\]]*)?""")

    /** The last URL in [screen], trailing punctuation trimmed. Internal for tests. */
    internal fun lastUrl(screen: String): String? =
        URL.findAll(screen).lastOrNull()?.value?.trimEnd('.', ',', ';', ':')

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * A chip over the terminal's corner when it shows a URL. Read from the rendered screen
 * every second while visible: cheap, and correct for output that repaints in place.
 *
 * Only the visible rows, and only when output has arrived since the last look. This used
 * to rebuild the whole 2000-row scrollback as one string every second on the main thread,
 * idle terminal or not - and a URL that has scrolled off the screen is not one anybody is
 * about to tap.
 */
@Composable
fun TerminalLinkChip(entry: TerminalSessions.Entry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var url by remember(entry.id) { mutableStateOf<String?>(null) }
    var dismissed by remember(entry.id) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(entry.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var seen = -1
            while (true) {
                val generation = entry.session.outputGeneration
                if (generation != seen) {
                    seen = generation
                    url = runCatching {
                        entry.session.emulator?.let { emulator ->
                            emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows - 1)
                        }
                    }.getOrNull()?.let(TerminalLinks::lastUrl)
                }
                delay(1_000)
            }
        }
    }
    val shown = url?.takeIf { it != dismissed } ?: return
    Box(modifier.padding(10.dp), contentAlignment = Alignment.BottomEnd) {
        FilledTonalButton(onClick = {
            TerminalLinks.open(context, shown)
            dismissed = shown
        }) {
            Icon(KernIcons.Play, null, Modifier.size(16.dp))
            Text("  Open ${shown.removePrefix("http://").removePrefix("https://").take(28)}")
        }
    }
}
