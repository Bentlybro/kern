package dev.kern.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.kern.app.runtime.LinuxRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "Kern"

/**
 * Every terminal in the app, in one place.
 *
 * This replaced a single hardcoded session, which forced two unrelated limitations: you
 * could only ever have one shell, and the agent cockpit could not have a terminal at all,
 * so it rendered agent output as plain lines. That second one is why TUI agents looked
 * broken there. They do not emit lines; they repaint a screen with cursor movement, and a
 * list of strings cannot represent that no matter how carefully the escapes are stripped.
 *
 * Both fall out of the same fix. A session is a session, so the agent gets a real
 * emulator by being one of these rather than a special case.
 */
object TerminalSessions {

    /** How a session was started, which decides where it is shown and what runs in it. */
    enum class Kind { Shell, Agent }

    class Entry(
        val id: Int,
        val kind: Kind,
        val title: String,
        val view: TerminalView,
        val session: TerminalSession,
    )

    private val entries = mutableListOf<Entry>()
    private var nextId = 1

    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())
    val sessions: StateFlow<List<Entry>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<Int?>(null)
    val activeId: StateFlow<Int?> = _activeId.asStateFlow()

    private fun publish() {
        _sessions.value = entries.toList()
    }

    // ---- shells -------------------------------------------------------------

    /** The shells, in the order they were opened. Drives the tab strip. */
    fun shells(): List<Entry> = entries.filter { it.kind == Kind.Shell }

    /** The active shell, opening the first one if none exists yet. */
    fun activeShell(context: Context): Entry {
        val current = entries.firstOrNull { it.id == _activeId.value && it.kind == Kind.Shell }
        if (current != null) return current
        return shells().firstOrNull()?.also { _activeId.value = it.id }
            ?: openShell(context)
    }

    fun openShell(context: Context): Entry {
        val entry = create(context, Kind.Shell, null, "sh ${shells().size + 1}")
        _activeId.value = entry.id
        return entry
    }

    fun select(id: Int) {
        if (entries.any { it.id == id }) _activeId.value = id
    }

    /**
     * Close a shell and free its pty.
     *
     * The last one is not closable: an empty terminal pane with no way to get a shell
     * back is a dead end, and reopening one is what the plus button is for.
     */
    fun closeShell(id: Int) {
        if (shells().size <= 1) return
        val entry = entries.firstOrNull { it.id == id && it.kind == Kind.Shell } ?: return
        destroy(entry)
        if (_activeId.value == id) _activeId.value = shells().firstOrNull()?.id
        publish()
    }

    // ---- the agent ----------------------------------------------------------

    fun agent(): Entry? = entries.firstOrNull { it.kind == Kind.Agent }

    /** Start [command] in its own terminal. Replaces any agent already running. */
    fun startAgent(context: Context, command: String): Entry {
        agent()?.let { destroy(it) }
        return create(context, Kind.Agent, command, "agent")
    }

    fun stopAgent() {
        agent()?.let { destroy(it) }
        publish()
    }

    /**
     * What the agent's screen currently says, read straight from the emulator.
     *
     * Backs the "agent needs you" notification. Reading the rendered screen rather than a
     * stream of bytes is the only thing that works for a TUI: it repaints in place, so
     * the last thing *written* and the last thing *shown* are frequently different.
     */
    fun agentScreen(): String? = agent()?.let { entry ->
        runCatching { entry.session.emulator?.screen?.transcriptText }.getOrNull()
    }

    /**
     * Guess whether the agent is waiting on the user. Heuristic by necessity, since
     * agents do not announce it, but it only drives a notification so a wrong guess is
     * cheap.
     */
    fun agentAwaitingInput(screen: String): Boolean {
        val last = screen.lines().lastOrNull { it.isNotBlank() }?.trim() ?: return false
        return Regex(
            "(\\?\\s*$)|(\\[y/n\\])|(\\(y/N\\))|(yes/no)|(continue\\??)|(approve)|(permission)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(last)
    }

    // ---- lifecycle ----------------------------------------------------------

    private fun create(
        context: Context,
        kind: Kind,
        command: String?,
        title: String,
    ): Entry {
        val appContext = context.applicationContext

        val view = TerminalView(context, null).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setTextSize(spToPx(context, 13f))
            keepScreenOn = true
            isFocusableInTouchMode = true
        }
        view.setTerminalViewClient(KernTerminalViewClient(view))

        val id = nextId++
        val session = TerminalSession(
            { columns, rows ->
                if (command == null) {
                    // One tmux session per terminal, or they all attach to the same one.
                    LinuxRuntime.spawnShell(appContext, columns, rows, "kern-$id")
                } else {
                    LinuxRuntime.spawnCommand(appContext, command, columns, rows)
                }
            },
            2000,
            KernTerminalSessionClient(appContext, view),
        )
        view.attachSession(session)

        val entry = Entry(id, kind, title, view, session)
        entries += entry
        publish()
        return entry
    }

    private fun destroy(entry: Entry) {
        runCatching { (entry.view.parent as? ViewGroup)?.removeView(entry.view) }
        runCatching { entry.session.finishIfRunning() }
        entries.remove(entry)
    }

    /** Detach every view from its parent without ending anything. */
    fun detachAll() {
        entries.forEach { e -> (e.view.parent as? ViewGroup)?.removeView(e.view) }
    }

    // ---- input --------------------------------------------------------------

    private fun focused(): Entry? = entries.firstOrNull { it.view.hasFocus() }

    fun hasFocus(): Boolean = focused() != null

    fun current(): TerminalView? =
        focused()?.view ?: entries.firstOrNull { it.id == _activeId.value }?.view

    fun sendKey(keyCode: Int, meta: Int = 0) {
        val view = current() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    fun write(text: String) {
        val entry = focused() ?: entries.firstOrNull { it.id == _activeId.value } ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        entry.session.write(bytes, 0, bytes.size)
    }

    /** Send a line to a specific session, which is how the cockpit replies to an agent. */
    fun writeTo(id: Int, text: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        entry.session.write(bytes, 0, bytes.size)
    }

    fun isAttached(): Boolean = entries.isNotEmpty()

    fun isKeyboardVisible(): Boolean {
        val view = current() ?: return false
        return ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    fun toggleKeyboard() {
        val view = current() ?: return
        view.requestFocus()
        val controller = ViewCompat.getWindowInsetsController(view) ?: return
        if (isKeyboardVisible()) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun spToPx(context: Context, sp: Float): Int =
        (sp * context.resources.displayMetrics.scaledDensity).toInt()
}

private class KernTerminalViewClient(
    private val view: TerminalView,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1.0f

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Back closes the keyboard. It does not send ESC.
     *
     * Termux maps it to escape because on a phone there is often no other way to send
     * one. We have an `esc` key in the key row, so mapping it here bought nothing and
     * cost a great deal: back was swallowed by the terminal instead of dismissing the
     * keyboard, and the ESC it sent landed in whatever was running. Anything interactive
     * takes that as "quit", so putting the keyboard away killed what you were watching.
     */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = KeyRowState.ctrl

    override fun readAltKey(): Boolean = KeyRowState.alt

    override fun readShiftKey(): Boolean = KeyRowState.shift

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
        false

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }

    override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }

    override fun logInfo(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "terminal", e) }
}

private class KernTerminalSessionClient(
    private val appContext: Context,
    private val view: TerminalView,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        view.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.i(TAG, "terminal session finished")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString() ?: return
        TerminalSessions.write(text)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }

    override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }

    override fun logInfo(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "terminal", e) }
}

/** Shared modifier state so the key row can drive either the workbench or a terminal. */
object KeyRowState {
    var ctrl: Boolean = false
    var alt: Boolean = false
    var shift: Boolean = false

    fun clear() {
        ctrl = false
        alt = false
        shift = false
    }
}
