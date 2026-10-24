package dev.kern.app.editor

import android.content.Context
import dev.kern.app.core.AppScope
import dev.kern.app.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Surviving a restart: the native editor's tabs, per project.
 *
 * Android ends this process whenever it likes, and an editor that comes back empty makes
 * every return to the app start with finding your files again. The tab list and the
 * active tab are kept in prefs and reopened on the next launch; the contents need no
 * keeping, since autosave already put them on disk.
 */
internal object EditorTabs {

    /** The project whose tabs are open. Each project keeps its own set. */
    @Volatile
    private var project: String? = null

    private fun tabsKey(root: String) = Prefs.KEY_EDITOR_TABS + ":" + root
    private fun activeKey(root: String) = Prefs.KEY_EDITOR_ACTIVE + ":" + root

    /**
     * True while a project switch is closing one project's tabs and reopening another's.
     * Nothing is remembered meanwhile: the closing would otherwise save the old project's
     * list - still [project] at that point - as empty, and a half-finished reopening would
     * save the new one's as partial. Main thread.
     */
    private var restoring = false

    /** One project switch at a time; see [showProject]. */
    private val switching = Mutex()

    /** Keep the open tabs and the active one as [project]'s. Main thread. */
    fun remember(context: Context?) {
        if (restoring) return
        context ?: return
        val root = project ?: return
        Prefs.of(context).edit()
            .putString(tabsKey(root), NativeEditor.files.keys.joinToString("\n"))
            .putString(activeKey(root), NativeEditor._active.value)
            .apply()
    }

    /**
     * Show [root]'s tabs: the ones it had last time, reopened from disk. Switching project
     * saves the current tabs and closes them first - a file from another project left open
     * here is one autosave away from being edited by mistake.
     *
     * The switch runs on the app scope and always finishes. The pane calls this from a
     * composition effect, which is cancelled the moment the pane leaves the screen; cut off
     * between closing the old tabs and reopening the new ones, it left the new project with
     * whatever had opened so far and no retry, since [project] already named it.
     */
    suspend fun showProject(context: Context, root: String) {
        if (project == root) return
        val app = context.applicationContext
        AppScope.start { switching.withLock { switchTo(app, root) } }.await()
    }

    private suspend fun switchTo(context: Context, root: String) {
        if (project == root) return
        val prefs = Prefs.of(context)
        val tabs = prefs.getString(tabsKey(root), null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        val active = prefs.getString(activeKey(root), null)
        // Language servers are rooted at the project they started in; the next request
        // starts them again at this one.
        if (project != null) LspIntel.stopAll()
        // Tabs that cannot be saved stay open into the new project rather than vanish.
        NativeEditor.flush()
        withContext(Dispatchers.Main) {
            restoring = true
            val files = NativeEditor.files
            val kept = files.keys.filter { it in NativeEditor._unsaved.value }
            NativeEditor.drop(*files.keys.filter { it !in kept }.toTypedArray())
            if (kept.isNotEmpty()) {
                NativeEditor.report("${kept.joinToString { it.substringAfterLast('/') }} couldn't be saved, so stayed open")
            }
            project = root
        }
        try {
            for (path in tabs) {
                // One unreadable tab must not cost the rest of the list.
                runCatching { if (GuestFiles.host(context, path).isFile) NativeEditor.open(context, path) }
            }
            withContext(Dispatchers.Main) {
                if (active != null && active in NativeEditor.files) NativeEditor.activate(active)
            }
        } finally {
            // Remembered once, now the list is whole.
            withContext(NonCancellable + Dispatchers.Main) {
                restoring = false
                remember(context)
            }
        }
    }
}
