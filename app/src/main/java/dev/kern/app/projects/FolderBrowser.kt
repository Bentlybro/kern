package dev.kern.app.projects

import dev.kern.app.ui.HeaderAction
import dev.kern.app.ui.KernColors
import dev.kern.app.ui.ScreenHeader
import dev.kern.app.ui.ScreenSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `/a/b` → `/a`, `/a` → `/`, and `/` stays itself (null means "leave the browser"). */
internal fun parentOf(path: String): String? =
    if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }

/**
 * Walk the guest's filesystem and open any directory as the workspace. One level at a
 * time, through the same one-round-trip listing Projects uses.
 */
@Composable
internal fun FolderBrowser(
    path: String,
    onNavigate: (String) -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ProjectRepository.Entry>?>(null) }
    LaunchedEffect(path) {
        entries = null
        entries = ProjectRepository.list(context, path)
    }

    ScreenSurface {
        ScreenHeader("browse") {
            HeaderAction("close", onClose)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onOpen(path) }) { Text("Open here") }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            parentOf(path)?.let { parent ->
                item {
                    FolderRow(name = "..", subtitle = parent, isRepo = false, onClick = { onNavigate(parent) })
                }
            }
            val list = entries
            when {
                list == null -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                list.isEmpty() -> item {
                    Text(
                        "No folders in here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                else -> items(list) { entry ->
                    FolderRow(
                        name = entry.name,
                        subtitle = entry.path,
                        isRepo = entry.isRepo,
                        onClick = { onNavigate(entry.path) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
internal fun FolderRow(
    name: String,
    subtitle: String,
    isRepo: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (isRepo) KernColors.Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
        Spacer(Modifier.width(12.dp))
        // Weighted so a long path cannot squeeze [trailing] out of the row entirely.
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                subtitle.removePrefix(ProjectRepository.HOME).ifEmpty { "~" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}
