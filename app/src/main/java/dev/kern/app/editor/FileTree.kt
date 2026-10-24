package dev.kern.app.editor

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import dev.kern.app.ui.KernIcons
import dev.kern.app.ui.HeaderIcon
import androidx.compose.material3.Icon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One visible row: a node and how deep it sits. */
private data class Row(val node: GuestFiles.Node, val depth: Int)

/**
 * The workspace as a native tree, read straight from the guest's filesystem.
 *
 * Expanding a folder is a directory listing in-process, not a round trip through the
 * workbench, so it keeps up with a thumb. Long-press any row for create, rename and delete;
 * long-press the root row to create at the top level.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTree(root: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(root) { mutableStateOf(setOf(root)) }
    var reload by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var menuFor by remember { mutableStateOf<GuestFiles.Node?>(null) }
    var prompt by remember { mutableStateOf<TreePrompt?>(null) }
    // Said here rather than over the editor: with no tab open, the editor shows nothing.
    var notice by remember { mutableStateOf<String?>(null) }
    val active by NativeEditor.active.collectAsStateWithLifecycle()

    LaunchedEffect(root, expanded, reload) {
        rows = withContext(Dispatchers.IO) { flatten(context, root, expanded) }
    }

    val rootNode = GuestFiles.Node(root.substringAfterLast('/').ifEmpty { "/" }, root, true)

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        // The project and what can be made in it, in plain sight: a long-press nobody
        // knows about is not a way to create a file.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                rootNode.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            HeaderIcon(KernIcons.Add, "New file", { prompt = TreePrompt.NewFile(rootNode) })
            HeaderIcon(KernIcons.Folder, "New folder", { prompt = TreePrompt.NewFolder(rootNode) })
            HeaderIcon(KernIcons.Refresh, "Refresh", { reload++ })
        }
        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().clickable { notice = null }.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (rows.isEmpty()) {
            Text(
                "Empty folder. Tap + to add a file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.node.path }) { row ->
                Box {
                    TreeRow(
                        node = row.node,
                        depth = row.depth,
                        isOpen = row.node.path in expanded,
                        selected = row.node.path == active,
                        onClick = {
                            if (row.node.isDirectory) {
                                expanded = if (row.node.path in expanded) expanded - row.node.path else expanded + row.node.path
                            } else {
                                onOpen(row.node.path)
                            }
                        },
                        onLong = { menuFor = row.node },
                    )
                    if (menuFor == row.node) {
                        NodeMenu(row.node, onDismiss = { menuFor = null }) { prompt = it; menuFor = null }
                    }
                }
            }
        }
        if (menuFor == rootNode) {
            NodeMenu(rootNode, onDismiss = { menuFor = null }) { prompt = it; menuFor = null }
        }
    }

    prompt?.let { p ->
        TreePromptDialog(p, onDismiss = { prompt = null }) { value ->
            prompt = null
            notice = null
            scope.launch {
                // A rename onto a name already taken would replace that file without a
                // word; refused here, where it can say why.
                if (p is TreePrompt.Rename) {
                    val target = p.target(value)
                    if (target == p.node.path) return@launch
                    if (withContext(Dispatchers.IO) { GuestFiles.exists(context, target) }) {
                        notice = "${value.trim()} already exists - pick another name"
                        return@launch
                    }
                }
                // Rename and delete move files out from under open tabs, so the tabs are
                // saved and closed for the duration; see NativeEditor.moving.
                val ok = if (p is TreePrompt.Rename || p is TreePrompt.Delete) {
                    NativeEditor.moving(context, p.node.path, (p as? TreePrompt.Rename)?.target(value)) {
                        withContext(Dispatchers.IO) { p.apply(context, value) }
                    }
                } else {
                    withContext(Dispatchers.IO) { p.apply(context, value) }
                }
                // null: refused over unsaved tabs, which already says so.
                if (ok == false) {
                    NativeEditor.report("${p.title.removeSuffix("?")} failed")
                    notice = "${p.title.removeSuffix("?")} failed"
                }
                if (p.node.isDirectory) expanded = expanded + p.node.path
                reload++
                // A file just made is a file about to be written in.
                if (p is TreePrompt.NewFile) {
                    NativeEditor.focusOnNextOpen()
                    onOpen("${TreePrompt.dirOf(p.node)}/${value.trim()}")
                }
            }
        }
    }
}

private fun flatten(context: android.content.Context, dir: String, expanded: Set<String>, depth: Int = 0): List<Row> =
    GuestFiles.list(context, dir).flatMap { node ->
        listOf(Row(node, depth)) +
            if (node.isDirectory && node.path in expanded) flatten(context, node.path, expanded, depth + 1) else emptyList()
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    node: GuestFiles.Node,
    depth: Int,
    isOpen: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                !node.isDirectory -> KernIcons.File
                isOpen -> KernIcons.ChevronDown
                else -> KernIcons.ChevronRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            node.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
            color = if (node.isDirectory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}

/** What a long-press offers. Everything except delete asks for a name first. */
private sealed class TreePrompt(val node: GuestFiles.Node, val title: String, val initial: String) {
    abstract fun apply(context: android.content.Context, value: String): Boolean

    class NewFile(node: GuestFiles.Node) : TreePrompt(node, "New file", "") {
        override fun apply(context: android.content.Context, value: String) =
            GuestFiles.createFile(context, "${dirOf(node)}/${value.trim()}")
    }

    class NewFolder(node: GuestFiles.Node) : TreePrompt(node, "New folder", "") {
        override fun apply(context: android.content.Context, value: String) =
            GuestFiles.createDirectory(context, "${dirOf(node)}/${value.trim()}")
    }

    class Rename(node: GuestFiles.Node) : TreePrompt(node, "Rename", node.name) {
        fun target(value: String) = "${node.path.substringBeforeLast('/')}/${value.trim()}"

        override fun apply(context: android.content.Context, value: String) =
            GuestFiles.rename(context, node.path, target(value))
    }

    class Delete(node: GuestFiles.Node) : TreePrompt(node, "Delete ${node.name}?", "") {
        override fun apply(context: android.content.Context, value: String) =
            GuestFiles.delete(context, node.path)
    }

    companion object {
        fun dirOf(node: GuestFiles.Node) = if (node.isDirectory) node.path else node.path.substringBeforeLast('/')
    }
}

@Composable
private fun NodeMenu(node: GuestFiles.Node, onDismiss: () -> Unit, onPick: (TreePrompt) -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("New file") }, onClick = { onPick(TreePrompt.NewFile(node)) })
        DropdownMenuItem(text = { Text("New folder") }, onClick = { onPick(TreePrompt.NewFolder(node)) })
        DropdownMenuItem(text = { Text("Rename") }, onClick = { onPick(TreePrompt.Rename(node)) })
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { onPick(TreePrompt.Delete(node)) },
        )
    }
}

@Composable
private fun TreePromptDialog(prompt: TreePrompt, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val asksName = prompt !is TreePrompt.Delete
    var value by remember { mutableStateOf(prompt.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            if (asksName) {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
            } else {
                Text("This cannot be undone.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !asksName || (value.isNotBlank() && '/' !in value),
                onClick = { onConfirm(value) },
            ) { Text(if (asksName) "OK" else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
