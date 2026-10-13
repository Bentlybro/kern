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
            if (!NativeEditor.flush()) return@start
            withContext(Dispatchers.Main) { EditorMode.set(app, EditorMode.Kind.Workbench) }
            if (file != null) WorkbenchFiles.open(app, file)
        }
    }
}
