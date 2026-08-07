package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.AgentRepository

/**
 * Choose which CLI agent the cockpit runs, or none.
 *
 * None is the default and a perfectly good answer — plenty of people want an editor and a
 * terminal and nothing else. Kern does not assume otherwise, and the cockpit's diff and
 * commit tabs stay useful either way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSection() {
    val context = LocalContext.current
    val command by AgentRepository.commandFlow(context).collectAsStateWithLifecycle()

    fun choose(value: String) {
        AgentRepository.setCommand(context, value)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "The cockpit runs this command in your project and shows its output, so you " +
                "can steer it one-handed. Anything on PATH in the guest works; these are " +
                "just the common ones.",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Choice("none", command.isBlank()) { choose("") }
            AgentRepository.PRESETS.forEach { preset ->
                Choice(preset.label, command == preset.command) { choose(preset.command) }
            }
        }

        OutlinedTextField(
            value = command,
            onValueChange = { choose(it) },
            singleLine = true,
            label = { Text("command", fontSize = 12.sp) },
            placeholder = { Text("leave empty for no agent", fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (command.isNotBlank()) {
            Text(
                "Kern does not install agents or hold their credentials — install and sign " +
                    "in to $command in the terminal, once, and the cockpit will drive it.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
