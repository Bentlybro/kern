package dev.kern.app.editor

import android.content.Context
import dev.kern.app.core.AppScope
import dev.kern.app.workbench.WorkbenchFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Moving from the native editor to the VS Code workbench, from the menu or from Settings. */
object EditorSwitch {

    /**
     * Save first, and switch only once the save has landed: the workbench reads from disk,
     * and switching over a failed save or an outside change would show the old file while
     * hiding the native editor's banner that explains it. Then open [file] there, if any,
     * so the switch lands where the user was.
     */
    fun toWorkbench(context: Context, file: String?) {
        val app = context.applicationContext
        AppScope.start {
            if (!NativeEditor.flush()) {
                // Named, since the tab holding things up may not be the one on screen.
                val names = withContext(Dispatchers.Main) { NativeEditor.unsaved.value.map { it.substringAfterLast('/') } }
                val verb = if (names.size == 1) "isn't" else "aren't"
                NativeEditor.report("Still in the native editor: ${names.joinToString()} $verb saved")
                return@start
            }
            withContext(Dispatchers.Main) { EditorMode.set(app, EditorMode.Kind.Workbench) }
            if (file != null) WorkbenchFiles.open(app, file)
        }
    }
}
