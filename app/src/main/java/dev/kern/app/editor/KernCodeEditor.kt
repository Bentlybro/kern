package dev.kern.app.editor

import android.content.Context
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * sora's editor, with typing over closing brackets and quotes.
 *
 * sora auto-inserts the closing half of a pair but has no notion of typing over it, so
 * `print(42)` typed naturally came out `print(42))` - every bracket a user closed by hand
 * was doubled (caught on device). VS Code steps over the character instead, and so does
 * this: a closer typed with the same closer right after the caret moves past it.
 *
 * It also lets [beforeCommit] see text before it goes in - typed or pasted - for
 * [NativeEditor] to drop colouring ahead of an insert too large for it. Afterwards is too
 * late: sora hands the insert to the language's background tokenizer before any change
 * event is dispatched.
 */
class KernCodeEditor(context: Context) : CodeEditor(context) {

    var beforeCommit: ((CharSequence) -> Unit)? = null

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (text.length == 1 && text[0] in CLOSERS && stepOver(text[0])) return
        beforeCommit?.invoke(text)
        super.commitText(text, applyAutoIndent, applySymbolCompletion)
    }

    private fun stepOver(closer: Char): Boolean {
        val cursor = cursor
        if (cursor.isSelected) return false
        val line = cursor.leftLine
        val column = cursor.leftColumn
        val lineText = text.getLine(line)
        if (column >= lineText.length || lineText[column] != closer) return false
        setSelection(line, column + 1)
        return true
    }

    private companion object {
        val CLOSERS = setOf(')', ']', '}', '"', '\'', '`')
    }
}
