package dev.kern.app.editor

import android.content.Context
import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/** A source of completions and definitions for some file types. */
interface IntelProvider {
    fun warm(context: Context)
    fun complete(context: Context, path: String, source: String, line: Int, column: Int): List<IntelCompletion>
    fun definition(context: Context, path: String, source: String, line: Int, column: Int): IntelLocation?
}

/** Which provider, if any, understands a file. */
object CodeIntel {

    private val python = object : IntelProvider {
        override fun warm(context: Context) = PythonIntel.warm(context)
        override fun complete(context: Context, path: String, source: String, line: Int, column: Int) =
            PythonIntel.complete(context, path, source, line, column)
        override fun definition(context: Context, path: String, source: String, line: Int, column: Int) =
            PythonIntel.definition(context, path, source, line, column)
    }

    private val typescript = object : IntelProvider {
        override fun warm(context: Context) = TsIntel.warm(context)
        override fun complete(context: Context, path: String, source: String, line: Int, column: Int) =
            TsIntel.complete(context, path, source, line, column)
        override fun definition(context: Context, path: String, source: String, line: Int, column: Int) =
            TsIntel.definition(context, path, source, line, column)
    }

    fun forPath(path: String): IntelProvider? = when {
        path.endsWith(".py") || path.endsWith(".pyi") -> python
        TsIntel.handles(path) -> typescript
        LspIntel.handles(path) -> LspIntel.provider
        else -> null
    }
}

/**
 * A TextMate language with a provider's completions in place of words from the file.
 * Everything else - highlighting, brackets, indentation - is the TextMate language's, and
 * when the provider has nothing to say the word list still answers, so completion never
 * gets worse than it was.
 */
class IntelLanguage(
    private val base: Language,
    private val context: Context,
    private val path: String,
    private val provider: IntelProvider,
) : Language by base {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val source = content.reference.toString()
        val results = provider.complete(context, path, source, position.line + 1, position.column)
        if (results.isEmpty()) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        results.forEach { c ->
            publisher.addItem(SimpleCompletionItem(c.name, c.prefixLength, c.name).kind(kindOf(c.kind)).desc(label(c.kind)))
        }
    }

    /**
     * TypeScript calls a plain word from the file a "warning" - its name for a suggestion
     * it is not sure of - which on a completion row reads as an error.
     */
    private fun label(kind: String): String = if (kind == "warning") "word" else kind

    /** jedi's and TypeScript's kind names, onto sora's icons. */
    private fun kindOf(type: String): CompletionItemKind = when (type) {
        "function", "method", "local function" -> CompletionItemKind.Function
        "class", "interface", "type", "enum" -> CompletionItemKind.Class
        "module", "external module name", "script" -> CompletionItemKind.Module
        "keyword" -> CompletionItemKind.Keyword
        "property", "getter", "setter" -> CompletionItemKind.Property
        "param", "parameter", "statement", "instance", "var", "let", "const", "local var" -> CompletionItemKind.Variable
        else -> CompletionItemKind.Text
    }
}
