package dev.kern.app.editor

import dev.kern.app.ui.KernIcons
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.projects.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Native-editor chrome the top bar drives: whether the tree and the find bar are showing. */
object EditorChrome {
    val treeOpen = MutableStateFlow(false)
    val findOpen = MutableStateFlow(false)
    val quickOpen = MutableStateFlow(false)

    /** Whether any of the above is showing - what back should close first. */
    fun anyOpen(): Boolean = treeOpen.value || findOpen.value || quickOpen.value

    /** Close the topmost: go-to-file, then the tree, then find. */
    fun closeTop() {
        when {
            quickOpen.value -> quickOpen.value = false
            treeOpen.value -> treeOpen.value = false
            else -> findOpen.value = false
        }
    }

    fun toggleTree() {
        treeOpen.value = !treeOpen.value
    }

    fun toggleFind() {
        findOpen.value = !findOpen.value
    }
}

/**
 * The native editor pane: tabs, the editor, a find bar, and the file tree.
 *
 * The tree docks beside the editor when there is room (an unfolded inner screen, a tablet,
 * landscape) and slides over it otherwise - on a phone held upright a docked tree would
 * leave a code column too narrow to read.
 */
@Composable
fun NativeEditorPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeOpen by EditorChrome.treeOpen.collectAsStateWithLifecycle()
    val findOpen by EditorChrome.findOpen.collectAsStateWithLifecycle()
    val quickOpen by EditorChrome.quickOpen.collectAsStateWithLifecycle()
    val open by NativeEditor.open.collectAsStateWithLifecycle()
    val active by NativeEditor.active.collectAsStateWithLifecycle()
    val unsaved by NativeEditor.unsaved.collectAsStateWithLifecycle()
    val problem by NativeEditor.problem.collectAsStateWithLifecycle()
    val conflicts by DiskSync.conflicts.collectAsStateWithLifecycle()
    val root = ProjectRepository.currentFolder(context)

    LaunchedEffect(Unit) { NativeEditor.restore(context.applicationContext) }

    // Follow the disk while on screen: one stat per open tab, so cheap enough to poll.
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                DiskSync.check(context.applicationContext)
                delay(1500)
            }
        }
    }

    fun openFile(path: String) {
        scope.launch { NativeEditor.open(context.applicationContext, path) }
    }

    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.background)) {
        val docked = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (treeOpen && docked) {
                FileTree(root, ::openFile, Modifier.width(260.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (open.isNotEmpty()) {
                    Tabs(open, active, unsaved, onSelect = ::openFile, onClose = NativeEditor::close)
                }
                if (findOpen) FindBar(onClose = { EditorChrome.findOpen.value = false })
                active?.takeIf { it in conflicts }?.let { path ->
                    ConflictBanner(
                        name = path.substringAfterLast('/'),
                        onKeepMine = { DiskSync.keepMine(path) },
                        onTakeTheirs = { scope.launch { DiskSync.takeTheirs(context.applicationContext, path) } },
                    )
                }
                if (problem != null && open.isNotEmpty()) {
                    Text(
                        problem!!,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> NativeEditor.acquire(ctx) },
                    )
                    if (open.isEmpty()) {
                        EmptyState(
                            onBrowse = { EditorChrome.treeOpen.value = true },
                            onGoTo = { EditorChrome.quickOpen.value = true },
                        )
                    }
                }
            }
        }
        if (treeOpen && !docked) {
            Row(Modifier.fillMaxSize()) {
                FileTree(
                    root,
                    onOpen = {
                        openFile(it)
                        EditorChrome.treeOpen.value = false
                    },
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.82f),
                )
                // The rest of the screen closes the drawer, as a scrim does.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                        .clickable { EditorChrome.treeOpen.value = false },
                )
            }
        }
        if (quickOpen) {
            QuickOpen(
                root,
                onOpen = {
                    openFile(it)
                    EditorChrome.quickOpen.value = false
                },
                onDismiss = { EditorChrome.quickOpen.value = false },
            )
        }
    }
}

@Composable
private fun Tabs(
    open: List<String>,
    active: String?,
    unsaved: Set<String>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()),
    ) {
        open.forEach { path ->
            val selected = path == active
            Row(
                Modifier
                    .background(if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(path) }
                    .heightIn(min = 40.dp)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    path.substringAfterLast('/'),
                    fontSize = 13.sp,
                    color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Unsaved shows as a dot where the close button sits, as VS Code does; it
                // lasts under a second with autosave, which is the point of showing it.
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { onClose(path) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (path in unsaved) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    } else {
                        Text("×", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onBrowse: () -> Unit, onGoTo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            KernIcons.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text("No file open", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.size(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onBrowse) { Text("Browse files") }
            OutlinedButton(onClick = onGoTo) { Text("Go to file") }
        }
    }
}

/** A tab whose file changed on disk while it held its own edits: the user decides. */
@Composable
private fun ConflictBanner(name: String, onKeepMine: () -> Unit, onTakeTheirs: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$name changed on disk",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onTakeTheirs) { Text("Reload", fontSize = 12.sp) }
        TextButton(onClick = onKeepMine) { Text("Keep mine", fontSize = 12.sp) }
    }
}
