package dev.kern.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import dev.kern.app.core.Prefs
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
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
        var language: io.github.rosemoe.sora.lang.Language? = null

        /** Library code reached by go-to-definition: shown, never edited or saved. */
        val readOnly: Boolean get() = path.startsWith("/usr/") || "/node_modules/" in path

        /** The file's mtime when last read or written by us; see [DiskSync]. */
        @Volatile
        var diskStamp: Long = 0

        /** The checker's complaint about the saved file, if any; see [SyntaxCheck]. */
        @Volatile
        var problem: SyntaxCheck.Problem? = null

        /** True while our own write is in flight, so [DiskSync] does not mistake it. */
        @Volatile
        var writing: Boolean = false

        /** Closed without saving: any write still queued for it must not land. */
        @Volatile
        var dropped: Boolean = false

        /** Whether [language] has its grammar yet; see [EditorLanguages.attach]. */
        var highlighted: Boolean = false

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
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            // Explicit params, not none: sora only allows scrolling past the last line when
            // the view has layout params, and without that scroll room the completion
            // window near the bottom of a file had nowhere to go and collapsed to a hairline
            // (caught on device).
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            typefaceText = Typeface.MONOSPACE
            setTextSize(EditorPrefs.fontSp(activityContext))
            isWordwrap = EditorPrefs.wrap(activityContext)
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
        val (language, highlighted) = EditorLanguages.forPath(context, path)
        withContext(Dispatchers.Main) {
            val file = OpenFile(path, Content(text)).also {
                it.language = language
                it.highlighted = highlighted
            }
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

    /** Show [message] over the editor until the next open or [clearReport]. */
    internal fun report(message: String) {
        _problem.value = message
    }

    /** Clear the shown message if it starts with [prefix]. */
    internal fun clearReport(prefix: String) {
        if (_problem.value?.startsWith(prefix) == true) _problem.value = null
    }

    private fun activate(path: String) {
        _active.value = path
        show(path)
        remember()
        if (focusNext) {
            focusNext = false
            instance?.let { it.requestFocus(); it.showSoftInput() }
        }
    }

    /**
     * Put the caret in the next file opened and raise the keyboard - for a file just
     * created, which is a file about to be typed in. Not for ordinary opens: a keyboard
     * nobody asked for covers half the code they opened the file to read.
     */
    @Volatile
    private var focusNext = false

    fun focusOnNextOpen() {
        focusNext = true
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
            runCatching {
                view.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance()).apply { kernAccents() }
            }.onSuccess { themed = true }
        }
        // Every time, not once: sora re-derives the scheme from the theme when the theme
        // registry fires, and that put the stock-blue handles back (caught on device).
        if (themed) view.colorScheme.kernAccents()
        view.setEditorLanguage(file.language)
        view.setText(file.content, true, null)
        view.isEditable = !file.readOnly
        showProblem(file)
    }

    /**
     * Close a tab - once its edits are on disk. A tab whose save failed or hit an outside
     * change stays open, since closing it would throw away the only copy of the edits.
     */
    fun close(path: String) {
        ui.launch {
            if (flush(listOf(path))) {
                drop(path)
            } else {
                report("${path.substringAfterLast('/')} isn't saved, so it stays open")
            }
        }
    }

    /**
     * Close tabs without saving - after a delete or rename in the tree, or when the user
     * took the disk's version. Main thread. Any write still queued for them is cancelled:
     * it would put the file back at a path that was just deleted or renamed away.
     */
    fun forget(pathPrefix: String) = drop(*under(pathPrefix).toTypedArray())

    /** Open tabs at [pathPrefix] or inside it. */
    fun under(pathPrefix: String): List<String> =
        files.keys.filter { it == pathPrefix || it.startsWith("$pathPrefix/") }

    private fun drop(vararg paths: String) {
        for (path in paths) {
            files.remove(path)?.dropped = true
            if (_active.value == path) _active.value = null
        }
        if (_active.value == null) {
            val next = files.keys.lastOrNull()
            _active.value = next
            if (next != null) show(next) else instance?.setText("")
        }
        publish()
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

    fun saveAll(): List<Job> = files.keys.toList().mapNotNull { saveNow(it) }

    /**
     * Save [paths] (every open file by default) and wait for the writes to land - before
     * anything reads the files from disk, like Run or the VS Code workbench. False if any
     * is still unsaved: a failed write, or an outside change waiting on the user.
     */
    suspend fun flush(paths: Collection<String>? = null): Boolean {
        repeat(3) {
            val (jobs, targets) = withContext(Dispatchers.Main) {
                val targets = paths?.filter { it in files } ?: files.keys.toList()
                targets.mapNotNull { saveNow(it) } to targets
            }
            jobs.joinAll()
            // Back on Main after the writes' own posts, so _unsaved is current. Retried:
            // typing during the write leaves a newer revision that is unsaved but fine.
            val left = withContext(Dispatchers.Main) { targets.filter { it in _unsaved.value } }
            if (left.isEmpty()) return true
            if (left.any { it in DiskSync.conflicts.value || files[it]?.dropped == true }) return false
        }
        return false
    }

    internal fun saveNow(path: String): Job? {
        val context = appContext ?: return null
        return EditorSaves.save(context, files[path] ?: return null)
    }

    internal fun showProblemIfActive(file: OpenFile) {
        if (_active.value == file.path) showProblem(file)
    }

    private fun publish() {
        _open.value = files.keys.toList()
        _unsaved.value = _unsaved.value.filter { it in files }.toSet()
        remember()
    }

    // ---- surviving a restart -------------------------------------------------
    //
    // Android ends this process whenever it likes, and an editor that comes back empty
    // makes every return to the app start with finding your files again. The tab list and
    // the active tab are kept in prefs and reopened on the next launch; the contents need
    // no keeping, since autosave already put them on disk.

    /** The project whose tabs are open. Each project keeps its own set. */
    private var project: String? = null

    private fun tabsKey(root: String) = Prefs.KEY_EDITOR_TABS + ":" + root
    private fun activeKey(root: String) = Prefs.KEY_EDITOR_ACTIVE + ":" + root

    private fun remember() {
        val context = appContext ?: return
        val root = project ?: return
        Prefs.of(context).edit()
            .putString(tabsKey(root), files.keys.joinToString("\n"))
            .putString(activeKey(root), _active.value)
            .apply()
    }

    /**
     * Show [root]'s tabs: the ones it had last time, reopened from disk. Switching project
     * saves the current tabs and closes them first - a file from another project left open
     * here is one autosave away from being edited by mistake.
     */
    suspend fun showProject(context: Context, root: String) {
        if (project == root) return
        appContext = context.applicationContext
        // Read before anything is closed: closing publishes, and publishing saves the tab
        // list - which would otherwise overwrite this project's saved tabs with nothing.
        val prefs = Prefs.of(context)
        val tabs = prefs.getString(tabsKey(root), null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        val active = prefs.getString(activeKey(root), null)
        // Language servers are rooted at the project they started in; the next request
        // starts them again at this one.
        if (project != null) LspIntel.stopAll()
        // Tabs that cannot be saved stay open into the new project rather than vanish.
        flush()
        withContext(Dispatchers.Main) {
            val kept = files.keys.filter { it in _unsaved.value }
            drop(*files.keys.filter { it !in kept }.toTypedArray())
            if (kept.isNotEmpty()) report("${kept.joinToString { it.substringAfterLast('/') }} couldn't be saved, so stayed open")
            project = root
            publish()
        }
        for (path in tabs) {
            if (GuestFiles.host(context, path).isFile) open(context, path)
        }
        if (active != null && active in files) withContext(Dispatchers.Main) { activate(active) }
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

    /**
     * Kern's accent on the editor's own chrome - selection handles, the caret, the
     * completion and text-action windows - so they read as part of the app rather than
     * sora's stock blue. The syntax colours stay VS Code's.
     */
    private fun EditorColorScheme.kernAccents() {
        val accent = 0xFFE2B15F.toInt()
        val panel = 0xFF1F2328.toInt()
        setColor(EditorColorScheme.SELECTION_HANDLE, accent)
        setColor(EditorColorScheme.SELECTION_INSERT, accent)
        setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, panel)
        setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, 0xFFE3E5E8.toInt())
        setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, panel)
        setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFF3A3F46.toInt())
        setColor(EditorColorScheme.COMPLETION_WND_TEXT_MATCHED, accent)
        setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFF2B3036.toInt())
    }

    private fun showProblem(file: OpenFile) {
        val view = instance ?: return
        view.diagnostics = SyntaxCheck.diagnostics(file.content, file.problem)
    }

    /**
     * Jump to where the name under the caret is defined - in this file, another file in the
     * project, or a library, through the file's [CodeIntel] provider. Returns whether it
     * found anywhere.
     */
    suspend fun goToDefinition(context: Context): Boolean {
        val path = _active.value ?: return false
        val provider = CodeIntel.forPath(path) ?: return false
        val (source, line, column) = withContext(Dispatchers.Main) {
            val view = instance ?: return@withContext null
            Triple(view.text.toString(), view.cursor.leftLine + 1, view.cursor.leftColumn)
        } ?: return false
        val target = withContext(Dispatchers.IO) { provider.definition(context, path, source, line, column) } ?: return false
        if (target.path != path) open(context, target.path)
        withContext(Dispatchers.Main) {
            val view = instance ?: return@withContext
            val l = (target.line - 1).coerceAtLeast(0)
            // After layout, not now: a file opened a moment ago has not been measured, and
            // scrolling against its unmeasured, word-wrapped layout landed hundreds of
            // lines short of the definition (caught on device: 1261 for line 1830).
            // Twice: sora builds a long file's word-wrapped layout on a background thread,
            // so the first scroll can still be measured against a partial layout. The second,
            // a moment later, lands on the finished one.
            val jump = Runnable {
                runCatching { view.setSelection(l, target.column, true) }
                view.ensurePositionVisible(l, target.column, true)
            }
            view.post(jump)
            view.postDelayed(jump, 600)
        }
        return true
    }
}
