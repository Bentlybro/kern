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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kern.app.runtime.ProjectRepository
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
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<ProjectRepository.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var cloneUrl by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    // On by default: nearly every project wants one eventually, and starting a repo at
    // creation is the difference between having history and wishing you had.
    var initGit by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    /** One guest command at a time; both actions write to the same directory. */
    val busy = cloning || creating

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
                        creating = true
                        message = "Creating..."
                        scope.launch {
                            val outcome =
                                ProjectRepository.create(context, newName.trim(), initGit)
                            creating = false
                            when (outcome) {
                                is ProjectRepository.Outcome.Success -> {
                                    newName = ""
                                    message = "Created ${outcome.name}"
                                    reloadToken++
                                    open(outcome.path)
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
                        cloning = true
                        message = "Cloning..."
                        scope.launch {
                            val outcome = ProjectRepository.clone(context, cloneUrl.trim())
                            cloning = false
                            when (outcome) {
                                is ProjectRepository.Outcome.Success -> {
                                    cloneUrl = ""
                                    message = "Cloned ${outcome.name}"
                                    reloadToken++
                                    open(outcome.path)
                                }
                                is ProjectRepository.Outcome.Failure ->
                                    message = outcome.message
                            }
                        }
                    },
                ) { Text(if (cloning) "Cloning..." else "Clone") }

                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (it.startsWith("Cloned") || it.startsWith("Created")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
        Column {
            Text(name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                subtitle.removePrefix(ProjectRepository.HOME).ifEmpty { "~" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
