package dev.kern.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.BatteryOptimization
import dev.kern.app.runtime.LinuxRuntime
import dev.kern.app.runtime.RootfsInstaller
import dev.kern.app.runtime.StorageManager
import dev.kern.app.session.SessionState
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
    val log by RootfsInstaller.log.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    /**
     * Whether setup is still running. Read from the installer itself, not from local
     * state: setup outlives this screen, and a flag held here would be lost the moment
     * the screen was recomposed — offering to start a second install over the first.
     */
    val installing = RootfsInstaller.isRunning ||
        stage is RootfsInstaller.Stage.Downloading ||
        stage is RootfsInstaller.Stage.Working

    // Deleting the guest signals through here rather than through the installer's stage,
    // so without this key the screen goes on offering to finish setting up — and to
    // delete — an environment that is already gone.
    val installChanges by LinuxRuntime.installChanges.collectAsStateWithLifecycle()

    val installed = remember(stage, installChanges) { LinuxRuntime.isInstalled(context) }

    // Not `installed`: a download that died before the filesystem was unpacked leaves
    // nothing installed and most of the payload in the cache, and gating the delete on the
    // guest hid the one control that clears it in exactly the state that needs it.
    val removable = remember(stage, installChanges) {
        installed || RootfsInstaller.hasCachedDownloads(context)
    }

    // code-server is installed *before* the toolchain step, so "is code-server present?"
    // turns true partway through setup. Gating on `installing` too is what stops the
    // screen offering to open the IDE while apt is still working — starting the session
    // then races dpkg for its lock, and the session fails and bounces back here.
    val ready = !installing && remember(stage, installChanges) { LinuxRuntime.isReady(context) }

    // Free space moves while the app is in the background - the user leaves to clear
    // photos and comes back - so it is re-read on the way in rather than once, and again
    // whenever an install starts or stops.
    var freeBytes by remember { mutableLongStateOf(Diagnostics.freeBytes(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { freeBytes = Diagnostics.freeBytes(context) }
    LaunchedEffect(installing) { freeBytes = Diagnostics.freeBytes(context) }

    // Setup used to be offered whatever the disk said, and then died deep inside tar with
    // a message about the filesystem that named nothing anyone could act on. An unpacked
    // guest only needs what is left to download and install, so the floor drops once the
    // filesystem is there rather than refusing a Retry that would have worked.
    val requiredMb = if (installed) {
        RootfsInstaller.ESTIMATED_DOWNLOAD_MB
    } else {
        RootfsInstaller.ESTIMATED_DISK_MB
    }
    val freeMb = Diagnostics.megabytes(freeBytes)
    val enoughSpace = freeMb >= requiredMb

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Kern",
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
        if (log.isNotEmpty()) LogView(log)
        Spacer(Modifier.weight(1f))

        BatteryHint(context)

        // The service knows exactly why the server never came up, and this was the only
        // screen that never said. Without it a dead server reads as "Linux is installed"
        // over a button that walks into the same 90-second wait every time.
        (state as? SessionState.Failed)?.let {
            Text(
                it.message,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

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
        } else if (!enoughSpace && !installing) {
            Text(
                "Not enough storage. Setup needs about $requiredMb MB free and this device " +
                    "has $freeMb MB - free up about ${requiredMb - freeMb} MB more and " +
                    "come back.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Button(
                onClick = { RootfsInstaller.start(context) },
                enabled = !installing && stage !is RootfsInstaller.Stage.Done,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    when {
                        installing -> "Setting up…"
                        installed -> "Finish setup"
                        else -> "Set up Linux"
                    },
                    fontSize = 16.sp,
                )
            }
        }

        (stage as? RootfsInstaller.Stage.Failed)?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Retry walks into the same wall when the disk is what stopped it, so it
                // goes with the button above. Copying the failure never does.
                if (enoughSpace) {
                    TextButton(onClick = { RootfsInstaller.start(context) }) { Text("Retry") }
                }
                CopyDiagnosticsButton()
            }
        }

        // Settings lives inside the IDE, so a guest too broken to start one leaves the
        // user with no way to throw it away. An environment can be damaged beyond what
        // re-running setup fixes — an interrupted apt can take coreutils with it, and
        // then even `ls` is gone — so starting over has to be reachable from here.
        if (removable && !installing) {
            if (!confirmReset) {
                TextButton(onClick = { confirmReset = true }) {
                    Text("Delete and start over", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    "This deletes the Linux environment, including anything in " +
                        "~/projects you have not pushed.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        confirmReset = false
                        scope.launch { StorageManager.deleteGuest(context) }
                    }) {
                        Text("Delete everything", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The real output, tailed live.
 *
 * Setup is several minutes of work, and a lone "Installing tools" for three of them
 * looks identical to being stuck. Showing what apt is actually doing costs nothing and
 * removes the guessing.
 */
@Composable
private fun LogView(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .border(
                1.dp,
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(lines) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Diagnostics.explain(LocalContext.current, stage.message),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Android suspends background work aggressively; this is the one thing worth asking for. */
@Composable
private fun BatteryHint(context: Context) {
    // The user grants this in Settings, outside the app, so the answer is only ever
    // stale here — re-read it on the way back rather than leaving the hint up forever.
    var exempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = BatteryOptimization.isExempt(context)
    }
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
        TextButton(onClick = { BatteryOptimization.requestExemption(context) }) {
            Text("Allow")
        }
    }
}
