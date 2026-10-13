package dev.kern.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import dev.kern.app.core.AppScope
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The native editing surface: one sora-editor view for the whole process, and the files
 * open in it.
 *
 * Process scoped for the same reason as the workbench WebView: a fold or a recreation must
 * not lose the caret, the scroll position or the undo history. Each open file keeps its own
 * [Content], which carries its own undo stack, and switching tabs swaps the Content object
 * rather than the text - so undo survives a trip to another tab and back.
 *
 * Saving is automatic, a moment after typing stops. Android can kill this process at any
 * time without warning, and an editor that waits to be told to save loses work to it; the
 * workbench enforces `files.autoSave` for exactly that reason.
 */
@SuppressLint("StaticFieldLeak")
object NativeEditor {

    private const val AUTOSAVE_MS = 800L

    class OpenFile(val path: String, var content: Content) {
        val name: String get() = path.substringAfterLast('/')

        @Volatile
        var savedRevision: Long = 0
        var language: TextMateLanguage? = null

        /** The file's mtime when last read or written by us; see [DiskSync]. */
        @Volatile
        var diskStamp: Long = 0

        /** True while our own write is in flight, so [DiskSync] does not mistake it. */
        @Volatile
        var writing: Boolean = false

        /** Set when the user chose "keep mine" over an outside change. */
        @Volatile
        var forceNextSave: Boolean = false
    }

    internal val files = LinkedHashMap<String, OpenFile>()

    private val _open = MutableStateFlow<List<String>>(emptyList())
    val open: StateFlow<List<String>> = _open.asStateFlow()

    internal val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    /** Paths with edits not yet on disk. Usually empty for no longer than [AUTOSAVE_MS]. */
    internal val _unsaved = MutableStateFlow<Set<String>>(emptySet())
    val unsaved: StateFlow<Set<String>> = _unsaved.asStateFlow()

    /** Why the last open failed, for the pane to show; cleared by the next open. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var instance: CodeEditor? = null
    private var wrapper: MutableContextWrapper? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveAll() }

    fun acquire(activityContext: Context): CodeEditor {
        appContext = activityContext.applicationContext
        instance?.let { view ->
            wrapper?.baseContext = activityContext
            (view.parent as? ViewGroup)?.removeView(view)
            return view
        }
        val w = MutableContextWrapper(activityContext).also { wrapper = it }
        themed = false
        val view = KernCodeEditor(w).apply {
            typefaceText = Typeface.MONOSPACE
            setTextSize(12f)
            isWordwrap = true
            setLineNumberEnabled(true)
            setPinLineNumber(true)
            props.apply {
                // The soft keyboard's autocorrect turns identifiers into dictionary words.
                disallowSuggestions = true
                stickyScroll = true
                useICULibToSelectWords = true
            }
            subscribeAlways(ContentChangeEvent::class.java) { onEdited() }
        }
        instance = view
        _active.value?.let { show(it) }
        return view
    }

    fun current(): CodeEditor? = instance

    fun hasFocus(): Boolean = instance?.hasFocus() == true

    /** Open [path] (a guest path), or switch to it if it is already open. */
    suspend fun open(context: Context, path: String) {
        _problem.value = null
        files[path]?.let {
            withContext(Dispatchers.Main) { activate(path) }
            return
        }
        val read = withContext(Dispatchers.IO) { GuestFiles.read(context, path) }
        val text = when (read) {
            is GuestFiles.Read.Text -> read.content
            GuestFiles.Read.Binary -> return fail("${path.substringAfterLast('/')} is a binary file")
            GuestFiles.Read.Missing -> return fail("${path.substringAfterLast('/')} is not there any more")
            is GuestFiles.Read.TooLarge -> return fail(
                "${path.substringAfterLast('/')} is ${read.bytes / 1024 / 1024} MB - open it in the terminal",
            )
        }
        Grammars.ensureLoaded(context)
        val language = withContext(Dispatchers.IO) {
            Grammars.scopeFor(path.substringAfterLast('/'))?.let { scope ->
                runCatching { TextMateLanguage.create(scope, true) }.getOrNull()
            }
        }
        withContext(Dispatchers.Main) {
            val file = OpenFile(path, Content(text)).also { it.language = language }
            file.savedRevision = file.content.documentVersion
            file.diskStamp = GuestFiles.host(context, path).lastModified()
            files[path] = file
            publish()
            activate(path)
        }
    }

    private fun fail(message: String) {
        _problem.value = message
    }

    private fun activate(path: String) {
        _active.value = path
        show(path)
    }

    /**
     * Whether the view has the TextMate scheme yet. It cannot be built before [Grammars]
     * has loaded the theme - built earlier it is empty, and every token draws in the
     * default colour - so it is applied on the first file shown after loading.
     */
    private var themed = false

    internal fun show(path: String) {
        val view = instance ?: return
        val file = files[path] ?: return
        if (!themed && Grammars.isLoaded()) {
            runCatching { view.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance()) }
                .onSuccess { themed = true }
        }
        view.setEditorLanguage(file.language)
        view.setText(file.content, true, null)
    }

    fun close(path: String) {
        saveNow(path)
        files.remove(path)
        if (_active.value == path) {
            val next = files.keys.lastOrNull()
            _active.value = next
            if (next != null) show(next) else instance?.setText("")
        }
        publish()
    }

    /** Close tabs whose files are gone, after a delete or a rename in the tree. */
    fun forget(pathPrefix: String) {
        files.keys.filter { it == pathPrefix || it.startsWith("$pathPrefix/") }.forEach { close(it) }
    }

    private fun onEdited() {
        val path = _active.value ?: return
        val file = files[path] ?: return
        if (file.content.documentVersion != file.savedRevision) {
            _unsaved.value = _unsaved.value + path
            main.removeCallbacks(autosave)
            main.postDelayed(autosave, AUTOSAVE_MS)
        }
    }

    fun saveAll() {
        files.keys.toList().forEach { saveNow(it) }
    }

    internal fun saveNow(path: String) {
        val context = appContext ?: return
        val file = files[path] ?: return
        val version = file.content.documentVersion
        if (version == file.savedRevision) return
        val text = file.content.toString()
        file.savedRevision = version
        // Off the main thread, but on the app scope: a save must finish even if the
        // screen that triggered it has gone.
        AppScope.start {
            val host = GuestFiles.host(context, path)
            // Never over the top of a change made outside the editor - a git pull, a
            // terminal edit, an agent - since this tab last read the file. DiskSync shows
            // the conflict and lets the user choose.
            if (host.exists() && host.lastModified() != file.diskStamp && !file.forceNextSave) {
                DiskSync.conflict(path)
                return@start
            }
            file.forceNextSave = false
            file.writing = true
            val ok = GuestFiles.write(context, path, text)
            if (ok) file.diskStamp = host.lastModified()
            file.writing = false
            if (ok && files[path]?.savedRevision == version) {
                _unsaved.value = _unsaved.value - path
            }
        }
    }

    private fun publish() {
        _open.value = files.keys.toList()
        _unsaved.value = _unsaved.value.filter { it in files }.toSet()
    }

    // ---- commands the native chrome drives -----------------------------------

    fun undo() = instance?.takeIf { it.canUndo() }?.undo()

    fun redo() = instance?.takeIf { it.canRedo() }?.redo()

    fun sendKey(keyCode: Int, meta: Int = 0) {
        val view = instance ?: return
        val t = android.os.SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    fun type(text: String) {
        instance?.let { it.commitText(text) }
    }

    fun searcher(): EditorSearcher? = instance?.searcher

    fun setTextSize(sp: Float) {
        instance?.setTextSize(sp)
    }
}
