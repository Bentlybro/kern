package dev.kern.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kern.app.runtime.GuestConfig
import dev.kern.app.runtime.LinuxRuntime

/**
 * Settings: what the Linux guest is using, how much it is allowed to use before the app
 * complains, and how to reclaim space or start over.
 */
@Composable
fun SettingsScreen(onDismiss: () -> Unit, onGuestDeleted: () -> Unit) {
    val context = LocalContext.current

    var guestOs by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) { guestOs = LinuxRuntime.osPrettyName(context) }

    ScreenSurface {
        ScreenHeader("settings") {
            HeaderAction("refresh") { refresh++ }
            HeaderAction("close", onDismiss)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Updates")
            UpdateSection()

            SectionTitle("GitHub")
            GitHubSection()

            SectionTitle("Agent")
            AgentSection()

            SectionTitle("Linux")
            when (val name = guestOs) {
                null -> Text(
                    "Checking...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    // An environment set up by an older build stays on the release it
                    // was built from; say so, rather than leaving the version looking
                    // like a bug.
                    if (!name.contains(GuestConfig.UBUNTU_RELEASE)) {
                        Text(
                            "New setups now use Ubuntu ${GuestConfig.UBUNTU_RELEASE}. " +
                                "Existing environments are left alone, so this one stays " +
                                "as it is - delete it below and run setup again to move " +
                                "over. Push anything in ~/projects first.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            StorageSection(refreshKey = refresh, onGuestDeleted = onGuestDeleted)

            Spacer(Modifier.height(24.dp))
        }
    }
}

// internal, not private: the sections that live in their own files label themselves with it.
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text.lowercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
