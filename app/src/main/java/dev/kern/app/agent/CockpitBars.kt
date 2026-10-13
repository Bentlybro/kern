package dev.kern.app.agent

import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.ui.KernColors
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReplyBar(
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
        // Addressed to the agent, not written into whatever happens to have focus.
        // The ambient write() resolves to the focused view or the active *shell* —
        // the active id is never set to an agent — so these answers went to a bash
        // prompt the user could not see, or nowhere at all, while the agent sat
        // waiting. Answering y/n one handed is the entire point of this screen.
        //
        // A scrollable row of its own, not squeezed beside Send: a TUI agent is driven
        // with more than yes and no — arrows walk its menus, esc backs out, ^C stops
        // it — and seven keys sharing a line with the send button left targets too
        // small to hit with a thumb.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickKey("yes") { TerminalSessions.writeTo(agentId, "yes") }
            QuickKey("no") { TerminalSessions.writeTo(agentId, "no") }
            QuickKey("enter") { TerminalSessions.writeTo(agentId, "") }
            // Raw, not writeTo: a TUI reads these as bytes, and the newline the
            // line-send appends would confirm whatever esc was meant to back out of.
            QuickKey("esc") { TerminalSessions.writeRawTo(agentId, "\u001B") }
            QuickKey("^C") { TerminalSessions.writeRawTo(agentId, "\u0003") }
            // Through the emulator, not as bytes: arrows encode differently in
            // normal and application cursor mode, and only the session knows which
            // is active. Menus in agent TUIs are vertical lists, so up and down
            // earn a place here; left and right stay in the terminal's key row.
            QuickKey("↑") { TerminalSessions.sendKeyTo(agentId, KeyEvent.KEYCODE_DPAD_UP) }
            QuickKey("↓") { TerminalSessions.sendKeyTo(agentId, KeyEvent.KEYCODE_DPAD_DOWN) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                label = { Text("reply to agent", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                modifier = Modifier.weight(1f),
            )
            Button(enabled = value.isNotBlank(), onClick = onSend) { Text("Send") }
        }
    }
}

@Composable
internal fun QuickKey(label: String, onTap: () -> Unit) {
    TextButton(onClick = onTap) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
internal fun CommitBar(
    message: String,
    onMessage: (String) -> Unit,
    enabled: Boolean,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
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
            // Multi-line on purpose: a subject plus a body is a normal commit message,
            // and a single-line field was quietly forbidding it. Capped so the field
            // cannot climb over the diff it is describing.
            singleLine = false,
            maxLines = 4,
            label = { Text("commit message", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && message.isNotBlank(), onClick = onCommit) {
                Text("Commit all")
            }
            TextButton(enabled = enabled, onClick = onPush) { Text("Push") }
            TextButton(enabled = enabled, onClick = onPull) { Text("Pull") }
        }
    }
}

/**
 * Who you are, before the first commit. Shown in place of the commit bar while git still
 * has setup's placeholder; signing in to GitHub fills it in automatically instead.
 */
@Composable
internal fun IdentityBar(onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Who should commits be from? Signing in to GitHub (settings) sets this for you.",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("name", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            singleLine = true,
            label = { Text("email", fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = name.isNotBlank() && '@' in email,
            onClick = { onSave(name.trim(), email.trim()) },
        ) { Text("Save") }
    }
}

/** Unified diff, coloured. The one format that stays readable at phone width. */
@Composable
internal fun DiffView(lines: List<String>) {
    if (lines.isEmpty()) {
        Text(
            "No changes.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    // A plain column under both scrolls, not a LazyColumn. Lazy items cannot share one
    // horizontal pan, and it was wrapping long lines to three and then cutting them —
    // silent truncation in the one surface that exists for reading changes. Code lines
    // pan rather than wrap because wrap destroys the indentation that makes a diff
    // scannable, and eager composition is affordable: GitCommands caps the diff at 400
    // lines before it gets here.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
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
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
