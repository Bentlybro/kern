package dev.kern.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kern.app.BuildConfig
import dev.kern.app.runtime.LinuxRuntime
import dev.kern.app.runtime.RootfsInstaller
import kotlinx.coroutines.delay

/**
 * The bug report, assembled by the app instead of asked for in an issue template.
 *
 * A phone has no adb and no second screen, so a user who cannot copy a failure cannot
 * report it and the bug simply never arrives. These are the facts that would have named
 * every failure this project has hit - device, ABIs, free space, and what setup was
 * actually doing - together with the installer log, which is otherwise thrown away when
 * the screen showing it goes.
 */
object Diagnostics {

    /**
     * Free bytes where the guest lives. Every caller wants the same filesystem: the
     * rootfs, the staged downloads and the app's own data all sit under it.
     */
    fun freeBytes(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)

    /**
     * Enough of the log to see what broke without pasting the installer's whole rolling
     * buffer of apt output into an issue. The tail is the useful end - failures are at
     * the bottom.
     */
    private const val LOG_TAIL = 100

    fun build(context: Context): String {
        val lines = mutableListOf<String>()
        lines += "Kern ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        lines += "ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}"
        lines += "Free space: ${megabytes(freeBytes(context))} MB"
        lines += "Guest: ${installState(context)}"
        lines += "Setup: ${stageLine()}"

        val log = RootfsInstaller.log.value
        if (log.isNotEmpty()) {
            val tail = log.takeLast(LOG_TAIL)
            lines += ""
            lines += "setup log (last ${tail.size} of ${log.size} lines):"
            lines += tail
        }
        return lines.joinToString("\n")
    }

    fun copy(context: Context): Boolean {
        // The platform clipboard rather than Compose's: LocalClipboardManager is
        // deprecated, and this is the same call the terminal already makes.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Kern diagnostics", build(context)))
        return true
    }

    /**
     * A failure message a stranger can act on.
     *
     * Setup reports `Throwable.message`, and for the network failures that string is a
     * hostname or the URL itself - true, and no help at all. Anything unrecognised falls
     * through untouched and the raw message is always kept, because a confident wrong
     * explanation costs more than an ugly right one.
     */
    fun explain(context: Context, message: String): String {
        val hint = network(message.lowercase()) ?: storage(context)
        return if (hint == null) message else "$hint\n\n$message"
    }

    private fun network(lower: String): String? {
        // HttpURLConnection's own exception for a 404 carries the URL as its whole message,
        // so a message that is nothing but a URL is that, however it reached here.
        val bareUrl = (lower.startsWith("https://") || lower.startsWith("http://")) &&
            " " !in lower

        return when {
            // Android's UnknownHostException reads "Unable to resolve host ..."; when an
            // exception carries no message at all, setup falls back to the class name.
            "unable to resolve host" in lower || "unknownhostexception" in lower ->
                "No connection - Kern could not reach the internet. Check Wi-Fi, then retry."

            // Something terminating TLS in the middle, which is ordinary on hotel and
            // office networks and looks nothing like a network problem from in here.
            "sslexception" in lower || "sslhandshake" in lower || "trust anchor" in lower ||
                "certification path" in lower ->
                "The connection was intercepted before it reached Ubuntu - usually a " +
                    "captive portal or a filtering proxy. Try another network."

            // "http 404" rather than "404": a byte count can be 404 too, and the message
            // for a transfer that stopped short counts bytes. The host decides the wording
            // because both downloads can 404 and only one of them is Ubuntu's.
            "http 404" in lower || bareUrl -> if ("cdimage" in lower) {
                "The Ubuntu image is no longer at the address this version of Kern knows. " +
                    "Update Kern - a newer release points at the current one."
            } else {
                "A file setup downloads is no longer at the address this version of Kern " +
                    "knows. Update Kern - a newer release points at the current one."
            }

            else -> null
        }
    }

    /**
     * Circumstantial, so it only speaks when there is plainly not enough left to have
     * finished, and only after the specific causes above have declined. tar and apt each
     * fail in their own words when the disk fills and none of those words say storage.
     * Stated as what is true rather than as the cause, which is not something a free-space
     * reading after the fact can actually know.
     */
    private fun storage(context: Context): String? {
        val freeMb = megabytes(freeBytes(context))
        if (freeMb >= RootfsInstaller.ESTIMATED_DOWNLOAD_MB) return null
        return "Only $freeMb MB of storage is free, which is not enough for setup to " +
            "finish. Free up space, then retry."
    }

    private fun installState(context: Context): String = when {
        LinuxRuntime.isReady(context) -> "installed, code-server present"
        LinuxRuntime.isInstalled(context) -> "installed, code-server missing"
        RootfsInstaller.hasCachedDownloads(context) -> "not installed, downloads cached"
        else -> "not installed"
    }

    private fun stageLine(): String = when (val stage = RootfsInstaller.stage.value) {
        is RootfsInstaller.Stage.Idle -> "idle"
        is RootfsInstaller.Stage.Downloading -> if (stage.total > 0) {
            "downloading ${stage.what}, ${megabytes(stage.bytes)} of ${megabytes(stage.total)} MB"
        } else {
            // A server that sent no Content-Length, so there is no total to report against.
            "downloading ${stage.what}, ${megabytes(stage.bytes)} MB"
        }
        is RootfsInstaller.Stage.Working -> stage.what
        is RootfsInstaller.Stage.Done -> "done"
        is RootfsInstaller.Stage.Failed -> "failed - ${stage.message}"
    }
}

/**
 * The one clipboard affordance in the app, so it looks the same wherever setup broke.
 *
 * The label answers back rather than a toast: minSdk is 29 and Android only shows its own
 * copy confirmation from 13, so on the older half of that range nothing else says it
 * worked, and on the newer half two confirmations read as an app unsure that it did.
 */
@Composable
fun CopyDiagnosticsButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    TextButton(
        onClick = { copied = Diagnostics.copy(context) },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(
            if (copied) "copied" else "copy diagnostics",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}
