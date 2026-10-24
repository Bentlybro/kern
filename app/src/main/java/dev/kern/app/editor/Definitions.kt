package dev.kern.app.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Go to definition in the native editor, through the file's [CodeIntel] provider. */
internal object Definitions {

    /**
     * Jump to where the name under the caret is defined - in this file, another file in the
     * project, or a library. Returns whether it found anywhere.
     */
    suspend fun goTo(context: Context): Boolean {
        val path = NativeEditor._active.value ?: return false
        val provider = CodeIntel.forPath(path) ?: return false
        val (source, line, column) = withContext(Dispatchers.Main) {
            val view = NativeEditor.current() ?: return@withContext null
            Triple(view.text.toString(), view.cursor.leftLine + 1, view.cursor.leftColumn)
        } ?: return false
        val target = withContext(Dispatchers.IO) { provider.definition(context, path, source, line, column) } ?: return false
        if (target.path != path) NativeEditor.open(context, target.path)
        withContext(Dispatchers.Main) {
            // The open can fail - a binary, a file gone, one too large - and the jump would
            // then land at that line in whatever tab was showing. The open says why.
            if (NativeEditor._active.value != target.path) return@withContext
            val view = NativeEditor.current() ?: return@withContext
            val content = NativeEditor.files[target.path]?.content ?: return@withContext
            // After layout, not now: a file opened a moment ago has not been measured, and
            // scrolling against its unmeasured, word-wrapped layout landed hundreds of
            // lines short of the definition (caught on device: 1261 for line 1830).
            // Twice: sora builds a long file's word-wrapped layout on a background thread,
            // so the first scroll can still be measured against a partial layout. The second,
            // a moment later, lands on the finished one.
            val jump = Runnable {
                // Checked again as it runs: the second comes 600 ms later, maybe in another
                // tab by then. Clamped, because sora throws for a position past the end -
                // a definition from a stale index can name one - and thrown from a posted
                // Runnable, that is a crash.
                if (NativeEditor._active.value != target.path || view.text !== content) return@Runnable
                val l = (target.line - 1).coerceIn(0, maxOf(content.lineCount - 1, 0))
                val c = target.column.coerceIn(0, content.getColumnCount(l))
                runCatching {
                    view.setSelection(l, c, true)
                    view.ensurePositionVisible(l, c, true)
                }
            }
            view.post(jump)
            view.postDelayed(jump, 600)
        }
        return true
    }
}
