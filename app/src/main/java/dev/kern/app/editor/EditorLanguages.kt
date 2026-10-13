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
 */
internal object EditorLanguages {

    /** Set when a tab opened without its grammar; see [attach]. */
    @Volatile
    private var pending = false

    suspend fun forPath(context: Context, path: String): Pair<Language?, Boolean> {
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
        val bare = withContext(Dispatchers.Main) { NativeEditor.files.values.filter { !it.highlighted }.map { it.path } }
        for (path in bare) {
            val (language, loaded) = forPath(context, path)
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
