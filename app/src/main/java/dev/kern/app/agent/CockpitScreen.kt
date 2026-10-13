package dev.kern.app.agent

import dev.kern.app.ui.KernIcons
import dev.kern.app.ui.HeaderIcon
import dev.kern.app.core.runDetached
import dev.kern.app.git.GitCommands
import dev.kern.app.projects.ProjectRepository
import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.terminal.TerminalSurface
import dev.kern.app.ui.HeaderAction
import dev.kern.app.ui.KernColors
import dev.kern.app.ui.ScreenSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    //
    // repeatOnLifecycle rather than a bare LaunchedEffect, because a composition survives
    // the app being backgrounded and this loop is not free: every pass spawns a PRoot in
    // the guest to run git. Left running, a phone in a pocket with the cockpit open forked
    // one every four seconds indefinitely - against a phantom process killer that caps this
    // app at 32 children, and on a battery that has nothing to show for it, since there is
    // no screen to put the answer on. It resumes on its own when the app comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(showDiff, refresh, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                status = GitCommands.status(context, project)
                if (showDiff) diff = GitCommands.diff(context, project)
                // Each pass is a PRoot fork. On the output tab the only consumer is the
                // branch line in the header, which does not need refreshing every four
                // seconds while an agent is busy forking things of its own.
                delay(if (showDiff) 4000 else 15000)
            }
        }
    }

    // Asked once per visit to the diff tab: commits made before signing in to GitHub are
    // otherwise authored as setup's placeholder, which nobody notices until it is pushed.
    var identity by remember { mutableStateOf<GitCommands.Identity?>(null) }
    LaunchedEffect(showDiff) {
        if (showDiff) identity = GitCommands.identity(context)
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
            onDismiss?.let { HeaderIcon(KernIcons.Close, "Close", it) }
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
                    HeaderAction("Stop") { TerminalSessions.stopAgent() }
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

        if (showDiff && identity?.isPlaceholder == true) {
            IdentityBar(
                onSave = { name, email ->
                    busy = "Saving..."
                    scope.launch {
                        val ok = GitCommands.setIdentity(app, name, email)
                        busy = null
                        result = if (ok) "Commits will be authored as $name" else "Could not save"
                        if (ok) identity = GitCommands.Identity(name, email)
                    }
                },
            )
        } else if (showDiff) {
            // git runs on the app scope: a commit cancelled halfway leaves .git/index.lock
            // behind, and every later git operation in that repo fails on it - including
            // the workbench's SCM panel. Leaving the cockpit now only gives up on showing
            // the result; the poll above picks the truth back up on return.
            fun git(label: String, op: suspend () -> String, then: (String) -> Unit = {}) {
                busy = label
                scope.runDetached(op) { outcome ->
                    result = outcome
                    busy = null
                    then(outcome)
                    refresh++
                }
            }
            CommitBar(
                message = commitMsg,
                onMessage = { commitMsg = it },
                enabled = busy == null && status?.isRepo == true,
                onCommit = {
                    val text = commitMsg
                    git("Committing...", { GitCommands.commitAll(app, project, text) }) {
                        // Kept on failure: retyping a message on a phone keyboard because
                        // a hook or an empty tree refused it is not a fair price.
                        if (it.startsWith("Committed")) commitMsg = ""
                    }
                },
                onPush = { git("Pushing...", { GitCommands.push(app, project) }) },
                onPull = { git("Pulling...", { GitCommands.pull(app, project) }) },
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
