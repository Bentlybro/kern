package dev.kern.app.editor

import android.content.Context
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Keeping open tabs honest about files that change underneath them.
 *
 * Kern is an IDE with a terminal and agents in it: `git pull`, a formatter, a code
 * generator or an agent will rewrite files that are open in the editor. Without this, the
 * tab kept its stale copy and the next autosave wrote it back - silently undoing whatever
 * changed on disk. Caught on device when a file deleted from a shell stayed open and would
 * have been recreated by the next keystroke.
 *
 * The rule is the one VS Code follows: an unchanged tab follows the disk; a tab with its own
 * edits never overwrites an outside change without the user saying so.
 */
object DiskSync {

    /** Paths whose disk copy changed while the tab held edits of its own. */
    private val _conflicts = MutableStateFlow<Set<String>>(emptySet())
    val conflicts: StateFlow<Set<String>> = _conflicts.asStateFlow()

    internal fun conflict(path: String) {
        _conflicts.value = _conflicts.value + path
    }

    /** [path]'s conflict no longer stands: its tab was closed without saving. */
    internal fun resolved(path: String) {
        _conflicts.value = _conflicts.value - path
    }

    /**
     * Compare every open tab with its file. Cheap - one stat per open file, no process -
     * so the pane calls it every second or two while it is on screen.
     */
    suspend fun check(context: Context) {
        val snapshot = withContext(Dispatchers.Main) { NativeEditor.files.values.toList() }
        for (file in snapshot) {
            if (file.writing) continue
            val host = GuestFiles.host(context, file.path)
            val edited = file.path in NativeEditor._unsaved.value
            if (!host.exists()) {
                if (edited) conflict(file.path) else withContext(Dispatchers.Main) { NativeEditor.forget(file.path) }
                continue
            }
            if (host.lastModified() == file.diskStamp) continue
            if (edited) {
                conflict(file.path)
            } else {
                reload(context, file)
            }
        }
    }

    /** Take the disk's version of [path], discarding the tab's edits. */
    suspend fun takeTheirs(context: Context, path: String) {
        val file = withContext(Dispatchers.Main) { NativeEditor.files[path] } ?: return
        _conflicts.value = _conflicts.value - path
        NativeEditor._unsaved.value = NativeEditor._unsaved.value - path
        if (!GuestFiles.host(context, path).exists()) {
            withContext(Dispatchers.Main) { NativeEditor.forget(path) }
            return
        }
        reload(context, file)
    }

    /** Write the tab's version over the outside change. */
    fun keepMine(path: String) {
        val file = NativeEditor.files[path] ?: return
        _conflicts.value = _conflicts.value - path
        file.forceNextSave = true
        file.savedRevision = -1
        NativeEditor.saveNow(path)
    }

    private suspend fun reload(context: Context, file: NativeEditor.OpenFile) {
        val before = withContext(Dispatchers.Main) { file.content.documentVersion }
        val read = withContext(Dispatchers.IO) { GuestFiles.read(context, file.path) } as? GuestFiles.Read.Text ?: return
        // The stamp that came with the text: a stat now could already see a newer write.
        val stamp = read.stamp
        val content = withContext(Dispatchers.IO) { Content(read.content) }
        val plain = withContext(Dispatchers.IO) { EditorLanguages.tooLargeToColour(read.content) }
        withContext(Dispatchers.Main) {
            // Typed into while the read was in flight, or closed: the disk's version must
            // not replace text it has never seen. That is a conflict for the user to settle.
            if (NativeEditor.files[file.path] !== file) return@withContext
            if (file.content.documentVersion != before) {
                conflict(file.path)
                return@withContext
            }
            file.content = content
            // Grown past what colouring survives - a formatter minifying it, say.
            if (plain) file.language = null
            file.savedRevision = file.content.documentVersion
            file.diskStamp = stamp
            if (NativeEditor.active.value == file.path) {
                // Keep the caret where it was: a reload under a reader's thumb should not
                // throw them back to line one.
                val view = NativeEditor.current()
                val line = view?.cursor?.leftLine ?: 0
                val column = view?.cursor?.leftColumn ?: 0
                NativeEditor.show(file.path)
                val lines = file.content.lineCount
                if (view != null && lines > 0) {
                    val l = line.coerceAtMost(lines - 1)
                    view.setSelection(l, column.coerceAtMost(file.content.getColumnCount(l)))
                }
            }
        }
    }
}
