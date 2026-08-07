package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.LinuxRuntime
import dev.kern.app.runtime.RootfsInstaller
import dev.kern.app.runtime.StorageManager
import kotlinx.coroutines.launch

/**
 * Settings: what the Linux guest is using, how much it is allowed to use before the app
 * complains, and how to reclaim space or start over.
 */
@Composable
fun SettingsScreen(onDismiss: () -> Unit, onGuestDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usage by remember { mutableStateOf<StorageManager.Usage?>(null) }
    var measuring by remember { mutableStateOf(true) }
    // Two separate facts: `busy` is an operation still running and gates the buttons,
    // `result` is what the last one said and outlives it.
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var guestOs by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val installerStage by RootfsInstaller.stage.collectAsStateWithLifecycle()

    LaunchedEffect(refresh) {
        measuring = true
        usage = StorageManager.measure(context)
        measuring = false
    }

    LaunchedEffect(refresh) { guestOs = LinuxRuntime.osPrettyName(context) }

    // A repair now runs outside this screen's scope, so pick up its result when it lands.
    LaunchedEffect(installerStage) {
        if (installerStage is RootfsInstaller.Stage.Done) refresh++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "settings",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) {
                Text("refresh", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(onClick = onDismiss) {
                Text("close", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Updates")
            UpdateSection()

            SectionTitle("GitHub")
            GitHubSection()

            SectionTitle("Agent")
            AgentSection()

            SectionTitle("Linux")
            when (val name = guestOs) {
                null -> Text(
                    "Checking...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    // An environment set up by an older build stays on the release it
                    // was built from; say so, rather than leaving the version looking
                    // like a bug.
                    if (!name.contains(RootfsInstaller.UBUNTU_RELEASE)) {
                        Text(
                            "New setups now use Ubuntu ${RootfsInstaller.UBUNTU_RELEASE}. " +
                                "Existing environments are left alone, so this one stays " +
                                "as it is - delete it below and run setup again to move " +
                                "over. Push anything in ~/projects first.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionTitle("Storage")

            if (measuring) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  measuring the Linux install...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            usage?.let { u ->
                UsageBar(u)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    UsageRow("Linux total", StorageManager.format(u.guestMb))
                    UsageRow("  your projects", StorageManager.format(u.projectsMb))
                    UsageRow("  package cache", StorageManager.format(u.aptCacheMb))
                    UsageRow("Free on device", StorageManager.format(u.freeMb))
                }

                if (u.overLimit) {
                    Text(
                        "Linux is over the ${StorageManager.format(u.limitMb.toLong())} " +
                            "budget you set.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionTitle("Budget")
            Text(
                "Linux is a folder inside this app, not a fixed-size disk, so this is a " +
                    "warning threshold rather than a hard cap - Android gives no way to " +
                    "enforce one without root. You will be told when it is exceeded.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LimitChooser(
                selected = usage?.limitMb ?: StorageManager.limitMb(context),
                onSelect = {
                    StorageManager.setLimitMb(context, it)
                    refresh++
                },
            )

            SectionTitle("Maintenance")
            (busy ?: result)?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = busy == null,
                    onClick = {
                        busy = "Cleaning up..."
                        scope.launch {
                            result = StorageManager.cleanUp(context)
                            busy = null
                            refresh++
                        }
                    },
                ) { Text("Free up space") }

                TextButton(
                    enabled = busy == null && !RootfsInstaller.isRunning,
                    onClick = {
                        // Started on the installer's own scope, not this screen's:
                        // closing settings must not cancel a repair mid-package. It is
                        // idempotent, skipping whatever is already in place.
                        RootfsInstaller.start(context)
                    },
                ) { Text("Repair") }
            }
            Text(
                "Free up space clears the package cache, temporary files and logs - your " +
                    "projects and installed tools are untouched. Repair reinstalls any " +
                    "missing tools, for a setup that was interrupted.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Surface live progress from the installer while Repair runs.
            (installerStage as? RootfsInstaller.Stage.Working)?.let {
                Text(
                    "${it.what}...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            SectionTitle("Reset")
            if (!confirmDelete) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete Linux", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Removes the whole environment, including anything in ~/projects that " +
                        "you have not pushed. Setup would run again from scratch.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "This deletes ${StorageManager.format(usage?.guestMb ?: 0)} including " +
                        "unpushed work. There is no undo.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = busy == null,
                        onClick = {
                            busy = "Deleting..."
                            scope.launch {
                                StorageManager.deleteGuest(context)
                                busy = null
                                onGuestDeleted()
                            }
                        },
                    ) { Text("Delete everything") }
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UsageBar(u: StorageManager.Usage) {
    val over = u.overLimit
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(u.fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (over) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    ),
            )
        }
        Text(
            "${StorageManager.format(u.guestMb)} of " +
                "${StorageManager.format(u.limitMb.toLong())} budget",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LimitChooser(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StorageManager.LIMIT_CHOICES.forEach { choice ->
            val active = choice == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(choice) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    StorageManager.format(choice.toLong()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.lowercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
