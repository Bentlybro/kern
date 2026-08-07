package dev.foldcode.app.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldcode.app.runtime.HealthCheck
import dev.foldcode.app.runtime.UsageTracker

/**
 * Environment health and usage stats (M6 + M5a). This is where the app tells the truth
 * about its own setup instead of failing silently.
 */
@Composable
fun StatusScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<HealthCheck.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        items = HealthCheck.runAll(context)
        loading = false
    }

    val totals = remember(refresh) { UsageTracker.totals(context) }
    val totalMs = totals.values.sum()

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
                "status",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) {
                Text("recheck", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(onClick = onDismiss) {
                Text("close", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
                        HealthCheck.Level.Ok -> Color(0xFF6FAE7F)
                        HealthCheck.Level.Warn -> Color(0xFFC99A4E)
                        HealthCheck.Level.Fail -> Color(0xFFD07158)
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
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
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
