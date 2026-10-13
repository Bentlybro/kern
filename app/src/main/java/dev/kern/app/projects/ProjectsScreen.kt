package dev.kern.app.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kern.app.ui.KernIcons
import dev.kern.app.ui.ScreenHeader
import dev.kern.app.ui.ScreenSurface
import dev.kern.app.ui.SectionTitle

/**
 * Projects: what you have, first - recent folders, then everything in ~/projects - with
 * New and Clone as two buttons that open focused dialogs. Long-press a project to delete
 * it; the guest work behind every action lives in [ProjectsModel].
 */
@Composable
fun ProjectsScreen(
    onOpenFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { ProjectsModel(context.applicationContext, scope) }

    var entries by remember { mutableStateOf<List<ProjectRepository.Entry>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ProjectRepository.Entry?>(null) }
    // Non-null while the folder browser is up, holding the directory it shows.
    var browsePath by remember { mutableStateOf<String?>(null) }
    // Where the browser was opened, which is where back closes it: at home, back used to
    // climb to "/" instead of returning to Projects (caught on device).
    var browseStart by remember { mutableStateOf<String?>(null) }
    // Where you have been, minus where you are: the open project is already on screen.
    val recents = remember(model.reload) {
        val current = ProjectRepository.currentFolder(context)
        ProjectRepository.recents(context).filter { it != current }.take(5)
    }

    LaunchedEffect(model.reload) {
        entries = ProjectRepository.list(context)
    }

    fun open(path: String) {
        ProjectRepository.rememberOpened(context, path)
        onOpenFolder(path)
    }

    browsePath?.let { path ->
        BackHandler { browsePath = if (path == browseStart) null else parentOf(path) }
        FolderBrowser(
            path = path,
            onNavigate = { browsePath = it },
            onOpen = { open(it) },
            onClose = { browsePath = null },
        )
        return
    }

    ScreenSurface {
        ScreenHeader("Projects", onBack = onDismiss)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { dialog = "new" }, modifier = Modifier.weight(1f)) {
                Icon(KernIcons.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("New project")
            }
            FilledTonalButton(onClick = { dialog = "clone" }, modifier = Modifier.weight(1f)) {
                Icon(KernIcons.Branch, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Clone")
            }
        }
        // Deletes report here; create and clone report in their dialogs.
        if (dialog == null) {
            (model.busy ?: model.message)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (recents.isNotEmpty()) {
                item { SectionTitle("Recent", Modifier.padding(start = 16.dp, top = 8.dp)) }
                items(recents) { path ->
                    FolderRow(name = path.substringAfterLast('/'), subtitle = path, isRepo = false, onClick = { open(path) })
                }
            }

            item { SectionTitle("~/projects", Modifier.padding(start = 16.dp, top = 8.dp)) }
            when (val list = entries) {
                null -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                else -> {
                    if (list.isEmpty()) {
                        item {
                            Text(
                                "Nothing here yet - start a new project or clone one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    items(list) { entry ->
                        FolderRow(
                            name = entry.name,
                            subtitle = entry.path,
                            isRepo = entry.isRepo,
                            onClick = { open(entry.path) },
                            onLongClick = { confirmDelete = entry },
                        )
                    }
                }
            }

            item { SectionTitle("Other", Modifier.padding(start = 16.dp, top = 8.dp)) }
            item { FolderRow(name = "Home", subtitle = ProjectRepository.HOME, isRepo = false, onClick = { open(ProjectRepository.HOME) }) }
            // "Open any folder": a package inside a monorepo, or anything outside ~/projects.
            item { FolderRow(name = "Open folder…", subtitle = "Browse the whole filesystem", isRepo = false, onClick = {
                browseStart = ProjectRepository.HOME
                browsePath = ProjectRepository.HOME
            }) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    when (dialog) {
        "new" -> NewProjectDialog(model, onCreated = { dialog = null; onOpenFolder(it) }, onDismiss = { dialog = null; model.message = null })
        "clone" -> CloneDialog(model, onCloned = { dialog = null; onOpenFolder(it) }, onDismiss = { dialog = null; model.message = null })
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${entry.name}?") },
            text = { Text("The folder and everything in it, including anything not pushed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; model.delete(entry.name) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
