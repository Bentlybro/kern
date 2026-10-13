package dev.kern.app.editor

import android.content.Context
import dev.kern.app.core.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which surface edits files: Kern's native editor, or the VS Code workbench.
 *
 * Native is the default. It opens instantly, edits with real Android selection handles and
 * IME, and needs nothing from code-server. The workbench stays one tap away for what only
 * it has - language servers, extensions, the debugger - and both edit the same files, so
 * switching is free at any moment.
 */
object EditorMode {

    enum class Kind { Native, Workbench }

    private val _kind = MutableStateFlow<Kind?>(null)

    fun flow(context: Context): StateFlow<Kind?> {
        if (_kind.value == null) _kind.value = read(context)
        return _kind.asStateFlow()
    }

    fun current(context: Context): Kind = _kind.value ?: read(context).also { _kind.value = it }

    fun isNative(context: Context): Boolean = current(context) == Kind.Native

    fun set(context: Context, kind: Kind) {
        Prefs.of(context).edit().putString(Prefs.KEY_EDITOR_MODE, kind.name).apply()
        _kind.value = kind
    }

    private fun read(context: Context): Kind =
        Prefs.of(context).getString(Prefs.KEY_EDITOR_MODE, null)
            ?.let { name -> Kind.entries.firstOrNull { it.name == name } }
            ?: Kind.Native
}
