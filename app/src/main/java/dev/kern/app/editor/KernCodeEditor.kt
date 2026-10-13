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
 */
class KernCodeEditor(context: Context) : CodeEditor(context) {

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (text.length == 1 && text[0] in CLOSERS && stepOver(text[0])) return
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
