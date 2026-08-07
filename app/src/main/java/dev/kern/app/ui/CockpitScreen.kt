package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kern.app.runtime.AgentRepository
import dev.kern.app.runtime.ProjectRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The agent cockpit (M5): monitor, steer and approve work one-handed.
 *
 * This is the surface the fold's cover screen exists for — the phone is demonstrably
 * good at reviewing and approving, and poor at typing, so the cockpit leads with agent
 * output, a unified diff, and one-thumb commit rather than an editor.
 */
@Composable
fun CockpitScreen(onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(AgentRepository.Snapshot(emptyList(), AgentRepository.State.Unknown)) }
    var status by remember { mutableStateOf<AgentRepository.GitStatus?>(null) }
    var diff by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDiff by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var commitMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    val project = remember(refresh) { ProjectRepository.currentFolder(context) }

    // Only one poller runs at a time. Every read is a Termux RUN_COMMAND round-trip, and
    // overlapping them starves whichever request the user is actually waiting on.
    LaunchedEffect(showDiff, refresh) {
        while (true) {
            if (showDiff) {
                diff = AgentRepository.gitDiff(context, project)
                status = AgentRepository.gitStatus(context, project)
                delay(6000)
            } else {
                snapshot = AgentRepository.snapshot(context, 40)
                status = AgentRepository.gitStatus(context, project)
                delay(4000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        // Header: state at a glance, in form as well as words.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (dot, label) = when (snapshot.state) {
                AgentRepository.State.AwaitingInput -> Color(0xFFC99A4E) to "needs you"
                AgentRepository.State.Working -> Color(0xFF6FAE7F) to "working"
                AgentRepository.State.Idle -> Color(0xFF8F929A) to "idle"
                AgentRepository.State.Unknown -> Color(0xFF8F929A) to "—"
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            status?.let {
                if (it.isRepo) {
                    Text(
                        "${it.branch} · ${it.changed} changed",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onDismiss?.let {
                TextButton(onClick = it) {
                    Text("close", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            Tab("output", !showDiff) { showDiff = false }
            Tab("diff", showDiff) { showDiff = true }
            TextButton(onClick = { refresh++ }) {
                Text("refresh", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Box(Modifier.weight(1f)) {
            if (showDiff) DiffView(diff) else OutputView(snapshot.tail)
        }

        // Steering: reply to the agent, or commit its work — the two one-thumb actions.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            busy?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            if (showDiff) {
                OutlinedTextField(
                    value = commitMsg,
                    onValueChange = { commitMsg = it },
                    singleLine = true,
                    label = { Text("commit message", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = commitMsg.isNotBlank() && busy == null,
                        onClick = {
                            busy = "Committing…"
                            scope.launch {
                                val r = AgentRepository.gitCommitAll(context, project, commitMsg)
                                commitMsg = ""
                                busy = r
                                refresh++
                                delay(4000)
                                busy = null
                            }
                        },
                    ) { Text("Commit all") }
                    TextButton(
                        enabled = busy == null,
                        onClick = {
                            busy = "Pushing…"
                            scope.launch {
                                busy = AgentRepository.gitPush(context, project)
                                delay(4000)
                                busy = null
                            }
                        },
                    ) { Text("Push") }
                }
            } else {
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    singleLine = true,
                    label = { Text("reply to agent", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickReply("yes") { scope.launch { AgentRepository.send(context, "yes") } }
                    QuickReply("no") { scope.launch { AgentRepository.send(context, "no") } }
                    QuickReply("↵") { scope.launch { AgentRepository.send(context, "") } }
                    Button(
                        enabled = reply.isNotBlank(),
                        onClick = {
                            val text = reply
                            reply = ""
                            scope.launch { AgentRepository.send(context, text) }
                        },
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun OutputView(tail: List<String>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
    ) {
        if (tail.isEmpty()) {
            item {
                Text(
                    "No session output yet. Start something in the terminal —\n" +
                        "it keeps running while the app is closed.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(tail) { line ->
            Text(
                line.ifBlank { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 3,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** Unified diff, coloured — split diffs are unreadable at phone width (docs/05). */
@Composable
private fun DiffView(diff: List<String>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
    ) {
        if (diff.isEmpty()) {
            item {
                Text(
                    "No changes in the working tree.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(diff) { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                line.startsWith("+") -> Color(0xFF6FAE7F)
                line.startsWith("-") -> Color(0xFFD07158)
                line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                line.startsWith("diff ") -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onBackground
            }
            Text(
                line.ifBlank { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                color = color,
            )
        }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickReply(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(44.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
