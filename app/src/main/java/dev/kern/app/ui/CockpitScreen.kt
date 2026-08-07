package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.AgentRepository
import dev.kern.app.runtime.AppScope
import dev.kern.app.runtime.GitCommands
import dev.kern.app.runtime.ProjectRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The agent cockpit (M5): watch an agent work, steer it, review the diff and commit,
 * all one handed.
 *
 * The agent's output is a **real terminal**, the same emulator the terminal pane uses.
 * It used to be a list of lines, which is why TUI agents looked broken here: they do not
 * emit lines, they repaint a screen with cursor movement, and stripping escapes out of
 * that produces nonsense rather than text.
 *
 * The diff and commit half works with no agent at all, which is deliberate. Plenty of
 * people want to review and push from a phone without one anywhere near their code.
 */
@Composable
fun CockpitScreen(onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()

    val sessions by TerminalSessions.sessions.collectAsStateWithLifecycle()
    val agent = sessions.firstOrNull { it.kind == TerminalSessions.Kind.Agent }

    var status by remember { mutableStateOf<GitCommands.Status?>(null) }
    var diff by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDiff by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var commitMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    val project = remember(refresh) { ProjectRepository.currentFolder(context) }
    val command by AgentRepository.commandFlow(context).collectAsStateWithLifecycle()

    // Only the git side is polled now. The terminal repaints itself, so there is nothing
    // to poll for output, and every read here is a round trip into the guest.
    LaunchedEffect(showDiff, refresh) {
        while (true) {
            status = GitCommands.status(context, project)
            if (showDiff) diff = GitCommands.diff(context, project)
            delay(if (showDiff) 6000 else 4000)
        }
    }

    ScreenSurface {
        // Not the shared ScreenHeader: this bar carries a live dot and the branch where the
        // other screens carry a title. The padding matches it, so the headers still line up.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val running = agent != null
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (running) KernColors.Ok else MaterialTheme.colorScheme.secondary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "running" else "idle",
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
            onDismiss?.let { HeaderAction("close", it) }
        }

        // Start and stop, or say plainly that no agent is set.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                command.isBlank() -> Text(
                    "No agent set — settings > agent. The diff and commit tabs work without one.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                agent != null -> {
                    HeaderAction("stop") { TerminalSessions.stopAgent() }
                    Text(
                        command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Button(onClick = { TerminalSessions.startAgent(context, command) }) {
                    Text("Start $command", fontSize = 13.sp)
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
            if (showDiff) {
                DiffView(diff)
            } else if (agent != null) {
                TerminalSurface(agent, Modifier.fillMaxSize())
            } else {
                Text(
                    if (command.isBlank()) {
                        "Nothing running. The diff and commit tabs work on their own."
                    } else {
                        "Nothing running. Start $command above."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        (busy ?: result)?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        if (showDiff) {
            CommitBar(
                message = commitMsg,
                onMessage = { commitMsg = it },
                enabled = busy == null && status?.isRepo == true,
                onCommit = {
                    val text = commitMsg
                    busy = "Committing..."
                    // git runs on the app scope: a commit cancelled halfway leaves
                    // .git/index.lock behind, and every later git operation in that repo
                    // fails on it - including the workbench's SCM panel. Leaving the
                    // cockpit now only gives up on showing the result; the poll above
                    // picks the truth back up on return.
                    val work = AppScope.start { GitCommands.commitAll(app, project, text) }
                    scope.launch {
                        result = work.await()
                        busy = null
                        commitMsg = ""
                        refresh++
                    }
                },
                onPush = {
                    busy = "Pushing..."
                    val work = AppScope.start { GitCommands.push(app, project) }
                    scope.launch {
                        result = work.await()
                        busy = null
                        refresh++
                    }
                },
            )
        } else if (agent != null) {
            ReplyBar(
                value = reply,
                onValue = { reply = it },
                agentId = agent.id,
                onSend = {
                    TerminalSessions.writeTo(agent.id, reply)
                    reply = ""
                },
            )
        }
    }
}

@Composable
private fun ReplyBar(
    value: String,
    onValue: (String) -> Unit,
    agentId: Int,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            label = { Text("reply to agent", fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Addressed to the agent, not written into whatever happens to have focus.
            // The ambient write() resolves to the focused view or the active *shell* —
            // the active id is never set to an agent — so these answers went to a bash
            // prompt the user could not see, or nowhere at all, while the agent sat
            // waiting. Answering y/n one handed is the entire point of this screen.
            TextButton(onClick = { TerminalSessions.writeTo(agentId, "yes") }) { Text("yes") }
            TextButton(onClick = { TerminalSessions.writeTo(agentId, "no") }) { Text("no") }
            TextButton(onClick = { TerminalSessions.writeTo(agentId, "") }) {
                Text("enter", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Button(enabled = value.isNotBlank(), onClick = onSend) { Text("Send") }
        }
    }
}

@Composable
private fun CommitBar(
    message: String,
    onMessage: (String) -> Unit,
    enabled: Boolean,
    onCommit: () -> Unit,
    onPush: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = message,
            onValueChange = onMessage,
            singleLine = true,
            label = { Text("commit message", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && message.isNotBlank(), onClick = onCommit) {
                Text("Commit all")
            }
            TextButton(enabled = enabled, onClick = onPush) { Text("Push") }
        }
    }
}

/** Unified diff, coloured. The one format that stays readable at phone width. */
@Composable
private fun DiffView(lines: List<String>) {
    if (lines.isEmpty()) {
        Text(
            "No changes.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines) { line ->
            val colour = when {
                line.startsWith("+++") || line.startsWith("---") ->
                    MaterialTheme.colorScheme.onSurfaceVariant
                line.startsWith("@@") -> KernColors.DiffHunk
                line.startsWith("+") -> KernColors.Ok
                line.startsWith("-") -> MaterialTheme.colorScheme.error
                line.startsWith("diff ") -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                color = colour,
                maxLines = 3,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
