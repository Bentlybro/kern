package dev.kern.app.input

import android.content.Context
import android.view.KeyCharacterMap
import dev.kern.app.editor.EditorChrome
import dev.kern.app.editor.EditorMode
import dev.kern.app.editor.NativeEditor
import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.workbench.WorkbenchWebView

/**
 * Where the key row, hardware chords and the top bar's commands go.
 *
 * Three surfaces can hold the keyboard - a terminal, the native editor, the workbench - and
 * every control that types or edits used to pick between two of them inline, each copy with
 * its own idea of precedence. A terminal with focus always wins; otherwise the editor the
 * user has chosen does.
 */
object InputTargets {

    private enum class Target { Terminal, Native, Workbench }

    private fun target(context: Context): Target = when {
        TerminalSessions.hasFocus() -> Target.Terminal
        EditorMode.isNative(context) -> Target.Native
        else -> Target.Workbench
    }

    fun sendKey(context: Context, keyCode: Int, meta: Int) = when (target(context)) {
        Target.Terminal -> TerminalSessions.sendKey(keyCode, meta)
        Target.Native -> NativeEditor.sendKey(keyCode, meta)
        Target.Workbench -> WorkbenchWebView.sendKey(keyCode, meta)
    }

    /** Literal text, as typed - no key semantics, no modifiers. */
    fun type(context: Context, text: String) {
        when (target(context)) {
            Target.Terminal -> TerminalSessions.write(text)
            Target.Native -> NativeEditor.type(text)
            Target.Workbench -> {
                val wv = WorkbenchWebView.current() ?: return
                val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                map.getEvents(text.toCharArray())?.forEach { wv.dispatchKeyEvent(it) }
            }
        }
    }

    // Editor commands. These deliberately skip the terminal: Ctrl+Z at a shell is SIGTSTP,
    // and "save" means the file, whatever has focus.

    fun undo(context: Context) {
        if (EditorMode.isNative(context)) NativeEditor.undo() else WorkbenchWebView.Commands.undo()
    }

    fun redo(context: Context) {
        if (EditorMode.isNative(context)) NativeEditor.redo() else WorkbenchWebView.Commands.redo()
    }

    fun save(context: Context) {
        if (EditorMode.isNative(context)) NativeEditor.saveAll() else WorkbenchWebView.Commands.save()
    }

    fun find(context: Context) {
        if (EditorMode.isNative(context)) EditorChrome.toggleFind() else WorkbenchWebView.Commands.find()
    }

    fun toggleFiles(context: Context) {
        if (EditorMode.isNative(context)) EditorChrome.toggleTree() else WorkbenchWebView.Commands.toggleSidebar()
    }
}
