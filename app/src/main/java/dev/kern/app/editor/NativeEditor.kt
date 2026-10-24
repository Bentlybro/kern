package dev.kern.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
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
        val readOnly: Boolean get() = NativeEditor.isLibrary(path)

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

        /** Where the view was scrolled when this tab was last switched away from; see [show]. */
        var scrollX: Int = 0
        var scrollY: Int = 0
    }

    /** Library code - shown read-only; see [OpenFile.readOnly]. */
    fun isLibrary(path: String): Boolean = path.startsWith("/usr/") || "/node_modules/" in path

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
            beforeCommit = { beforeInsert(it) }
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
            is GuestFiles.Read.Text -> read
            GuestFiles.Read.Binary -> return fail("${path.substringAfterLast('/')} is a binary file")
            GuestFiles.Read.Missing -> return fail("${path.substringAfterLast('/')} is not there any more")
            is GuestFiles.Read.TooLarge -> return fail(
                "${path.substringAfterLast('/')} is ${read.bytes / 1024 / 1024} MB - open it in the terminal",
            )
        }
        // Built here, not on Main: splitting a few MB into lines is a visible stall, and a
        // Content belongs to no view until it is set on one.
        val content = withContext(Dispatchers.IO) { Content(text.content) }
        val (language, highlighted) = EditorLanguages.forPath(context, path, EditorLanguages.tooLargeToColour(text.content))
        withContext(Dispatchers.Main) {
            // Opened twice at once: the first one in is the tab, and may already be edited.
            if (path in files) {
                activate(path)
                return@withContext
            }
            val file = OpenFile(path, content).also {
                it.language = language
                it.highlighted = highlighted
            }
            file.savedRevision = file.content.documentVersion
            // The stamp from the read, not a fresh stat: the grammar can take seconds to
            // load on first open, and an outside write in that time would be taken for
            // this text and overwritten by the next autosave.
            file.diskStamp = text.stamp
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

    internal fun activate(path: String) {
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
        // Where the outgoing tab was scrolled to: setText puts the view back at the top,
        // and every trip to another tab and back used to lose the reader's place.
        shown?.let {
            it.scrollX = view.offsetX
            it.scrollY = view.offsetY
        }
        // Plain first, the tab's language only once its own text is in: setEditorLanguage
        // starts the new language's tokenizer on whatever text the view holds, and that was
        // the outgoing tab's - a minified file shown plain got the next tab's grammar run
        // over it, the crash plain text exists to avoid.
        view.setEditorLanguage(null)
        view.setText(file.content, true, null)
        view.setEditorLanguage(file.language)
        view.isEditable = !file.readOnly
        showProblem(file)
        shown = file
        restoreScroll(view, file)
    }

    /** The tab whose text the view holds, for [show] to keep its scroll position. Main thread. */
    private var shown: OpenFile? = null

    private fun restoreScroll(view: CodeEditor, file: OpenFile) {
        val x = file.scrollX
        val y = file.scrollY
        if (x == 0 && y == 0) return
        // After layout and twice, as for go to definition: until sora has laid out the text -
        // a long word-wrapped file on a background thread - there is not that far to scroll.
        // The second only if nobody has scrolled since the first.
        var landed: Pair<Int, Int>? = null
        val restore = Runnable {
            if (view.text !== file.content) return@Runnable
            if (landed?.let { it.first != view.offsetX || it.second != view.offsetY } == true) return@Runnable
            runCatching {
                val targetX = x.coerceIn(0, view.scrollMaxX)
                val targetY = y.coerceIn(0, view.scrollMaxY)
                val scroller = view.scroller
                scroller.startScroll(view.offsetX, view.offsetY, targetX - view.offsetX, targetY - view.offsetY, 0)
                scroller.abortAnimation()
                view.invalidate()
            }
            landed = view.offsetX to view.offsetY
        }
        view.post(restore)
        view.postDelayed(restore, 600)
    }

    /**
     * Close a tab - once its edits are on disk. A tab whose save failed or hit an outside
     * change stays open, since closing it would throw away the only copy of the edits;
     * [onUnsaved] is then told, to offer [discard], or without one the editor says why.
     */
    fun close(path: String, onUnsaved: ((String) -> Unit)? = null) {
        ui.launch {
            if (flush(listOf(path))) {
                drop(path)
            } else if (onUnsaved != null) {
                onUnsaved(path)
            } else {
                report("${path.substringAfterLast('/')} isn't saved, so it stays open")
            }
        }
    }

    /**
     * Close a tab without saving, on the user's say-so - the way out for a tab whose save
     * keeps failing, or whose outside change they do not want to settle. Main thread.
     */
    fun discard(path: String) {
        DiskSync.resolved(path)
        drop(path)
    }

    /**
     * Run [operation] - a rename of [path] to [renamedTo], or with null a delete - with the
     * tabs under it out of the way: saved, closed, and their in-flight writes finished, so
     * nothing can be typed into them or written back while the files move. Afterwards they
     * reopen at their new paths, or at their old ones if the operation failed.
     *
     * Returns the operation's result, or null without running it when a tab could not be
     * saved - closing it then would lose the edits.
     */
    suspend fun moving(context: Context, path: String, renamedTo: String?, operation: suspend () -> Boolean): Boolean? {
        val affected = withContext(Dispatchers.Main) { under(path) }
        val wasActive = _active.value?.takeIf { it in affected }
        flush(affected)
        val cleared = withContext(Dispatchers.Main) {
            // Checked and dropped in one step on Main, so no keystroke lands in between.
            if (affected.any { it in _unsaved.value }) return@withContext false
            drop(*affected.filter { it in files }.toTypedArray())
            true
        }
        if (!cleared) {
            report("${path.substringAfterLast('/')} has unsaved changes - resolve them first")
            return null
        }
        EditorSaves.settle(affected)
        val ok = operation()
        fun moved(p: String) = if (ok && renamedTo != null) renamedTo + p.removePrefix(path) else p
        if (!ok || renamedTo != null) {
            for (p in affected) open(context, moved(p))
            wasActive?.let { moved(it) }?.takeIf { it in files }?.let { withContext(Dispatchers.Main) { activate(it) } }
        }
        return ok
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

    internal fun drop(vararg paths: String) {
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

    /**
     * Drop colouring before an insert would take the tab past what it survives - a
     * minified blob pasted into a coloured file; see [EditorLanguages.tooLargeToColour].
     */
    private fun beforeInsert(text: CharSequence) {
        val view = instance ?: return
        val file = _active.value?.let { files[it] } ?: return
        if (file.language == null) return
        val line = file.content.getColumnCount(view.cursor.leftLine)
        if (!EditorLanguages.insertTooLargeToColour(file.content.length, line, text)) return
        file.language = null
        view.setEditorLanguage(null)
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

    // Kept per project across restarts; see EditorTabs.
    private fun remember() = EditorTabs.remember(appContext)

    /** Show [root]'s tabs, closing the last project's; see [EditorTabs.showProject]. */
    suspend fun showProject(context: Context, root: String) {
        appContext = context.applicationContext
        EditorTabs.showProject(context, root)
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

    /** Jump to the definition of the name under the caret; see [Definitions.goTo]. */
    suspend fun goToDefinition(context: Context): Boolean = Definitions.goTo(context)
}
