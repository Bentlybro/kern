package dev.kern.app.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kern.app.runtime.AppScope
import dev.kern.app.runtime.ProjectRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Native project management (open a folder, clone a repo). Reads the guest Linux
 * filesystem directly through the runtime - no workbench involved.
 */
@Composable
fun ProjectsScreen(
    onOpenFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<ProjectRepository.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var cloneUrl by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf(false) }
    var cloneJob by remember { mutableStateOf<Job?>(null) }
    // git's progress lines arrive on the runtime's IO thread, so they land in a flow rather
    // than straight into Compose state.
    val cloneProgress = remember { MutableStateFlow<String?>(null) }
    val progressLine by cloneProgress.collectAsState()
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Which row has had its delete tapped once; a second tap on the same row does it.
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // On by default: nearly every project wants one eventually, and starting a repo at
    // creation is the difference between having history and wishing you had.
    var initGit by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    /** One guest command at a time; every action writes to the same directory. */
    val busy = cloning || creating || deleting

    val recents = remember(reloadToken) { ProjectRepository.recents(context) }

    LaunchedEffect(reloadToken) {
        loading = true
        entries = ProjectRepository.list(context)
        loading = false
    }

    fun open(path: String) {
        ProjectRepository.rememberOpened(context, path)
        onOpenFolder(path)
    }

    fun remove(name: String) {
        pendingDelete = null
        deleting = true
        message = "Deleting $name..."
        // App scope like the other two: an rm -rf of a big repository takes long enough
        // that leaving the screen would kill it partway through the tree.
        val work = AppScope.start { ProjectRepository.delete(app, name) }
        scope.launch {
            val outcome = work.await()
            deleting = false
            when (outcome) {
                is ProjectRepository.Outcome.Success -> {
                    message = "Deleted ${outcome.name}"
                    reloadToken++
                }
                is ProjectRepository.Outcome.Failure -> message = outcome.message
            }
        }
    }

    ScreenSurface {
        ScreenHeader("projects") {
            HeaderAction("close", onDismiss)
        }

        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it; message = null },
                singleLine = true,
                enabled = !busy,
                label = { Text("new project", fontSize = 12.sp) },
                placeholder = { Text("my-app", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = {
                        val name = newName.trim()
                        creating = true
                        message = "Creating..."
                        // The guest work goes on the app scope, not this screen's: leaving
                        // Projects mid-create killed git init and left the folder behind,
                        // and the retry then reported the name as taken. Only the reporting
                        // below dies with the screen.
                        val work = AppScope.start {
                            val outcome = ProjectRepository.create(app, name, initGit)
                            // Same shape as clone below: the screen only reports, so the
                            // consequence has to ride on the work or a posture change
                            // strands the new project unopened.
                            if (outcome is ProjectRepository.Outcome.Success) {
                                ProjectRepository.rememberOpened(app, outcome.path)
                                WorkbenchWebView.openFolder(outcome.path)
                            }
                            outcome
                        }
                        scope.launch {
                            val outcome = work.await()
                            creating = false
                            when (outcome) {
                                is ProjectRepository.Outcome.Success -> {
                                    newName = ""
                                    message = "Created ${outcome.name}"
                                    reloadToken++
                                    onDismiss()
                                }
                                is ProjectRepository.Outcome.Failure ->
                                    message = outcome.message
                            }
                        }
                    },
                ) { Text(if (creating) "Creating..." else "Create") }

                Checkbox(
                    checked = initGit,
                    onCheckedChange = { initGit = it },
                    enabled = !busy,
                )
                Text(
                    "git repo",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(2.dp))

            OutlinedTextField(
                value = cloneUrl,
                onValueChange = { cloneUrl = it; message = null },
                singleLine = true,
                enabled = !busy,
                label = { Text("git clone URL", fontSize = 12.sp) },
                placeholder = { Text("https://github.com/user/repo.git", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = cloneUrl.isNotBlank() && !busy,
                    onClick = {
                        val url = cloneUrl.trim()
                        cloning = true
                        message = "Cloning..."
                        cloneProgress.value = null
                        val work = AppScope.start {
                            val outcome = ProjectRepository.clone(
                                app,
                                url,
                                onProgress = { cloneProgress.value = it },
                            )
                            // Opening the result travels with the work, not with the
                            // screen. The reporting coroutine below dies whenever this
                            // screen does — and on a foldable it does without anyone
                            // navigating, because a posture change recreates the activity
                            // and `destination` resets to the editor. A clone that
                            // finished with no one listening used to open nothing: the
                            // repository existed, and the workbench never heard about it.
                            if (outcome is ProjectRepository.Outcome.Success) {
                                ProjectRepository.rememberOpened(app, outcome.path)
                                WorkbenchWebView.openFolder(outcome.path)
                            }
                            outcome
                        }
                        cloneJob = work
                        scope.launch {
                            // join, not await: cancelling the clone must not take this
                            // reporting coroutine down with it.
                            work.join()
                            cloning = false
                            cloneJob = null
                            cloneProgress.value = null
                            if (work.isCancelled) {
                                message = "Clone cancelled"
                            } else when (val outcome = work.await()) {
                                is ProjectRepository.Outcome.Success -> {
                                    cloneUrl = ""
                                    message = "Cloned ${outcome.name}"
                                    reloadToken++
                                    // The work already pointed the workbench at the new
                                    // folder; a second openFolder here would just reload
                                    // it. All that is left is to step out of the way.
                                    onDismiss()
                                }
                                is ProjectRepository.Outcome.Failure ->
                                    message = outcome.message
                            }
                        }
                    },
                ) { Text(if (cloning) "Cloning..." else "Clone") }

                if (cloning) {
                    TextButton(
                        onClick = {
                            // the repository takes the half-cloned folder back out, which
                            // is what makes the retry work rather than say it exists.
                            message = "Cancelling..."
                            cloneJob?.cancel()
                        },
                    ) { Text("Cancel") }
                }

                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            message?.let {
                val done = it.startsWith("Cloned") ||
                    it.startsWith("Created") ||
                    it.startsWith("Deleted")
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // git's own counters, so a clone that takes four minutes does not look stuck.
            progressLine?.let {
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (recents.isNotEmpty()) {
                item { SectionLabel("recent") }
                items(recents) { path ->
                    FolderRow(
                        name = path.substringAfterLast('/'),
                        subtitle = path,
                        isRepo = false,
                        onClick = { open(path) },
                    )
                }
            }

            item { SectionLabel("~/projects") }

            if (!loading && entries.isEmpty()) {
                item {
                    Text(
                        "No projects yet - create one or clone a repository above.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            items(entries) { entry ->
                FolderRow(
                    name = entry.name,
                    subtitle = entry.path,
                    isRepo = entry.isRepo,
                    onClick = { open(entry.path) },
                    trailing = {
                        if (pendingDelete == entry.name) {
                            // Ordered so that arming shifts the delete left and puts
                            // "cancel" under the finger that just tapped: a reflex second
                            // tap in the same spot backs out rather than deleting.
                            TextButton(
                                enabled = !busy,
                                onClick = { remove(entry.name) },
                            ) {
                                Text(
                                    "delete",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = { pendingDelete = null }) {
                                Text("cancel", fontSize = 12.sp)
                            }
                        } else {
                            TextButton(
                                enabled = !busy,
                                onClick = { pendingDelete = entry.name },
                            ) {
                                Text(
                                    "delete",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }

            item { SectionLabel("other") }
            item {
                FolderRow(
                    name = "home",
                    subtitle = ProjectRepository.HOME,
                    isRepo = false,
                    onClick = { open(ProjectRepository.HOME) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun FolderRow(
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
