package dev.kern.app.projects

import dev.kern.app.editor.EditorMode
import dev.kern.app.editor.NativeEditor
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.workbench.WorkbenchFiles
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import dev.kern.app.core.copyCapped
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Files arriving from the rest of the phone: "Open with Kern" from a file manager, and
 * "Share to Kern" from anywhere that shares a file or a piece of text.
 *
 * Each one is copied into ~/inbox inside the guest and opened in the editor. Copied, not
 * edited in place, because a content URI is not a path the guest can see; the copy is an
 * ordinary file that git, the terminal and every tool in the guest can work on.
 */
object IncomingFiles {

    private const val TAG = "Kern"
    const val INBOX = "${ProjectRepository.HOME}/inbox"

    /**
     * Bumped each time a file has been handed to the editor, so the shell can step back to
     * the editor from Projects or Settings rather than opening it behind them.
     */
    private val _arrivals = kotlinx.coroutines.flow.MutableStateFlow(0)
    val arrivals: kotlinx.coroutines.flow.StateFlow<Int> = _arrivals

    /** Receive [intent] and open the result in the editor. Silent if it carried nothing. */
    suspend fun handle(context: Context, intent: Intent) {
        val path = receive(context, intent) ?: return
        _arrivals.value++
        // Into whichever editor is in use: the native one opens instantly, the workbench
        // once it has a connected window.
        if (EditorMode.isNative(context)) {
            NativeEditor.open(context, path)
        } else {
            WorkbenchFiles.open(context, path)
        }
    }

    /** Whether [intent] is one of ours to handle at all. */
    fun accepts(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW && intent.data != null ||
            intent?.action == Intent.ACTION_SEND

    /** Copy whatever [intent] carries into the inbox. Returns the guest path, or null. */
    suspend fun receive(context: Context, intent: Intent): String? = withContext(Dispatchers.IO) {
        if (!LinuxRuntime.isInstalled(context)) return@withContext null
        runCatching {
            val inbox = File(LinuxRuntime.rootfsDir(context), INBOX.removePrefix("/")).apply { mkdirs() }
            val stream = streamOf(intent)
            if (stream != null) {
                val name = unique(inbox, safeName(displayName(context, stream) ?: "shared-file"))
                val target = File(inbox, name)
                val copied = context.contentResolver.openInputStream(stream)?.use { src ->
                    target.outputStream().use { src.copyCapped(it, MAX_IMPORT_BYTES) }
                } ?: return@runCatching null
                // Any app can share to us; an endless stream must not fill the guest's disk.
                if (!copied) {
                    target.delete()
                    Log.w(TAG, "shared file over ${MAX_IMPORT_BYTES / 1024 / 1024} MB, not imported")
                    return@runCatching null
                }
                "$INBOX/$name"
            } else {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: return@runCatching null
                val name = unique(inbox, "shared-${System.currentTimeMillis() / 1000}.txt")
                File(inbox, name).writeText(text)
                "$INBOX/$name"
            }
        }.onFailure { Log.w(TAG, "could not import a shared file", it) }.getOrNull()
    }

    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    private fun streamOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        else -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** One path component: no separators, no leading dots, nothing empty. */
    internal fun safeName(raw: String): String =
        raw.replace(Regex("[/\\\\\u0000]"), "_").trim().trimStart('.').ifBlank { "shared-file" }

    /** `notes.txt`, then `notes-2.txt`, so a second share never overwrites the first. */
    private fun unique(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (File(dir, "$stem-$n$ext").exists()) n++
        return "$stem-$n$ext"
    }
}
