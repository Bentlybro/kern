package dev.foldcode.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldcode.app.runtime.LinuxRuntime
import dev.foldcode.app.runtime.RootfsInstaller
import dev.foldcode.app.session.SessionState
import kotlinx.coroutines.launch

/**
 * First run. One button: fetch Linux and set it up. Everything the IDE needs lives inside
 * the app afterwards, so there is nothing else to install and nothing to configure.
 */
@Composable
fun SetupScreen(state: SessionState, onStart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stage by RootfsInstaller.stage.collectAsStateWithLifecycle()

    val installed = remember(stage) { LinuxRuntime.isInstalled(context) }
    val ready = remember(stage) {
        LinuxRuntime.isInstalled(context) && LinuxRuntime.isCodeServerInstalled(context)
    }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "FoldCode",
            fontFamily = FontFamily.Monospace,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (ready) {
                "Linux is installed. Everything runs on this device."
            } else {
                "A complete Linux development environment, running inside this app. " +
                    "Nothing else to install."
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!ready) {
            Text(
                "Setup downloads about ${RootfsInstaller.ESTIMATED_DOWNLOAD_MB} MB and " +
                    "uses roughly ${RootfsInstaller.ESTIMATED_DISK_MB} MB of storage. " +
                    "Use Wi-Fi — it takes a few minutes.",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        StageView(stage)
        Spacer(Modifier.weight(1f))

        BatteryHint(context)

        if (ready) {
            Button(
                onClick = onStart,
                enabled = state !is SessionState.Starting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    if (state is SessionState.Starting) "Starting…" else "Open IDE",
                    fontSize = 16.sp,
                )
            }
        } else {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        RootfsInstaller.install(context)
                        busy = false
                    }
                },
                enabled = !busy && stage !is RootfsInstaller.Stage.Done,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    when {
                        busy -> "Setting up…"
                        installed -> "Finish setup"
                        else -> "Set up Linux"
                    },
                    fontSize = 16.sp,
                )
            }
        }

        (stage as? RootfsInstaller.Stage.Failed)?.let {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    RootfsInstaller.install(context)
                    busy = false
                }
            }) { Text("Retry") }
        }
    }
}

@Composable
private fun StageView(stage: RootfsInstaller.Stage) {
    when (stage) {
        is RootfsInstaller.Stage.Idle -> Unit

        is RootfsInstaller.Stage.Downloading -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val mb = stage.bytes / (1024 * 1024)
            val totalMb = stage.total / (1024 * 1024)
            Text(
                if (stage.total > 0) {
                    "Downloading ${stage.what} — $mb / $totalMb MB"
                } else {
                    "Downloading ${stage.what} — $mb MB"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stage.total > 0) {
                LinearProgressIndicator(
                    progress = { stage.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is RootfsInstaller.Stage.Working -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${stage.what}…",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is RootfsInstaller.Stage.Done -> Text(
            "Ready.",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )

        is RootfsInstaller.Stage.Failed -> Text(
            stage.message,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Android suspends background work aggressively; this is the one thing worth asking for. */
@Composable
private fun BatteryHint(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val exempt = remember { pm.isIgnoringBatteryOptimizations(context.packageName) }
    if (exempt) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Text(
                "Allow unrestricted battery use so builds and agents keep running with " +
                    "the screen off.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.packageName),
                ),
            )
        }) { Text("Allow") }
    }
}
