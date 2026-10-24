package dev.kern.app.settings

import dev.kern.app.editor.EditorSection
import dev.kern.app.ui.SectionTitle
import dev.kern.app.ui.Panel
import dev.kern.app.ui.KernIcons
import dev.kern.app.ui.HeaderIcon
import dev.kern.app.agent.AgentSection
import dev.kern.app.core.GuestPaths
import dev.kern.app.guest.GuestConfig
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.input.KeyRowSection
import dev.kern.app.ui.ScreenHeader
import dev.kern.app.ui.ScreenSurface
import dev.kern.app.update.UpdateSection
import dev.kern.app.update.Updates
import android.content.Context
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How the guest describes itself, e.g. "Ubuntu 26.04 LTS", or null if it cannot say.
 *
 * Read from the rootfs rather than by sourcing os-release in a guest shell: it is one small
 * file, and the PRoot spawn was the slow part of opening Settings. Ubuntu's is a symlink
 * into /usr/lib, which [GuestPaths] follows inside the guest.
 */
private suspend fun guestOsName(context: Context): String? = withContext(Dispatchers.IO) {
    val file = GuestPaths.resolve(LinuxRuntime.rootfsDir(context), "/etc/os-release")
        ?: return@withContext null
    runCatching { file.readLines() }.getOrNull()
        ?.firstOrNull { it.startsWith("PRETTY_NAME=") }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?.takeIf { it.isNotBlank() }
}

/**
 * Settings: what the Linux guest is using, how much it is allowed to use before the app
 * complains, and how to reclaim space or start over.
 */
@Composable
fun SettingsScreen(onDismiss: () -> Unit, onGuestDeleted: () -> Unit) {
    val context = LocalContext.current

    var guestOs by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) { guestOs = guestOsName(context) }

    ScreenSurface {
        ScreenHeader("Settings", onBack = onDismiss) {
            HeaderIcon(KernIcons.Refresh, "Refresh", { refresh++ })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Editor")
            Panel { EditorSection() }

            SectionTitle("GitHub")
            Panel { GitHubSection() }

            SectionTitle("Agent")
            Panel { AgentSection() }

            SectionTitle("Key row")
            Panel { KeyRowSection() }

            SectionTitle("Updates")
            Panel { UpdateSection() }

            SectionTitle("Linux")
            Panel {
                when (val name = guestOs) {
                    null -> Text(
                        "Checking...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        // An environment set up by an older build stays on the release it
                        // was built from; say so, rather than leaving the version looking
                        // like a bug.
                        if (!name.contains(GuestConfig.UBUNTU_RELEASE)) {
                            Text(
                                "New setups now use Ubuntu ${GuestConfig.UBUNTU_RELEASE}. " +
                                    "Existing environments are left alone, so this one stays " +
                                    "as it is - delete it below and run setup again to move " +
                                    "over. Push anything in ~/projects first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            StorageSection(refreshKey = refresh, onGuestDeleted = onGuestDeleted)

            Spacer(Modifier.height(24.dp))
        }
    }
}
