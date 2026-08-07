package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.AppScope
import dev.kern.app.runtime.BatteryOptimization
import dev.kern.app.runtime.HealthCheck
import dev.kern.app.runtime.UsageTracker
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Packages currently being installed, or null when idle.
 *
 * Outside the composition because apt outlives this screen now: a screen-local flag came
 * back cleared, re-enabled Fix on top of an apt that was still running, and the second one
 * died on dpkg's lock.
 */
private val installingPackages = MutableStateFlow<String?>(null)

/**
 * Environment health and usage stats (M6 + M5a). This is where the app tells the truth
 * about its own setup instead of failing silently.
 */
@Composable
fun StatusScreen(onDismiss: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HealthCheck.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val installing by installingPackages.collectAsStateWithLifecycle()

    LaunchedEffect(refresh) {
        loading = true
        items = HealthCheck.runAll(context)
        loading = false
    }

    val totals = remember(refresh) { UsageTracker.totals(context) }
    val totalMs = totals.values.sum()

    /** Install what a check said was missing, then re-run the checks. */
    fun install(packages: List<String>) {
        installingPackages.value = packages.joinToString(", ")
        // apt runs on the app scope: leaving Status used to cancel it mid-unpack, and a
        // dpkg stopped there is past what Repair can put back.
        val work = AppScope.start {
            try {
                HealthCheck.install(app, packages)
            } finally {
                // Cleared here, not after the await below: that dies with the screen, and
                // the label would then stay lit and hold every Fix button disabled.
                installingPackages.value = null
            }
        }
        scope.launch {
            work.await()
            refresh++
        }
    }

    ScreenSurface {
        ScreenHeader("status") {
            HeaderAction("settings", onOpenSettings)
            HeaderAction("recheck") { refresh++ }
            HeaderAction("close", onDismiss)
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // apt can run for minutes, so say what is happening rather than leaving a
        // disabled button and no explanation.
        installing?.let { what ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Installing $what... this can take a few minutes.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(items) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    val color = when (item.level) {
                        HealthCheck.Level.Ok -> KernColors.Ok
                        HealthCheck.Level.Warn -> KernColors.Warn
                        HealthCheck.Level.Fail -> MaterialTheme.colorScheme.error
                    }
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            item.name,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            item.detail,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.fix?.let {
                            Text(
                                it,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        // Anything the app can fix itself gets a button. Printing the
                        // shell command instead would be asking the user to type it on
                        // a phone keyboard, which is not a fix.
                        item.remedy?.let { remedy ->
                            TextButton(
                                enabled = installing == null,
                                onClick = {
                                    when (remedy) {
                                        is HealthCheck.Remedy.Install ->
                                            install(remedy.packages)
                                        HealthCheck.Remedy.OpenSettings -> onOpenSettings()
                                        HealthCheck.Remedy.BatterySettings ->
                                            BatteryOptimization.requestExemption(context)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text(
                                    item.remedyLabel ?: "Fix",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "where your time goes",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
                )
            }
            items(UsageTracker.Surface.entries) { surface ->
                val ms = totals[surface] ?: 0L
                val share = if (totalMs > 0) ms.toFloat() / totalMs else 0f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        surface.name.lowercase().padEnd(9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(share)
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        UsageTracker.format(ms),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    if (totalMs <= 0) {
                        "No usage recorded yet."
                    } else {
                        "Editor is ${"%.0f".format(UsageTracker.editorShare(context) * 100)}% " +
                            "of tracked time. Decision D12 says a native editor rewrite only " +
                            "earns its keep well above ~15%."
                    },
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
