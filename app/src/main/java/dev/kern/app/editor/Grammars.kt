package dev.kern.app.editor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.kern.app.guest.LinuxRuntime
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.FileResolver
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Syntax highlighting for the native editor, from VS Code's own grammars and theme.
 *
 * Nothing is bundled. code-server is already installed in the guest, and its built-in
 * extensions carry every TextMate grammar, language configuration (brackets, comments,
 * auto-close) and colour theme VS Code ships - all plain JSON under one directory. Reading
 * them from there means the native editor colours a file exactly as the workbench does, and
 * picks up new languages whenever code-server is updated.
 *
 * tm4e's registry resolves `include`s only against grammars it already holds, so the whole
 * set is loaded once, off the main thread, before the first file is shown.
 */
object Grammars {

    private const val TAG = "Kern"
    private const val THEME = "dark_modern.json"

    private fun extensionsDir(context: Context) =
        File(LinuxRuntime.rootfsDir(context), "usr/lib/code-server/lib/vscode/extensions")

    private val byExtension = HashMap<String, String>()
    private val byFileName = HashMap<String, String>()

    private val loaded = CompletableDeferred<Boolean>()

    @Volatile
    private var started = false

    /** Load everything once. Safe to call repeatedly; later calls wait on the first. */
    suspend fun ensureLoaded(context: Context): Boolean {
        if (!started) {
            synchronized(this) {
                if (!started) {
                    started = true
                    val app = context.applicationContext
                    Thread({ loaded.complete(runCatching { load(app) }.getOrElse {
                        Log.w(TAG, "grammars: failed", it); false
                    }) }, "KernGrammars").start()
                }
            }
        }
        return withContext(Dispatchers.IO) { loaded.await() }
    }

    fun isLoaded(): Boolean = loaded.isCompleted

    /** The grammar scope for a file name, or null to show it as plain text. */
    fun scopeFor(fileName: String): String? {
        byFileName[fileName]?.let { return it }
        val lower = fileName.lowercase()
        // Longest extension first, so `.d.ts` beats `.ts` and `.tar.gz` is not mistaken.
        var dot = lower.indexOf('.')
        while (dot >= 0) {
            byExtension[lower.substring(dot)]?.let { return it }
            dot = lower.indexOf('.', dot + 1)
        }
        return null
    }

    private fun load(context: Context): Boolean {
        val started = SystemClock.elapsedRealtime()
        val root = extensionsDir(context)
        val extensions = root.listFiles()?.filter { it.isDirectory } ?: return false
        FileProviderRegistry.getInstance().addFileProvider(FileResolver.DEFAULT)

        val definitions = ArrayList<GrammarDefinition>()
        for (ext in extensions) {
            val manifest = File(ext, "package.json").takeIf { it.isFile } ?: continue
            val contributes = runCatching { JSONObject(manifest.readText()) }.getOrNull()
                ?.optJSONObject("contributes") ?: continue

            val configs = HashMap<String, String>()
            val languages = contributes.optJSONArray("languages") ?: JSONArray()
            val scopesByLanguage = HashMap<String, MutableList<String>>()
            for (i in 0 until languages.length()) {
                val lang = languages.optJSONObject(i) ?: continue
                val id = lang.optString("id").takeIf { it.isNotEmpty() } ?: continue
                lang.optString("configuration").takeIf { it.isNotEmpty() }?.let {
                    configs[id] = File(ext, it).absolutePath
                }
                scopesByLanguage.getOrPut(id) { mutableListOf() }
                lang.optJSONArray("extensions")?.let { arr ->
                    for (j in 0 until arr.length()) scopesByLanguage[id]!!.add("ext:" + arr.optString(j).lowercase())
                }
                lang.optJSONArray("filenames")?.let { arr ->
                    for (j in 0 until arr.length()) scopesByLanguage[id]!!.add("name:" + arr.optString(j))
                }
            }

            val grammars = contributes.optJSONArray("grammars") ?: continue
            for (i in 0 until grammars.length()) {
                val g = grammars.optJSONObject(i) ?: continue
                if (g.has("injectTo")) continue
                val scope = g.optString("scopeName").takeIf { it.isNotEmpty() } ?: continue
                val file = File(ext, g.optString("path")).takeIf { it.isFile } ?: continue
                val language = g.optString("language").takeIf { it.isNotEmpty() }
                val source = IGrammarSource.fromFile(file)
                // Named by scope, not language: the registry treats a second grammar with
                // a language name it has seen as already loaded.
                val base: DefaultGrammarDefinition = if (language != null && configs[language] != null) {
                    DefaultGrammarDefinition.withLanguageConfiguration(source, configs[language], scope, scope)
                } else {
                    DefaultGrammarDefinition.withGrammarSource(source, scope, scope)
                }
                val embedded = HashMap<String, String>()
                g.optJSONObject("embeddedLanguages")?.let { emb ->
                    emb.keys().forEach { k -> embedded[k] = emb.optString(k) }
                }
                definitions += if (embedded.isEmpty()) base else base.withEmbeddedLanguages(embedded)
                if (language != null) {
                    scopesByLanguage[language]?.forEach { key ->
                        when {
                            key.startsWith("ext:") -> byExtension.putIfAbsent(key.removePrefix("ext:"), scope)
                            key.startsWith("name:") -> byFileName.putIfAbsent(key.removePrefix("name:"), scope)
                        }
                    }
                }
            }
        }

        loadTheme(root)
        val registry = GrammarRegistry.getInstance()
        var ok = 0
        for (def in definitions) {
            // One at a time: a single malformed grammar must not cost every language.
            if (runCatching { registry.loadGrammar(def) }.isSuccess) ok++
        }
        Log.i(
            TAG,
            "grammars: $ok/${definitions.size} loaded, ${byExtension.size} extensions, " +
                "${SystemClock.elapsedRealtime() - started}ms",
        )
        return ok > 0
    }

    /**
     * VS Code's theme, with its `include` chain flattened: dark_modern includes dark_plus,
     * which includes dark_vs, and tm4e reads one document. Parents first, so the child's
     * token colours and editor colours win.
     */
    private fun loadTheme(extensionsRoot: File) {
        val start = File(extensionsRoot, "theme-defaults/themes/$THEME")
        val chain = ArrayList<JSONObject>()
        var file: File? = start
        while (file != null && file.isFile && chain.size < 8) {
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: break
            chain.add(0, json)
            file = json.optString("include").takeIf { it.isNotEmpty() }?.let { File(file!!.parentFile, it) }
        }
        if (chain.isEmpty()) return
        val colors = JSONObject()
        val tokenColors = JSONArray()
        for (theme in chain) {
            theme.optJSONObject("colors")?.let { c -> c.keys().forEach { colors.put(it, c.get(it)) } }
            theme.optJSONArray("tokenColors")?.let { arr ->
                for (i in 0 until arr.length()) tokenColors.put(arr.get(i))
            }
        }
        val merged = JSONObject()
            .put("name", "Kern Dark")
            .put("colors", colors)
            .put("tokenColors", tokenColors)
        val model = ThemeModel(IThemeSource.fromString(IThemeSource.ContentType.JSON, merged.toString()), "kern-dark")
        model.isDark = true
        runCatching {
            ThemeRegistry.getInstance().loadTheme(model)
            ThemeRegistry.getInstance().setTheme("kern-dark")
        }.onFailure { Log.w(TAG, "grammars: theme failed", it) }
    }
}
