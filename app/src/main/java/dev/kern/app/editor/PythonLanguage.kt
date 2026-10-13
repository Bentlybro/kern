package dev.kern.app.editor

import android.content.Context
import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/**
 * The TextMate language for Python, with jedi's completions in place of words from the
 * file. Everything else - highlighting, brackets, indentation - is the TextMate language's.
 *
 * Falls back to the word list when jedi has nothing to say, so completion never gets
 * worse than it was.
 */
class PythonLanguage(
    private val base: Language,
    private val context: Context,
    private val path: String,
) : Language by base {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val source = content.reference.toString()
        val results = PythonIntel.complete(context, path, source, position.line + 1, position.column)
        if (results.isEmpty()) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        results.forEach { c ->
            publisher.addItem(
                SimpleCompletionItem(c.name, c.prefixLength, c.name)
                    .kind(kindOf(c.kind))
                    .desc(c.kind),
            )
        }
    }

    private fun kindOf(type: String): CompletionItemKind = when (type) {
        "function" -> CompletionItemKind.Function
        "class" -> CompletionItemKind.Class
        "module" -> CompletionItemKind.Module
        "keyword" -> CompletionItemKind.Keyword
        "property" -> CompletionItemKind.Property
        "param" -> CompletionItemKind.Variable
        "statement", "instance" -> CompletionItemKind.Variable
        else -> CompletionItemKind.Text
    }
}
