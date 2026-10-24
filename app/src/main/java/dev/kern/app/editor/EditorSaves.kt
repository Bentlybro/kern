package dev.kern.app.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.kern.app.core.AppScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writing the native editor's tabs to disk.
 *
 * One writer per file at a time, so an older revision can never land after a newer one,
 * and a revision counts as saved only once it is on disk: a failed write leaves the tab
 * unsaved, says so, and is tried again by the next edit or flush. The syntax check of
 * what was written runs outside the lock - it can take seconds, and nothing waiting on
 * the save should wait on it too.
 */
internal object EditorSaves {

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val main = Handler(Looper.getMainLooper())

    /** Start saving [file] if it has unsaved edits. Main thread. The job ends once the write has. */
    fun save(context: Context, file: NativeEditor.OpenFile): Job? {
        if (file.readOnly || file.dropped) return null
        val version = file.content.documentVersion
        if (version == file.savedRevision) return null
        // A shallow copy here - it shares the lines, and sora copies a shared line before
        // changing it - and the string built on the app scope: building it here put a copy
        // of the whole file on the main thread every time typing paused.
        val snapshot = file.content.copyText(false, true)
        // On the app scope: a save must finish even if the screen that triggered it has gone.
        return AppScope.start {
            val text = try {
                snapshot.toString()
            } finally {
                snapshot.release()
            }
            locks.getOrPut(file.path) { Mutex() }.withLock {
                if (write(context, file, version, text)) scheduleCheck(context, file, version)
            }
        }
    }

    private const val CHECK_DELAY_MS = 2_500L

    /** The pending or running syntax check per path; see [scheduleCheck]. */
    private val checks = ConcurrentHashMap<String, Job>()

    /**
     * Check what was just written, once saving has paused. Each check is a PRoot and an
     * interpreter, and one per autosave queued them up behind each other while typing;
     * only the last save of a burst is worth checking. Scheduled under the write lock, so
     * a newer save's check always replaces an older one's - cancelling it, running or not.
     */
    private fun scheduleCheck(context: Context, file: NativeEditor.OpenFile, version: Long) {
        val job = AppScope.start {
            delay(CHECK_DELAY_MS)
            check(context, file, version)
        }
        checks.put(file.path, job)?.cancel()
    }

    /**
     * Wait until no write to [paths] is in flight. Call after dropping their tabs: a write
     * that had already passed its dropped check then finishes before the caller moves or
     * deletes the files, rather than landing after and recreating them.
     */
    suspend fun settle(paths: Collection<String>) {
        for (path in paths) locks[path]?.withLock { }
    }

    private fun write(context: Context, file: NativeEditor.OpenFile, version: Long, text: String): Boolean {
        // Closed without saving, or a later save of this file already wrote newer text.
        if (file.dropped || file.savedRevision >= version) return false
        val host = GuestFiles.host(context, file.path)
        // Never over the top of a change made outside the editor - a git pull, a terminal
        // edit, an agent - since this tab last read the file. DiskSync shows the conflict
        // and lets the user choose.
        if (host.exists() && host.lastModified() != file.diskStamp && !file.forceNextSave) {
            DiskSync.conflict(file.path)
            return false
        }
        file.forceNextSave = false
        file.writing = true
        val stamp = GuestFiles.write(context, file.path, text)
        if (stamp != null) file.diskStamp = stamp
        val ok = stamp != null
        file.writing = false
        val failure = "Couldn't save ${file.name}"
        if (!ok) {
            NativeEditor.report("$failure - check free space")
            return false
        }
        file.savedRevision = version
        NativeEditor.clearReport(failure)
        main.post {
            if (file.content.documentVersion == version) NativeEditor._unsaved.value = NativeEditor._unsaved.value - file.path
        }
        return true
    }

    /** Underline the error in what was just saved, unless something newer was saved since. */
    private suspend fun check(context: Context, file: NativeEditor.OpenFile, version: Long) {
        val problem = SyntaxCheck.check(context, file.path)
        if (file.savedRevision != version) return
        file.problem = problem
        main.post { NativeEditor.showProblemIfActive(file) }
    }
}
