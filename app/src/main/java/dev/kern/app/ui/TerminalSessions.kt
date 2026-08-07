package dev.kern.app.ui

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.kern.app.runtime.LinuxRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Kept so a session that ends can be replaced without a Context from the caller. */
    private val appContexts = mutableMapOf<Int, Context>()

    /**
     * A session's shell exited, usually because someone typed `exit`.
     *
     * The tab goes with it, and if that was the last shell a fresh one opens. Leaving a
     * dead terminal on screen with no way to start another is a corner you cannot get out
     * of without restarting the app, which is what used to happen.
     *
     * An agent that exits is simply gone: the cockpit then offers to start it again,
     * which is the right thing for something you chose to run rather than a shell you
     * always want available.
     */
    fun handleFinished(id: Int) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        val context = appContexts[id]
        val wasShell = entry.kind == Kind.Shell
        val wasActive = _activeId.value == id

        destroy(entry)
        appContexts.remove(id)

        if (wasShell && shells().isEmpty() && context != null) {
            openShell(context)
        } else if (wasActive) {
            _activeId.value = shells().firstOrNull()?.id
        }
        publish()
    }

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
        appContexts[id] = appContext
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
            KernTerminalSessionClient(appContext, view, id),
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

    fun isKeyboardVisible(): Boolean = current()?.imeVisible() == true

    fun toggleKeyboard() {
        current()?.toggleIme()
    }

    private fun spToPx(context: Context, sp: Float): Int =
        (sp * context.resources.displayMetrics.scaledDensity).toInt()
}
