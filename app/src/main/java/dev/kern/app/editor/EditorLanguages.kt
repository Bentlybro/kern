package dev.kern.app.editor

import android.content.Context
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sora [Language] for a tab: VS Code's TextMate grammar for colour, with a
 * [CodeIntel] provider's completions on top.
 *
 * The two are independent. The grammars come from code-server, which on first run is
 * still installing when the editor opens; completion must not wait for it. A tab opened
 * before the grammars exist gets intelligence at once and colour later, from [attach].
 *
 * A file too large to colour safely - see [tooLargeToColour] - is shown as plain text.
 */
internal object EditorLanguages {

    /** Longer lines than this are not tokenized; VS Code stops at 20,000 on a desktop. */
    private const val MAX_COLOURED_LINE = 10_000

    /** Larger files than this, in characters, are not coloured at all. */
    private const val MAX_COLOURED_CHARS = 1_000_000

    /**
     * Whether [text] is past what TextMate colouring survives on a phone. sora tokenizes
     * every line on a background thread that catches exceptions but not errors, and a
     * minified JSON file - one line of megabytes, a token every few characters - can run
     * it out of memory, and that takes the whole app down. VS Code makes the same call:
     * very long lines and very large files go uncoloured. Internal for tests.
     */
    internal fun tooLargeToColour(text: CharSequence): Boolean {
        if (text.length > MAX_COLOURED_CHARS) return true
        var lineStart = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                if (i - lineStart > MAX_COLOURED_LINE) return true
                lineStart = i + 1
            }
        }
        return text.length - lineStart > MAX_COLOURED_LINE
    }

    /** Set when a tab opened without its grammar; see [attach]. */
    @Volatile
    private var pending = false

    /**
     * The language for [path], and whether the grammars were ready for it. A [plain] file
     * - one [tooLargeToColour] - gets neither colour nor intelligence, which would only
     * be handed the same megabytes on every completion.
     */
    suspend fun forPath(context: Context, path: String, plain: Boolean): Pair<Language?, Boolean> {
        if (plain) return null to true
        val loaded = Grammars.ensureLoaded(context)
        if (!loaded) pending = true
        return withContext(Dispatchers.IO) {
            val grammar = if (loaded) {
                Grammars.scopeFor(path.substringAfterLast('/'))?.let { scope ->
                    runCatching { TextMateLanguage.create(scope, true) }.getOrNull()
                }
            } else {
                null
            }
            val language = CodeIntel.forPath(path)?.let { provider ->
                provider.warm(context, path)
                IntelLanguage(grammar ?: EmptyLanguage(), context.applicationContext, path, provider)
            } ?: grammar
            language to loaded
        }
    }

    /**
     * Give colour to tabs opened before the grammars existed - on first run, files opened
     * while code-server was still installing. Cheap when there is nothing to do.
     */
    suspend fun attach(context: Context) {
        if (!pending || !Grammars.ensureLoaded(context)) return
        pending = false
        val bare = withContext(Dispatchers.Main) {
            NativeEditor.files.values.filter { !it.highlighted }.map { it.path to tooLargeToColour(it.content.toString()) }
        }
        for ((path, plain) in bare) {
            val (language, loaded) = forPath(context, path, plain)
            if (!loaded) continue
            withContext(Dispatchers.Main) {
                NativeEditor.files[path]?.let {
                    it.language = language
                    it.highlighted = true
                }
                if (NativeEditor.active.value == path) NativeEditor.show(path)
            }
        }
    }
}
