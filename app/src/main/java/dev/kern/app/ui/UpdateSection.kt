package dev.kern.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.Updates
import kotlinx.coroutines.launch

/**
 * Optional updates from GitHub Releases.
 *
 * Nothing here happens on its own. An update is offered, can be skipped for good, and the
 * install still goes through the platform's own confirmation — an IDE that restarts
 * itself mid-edit would be worse than one that is a version behind.
 */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsStateWithLifecycle()

    // Check on open rather than on a timer: this screen is the only place it surfaces,
    // so anywhere else would be doing network the user never asked for.
    LaunchedEffect(Unit) { Updates.check(context) }

    // Coming back from Android's "Install unknown apps" screen is a resume, not a
    // recomposition, so nothing here would notice the grant by itself — the user
    // returned to the same "needs permission" text they left, with the toggle already
    // on. Watching resume puts them back on the offer the moment they return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val current = Updates.state.value
            if (event == Lifecycle.Event.ON_RESUME &&
                current is Updates.State.NeedsPermission &&
                Updates.canInstall(context)
            ) {
                Updates.permissionGranted(current.release)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Version ${Updates.currentVersion}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )

        when (val current = state) {
            is Updates.State.Checking -> Muted("Checking for updates...")

            is Updates.State.UpToDate -> {
                Muted("Up to date.")
                CheckAgain(scope, context)
            }

            is Updates.State.Available -> {
                Text(
                    "Version ${current.release.version} is available.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (current.release.notes.isNotBlank()) {
                    Text(
                        current.release.notes.lineSequence().take(8).joinToString("\n"),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // Checked before the download, not after: without the grant the
                        // platform blocks the session at commit, tens of megabytes too
                        // late, and the error it produces ("blocked by unknown source
                        // package") names a settings screen it does not open.
                        if (!Updates.canInstall(context)) {
                            Updates.needsPermission(current.release)
                            return@Button
                        }
                        scope.launch {
                            val apk = Updates.download(context, current.release)
                            if (apk != null) Updates.install(context, apk)
                        }
                    }) {
                        Text("Update (${current.release.sizeBytes / (1024 * 1024)} MB)")
                    }
                    TextButton(onClick = { Updates.skip(context, current.release.version) }) {
                        Text("Skip this version")
                    }
                }
            }

            is Updates.State.NeedsPermission -> {
                Text(
                    "Android needs your permission before Kern can install its own " +
                        "updates. It is a one-time switch: \"Allow from this source\" on " +
                        "the screen behind this button.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    runCatching {
                        context.startActivity(Updates.permissionSettingsIntent(context))
                    }
                }) {
                    Text("Open Android settings")
                }
            }

            is Updates.State.Downloading -> {
                Muted("Downloading... ${current.percent}%")
                LinearProgressIndicator(
                    progress = { current.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }

            is Updates.State.Installing -> Muted("Installing — Android will ask you to confirm.")

            is Updates.State.Failed -> {
                Text(
                    current.message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                CheckAgain(scope, context)
            }

            is Updates.State.Idle -> {
                Updates.skippedVersion(context)?.let {
                    Muted("Version $it was skipped.")
                }
                CheckAgain(scope, context)
            }
        }
    }
}

@Composable
private fun CheckAgain(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    // includeSkipped, because pressing this is asking again on purpose.
    TextButton(onClick = { scope.launch { Updates.check(context, includeSkipped = true) } }) {
        Text("Check for updates", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
