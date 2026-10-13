package dev.kern.app.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * New project: a name, and whether to start a git repository. On by default - nearly every
 * project wants one eventually, and starting it now is the difference between having
 * history and wishing you had.
 */
@Composable
fun NewProjectDialog(model: ProjectsModel, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var git by remember { mutableStateOf(true) }
    val busy = model.busy
    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; model.message = null },
                    singleLine = true,
                    enabled = busy == null,
                    label = { Text("Name") },
                    placeholder = { Text("my-app") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckRow("Start a git repository", git, enabled = busy == null) { git = it }
                Status(busy, model.message)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && busy == null, onClick = { model.create(name.trim(), git, onCreated) }) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(enabled = busy == null, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Clone: a URL, and whether to take only the latest commit. Full by default - a shallow
 * clone shows one commit of history, which reads as a broken repository.
 */
@Composable
fun CloneDialog(model: ProjectsModel, onCloned: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var shallow by remember { mutableStateOf(false) }
    val busy = model.busy
    val progress by model.progress.collectAsState()
    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("Clone a repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; model.message = null },
                    singleLine = true,
                    enabled = busy == null,
                    label = { Text("URL") },
                    placeholder = { Text("https://github.com/user/repo") },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckRow("Latest commit only (faster)", shallow, enabled = busy == null) { shallow = it }
                Status(busy, model.message)
                // git's own counters, so a clone that takes four minutes does not look stuck.
                progress?.let {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (model.isCloning) {
                TextButton(onClick = { model.cancelClone() }) { Text("Stop") }
            } else {
                TextButton(enabled = url.isNotBlank() && busy == null, onClick = { model.clone(url.trim(), shallow, onCloned) }) {
                    Text("Clone")
                }
            }
        },
        dismissButton = { TextButton(enabled = busy == null, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What is happening now, or what went wrong last. */
@Composable
private fun Status(busy: String?, message: String?) {
    if (busy != null) {
        Text(busy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (message != null) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

