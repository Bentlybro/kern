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
        /**
         * Which shell this is, as opposed to which view: it names the tmux session, so it
         * has to outlive both a respawn and a closed tab or `new-session -A` never finds
         * the session it was meant to reattach to. Zero for the agent, which has no tmux.
         */
        val slot: Int = 0,
    )

    private val entries = mutableListOf<Entry>()
    private var nextId = 1

    /** Kept so a session that ends can be replaced without a Context from the caller. */
    private val appContexts = mutableMapOf<Int, Context>()

    /** Two deaths this close together are a spawn that fails, not someone typing `exit`. */
    private const val RESPAWN_WINDOW_MS = 5_000L
    private const val RESPAWN_LIMIT = 2

    private var respawns = 0
    private var lastShellDeath = 0L

    /**
     * A session's shell exited, usually because someone typed `exit`.
     *
     * The tab goes with it, and if that was the last shell a fresh one opens. Leaving a
     * dead terminal on screen with no way to start another is a corner you cannot get out
     * of without restarting the app, which is what used to happen.
     *
     * Reopening is capped. pty.c hands back a live master fd even when the exec behind it
     * never ran, so a guest the phone killed to free memory looks exactly like `exit` from
     * here - and reopen, recompose, lay out, spawn is a cycle that turns once a frame,
     * forking PRoot and allocating a 2000-row emulator and three threads each pass. Two
     * deaths inside [RESPAWN_WINDOW_MS] and it stops and waits to be asked again.
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
        val slot = entry.slot

        destroy(entry)

        if (wasShell && shells().isEmpty() && context != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            respawns = if (now - lastShellDeath < RESPAWN_WINDOW_MS) respawns + 1 else 1
            lastShellDeath = now
            if (respawns > RESPAWN_LIMIT) {
                _shellStuck.value = true
            } else {
                // the same slot on purpose: a fresh one each pass is a fresh tmux name
                // each pass, so there is never a session for `-A` to find its way back to.
                openShell(context, slot)
            }
        } else if (wasActive) {
            _activeId.value = shells().firstOrNull()?.id
        }
        publish()
    }

    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())
    val sessions: StateFlow<List<Entry>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<Int?>(null)
    val activeId: StateFlow<Int?> = _activeId.asStateFlow()

    /**
     * Shells whose tab was closed, by slot. tmux still has them, so a closed tab is
     * somewhere you can walk back into rather than a ten-minute build you threw away.
     */
    private val _detached = MutableStateFlow<List<Int>>(emptyList())
    val detached: StateFlow<List<Int>> = _detached.asStateFlow()

    /** A shell that will not stay up. The pane offers a retry rather than looping on it. */
    private val _shellStuck = MutableStateFlow(false)
    val shellStuck: StateFlow<Boolean> = _shellStuck.asStateFlow()

    private fun publish() {
        _sessions.value = entries.toList()
    }

    // ---- shells -------------------------------------------------------------

    /** The shells, in the order they were opened. Drives the tab strip. */
    fun shells(): List<Entry> = entries.filter { it.kind == Kind.Shell }

    fun openShell(context: Context): Entry = openShell(context, freeSlot())

    /**
     * Take a closed tab back. tmux kept the session running under the same name, so this
     * lands in the shell you left, output and all, rather than at a fresh prompt.
     */
    fun reattach(context: Context, slot: Int) {
        if (_detached.value.contains(slot)) openShell(context, slot)
    }

    /** Ask for a shell again after the respawn cap stopped it, counting from zero. */
    fun retryShell(context: Context) {
        respawns = 0
        openShell(context)
    }

    private fun openShell(context: Context, slot: Int): Entry {
        _shellStuck.value = false
        _detached.value = _detached.value - slot
        val entry = create(context, Kind.Shell, null, "sh $slot", slot)
        _activeId.value = entry.id
        return entry
    }

    /** The lowest number no live shell holds and no closed tab is keeping for later. */
    private fun freeSlot(): Int {
        val taken = shells().map { it.slot }.toSet() + _detached.value
        var slot = 1
        while (slot in taken) slot++
        return slot
    }

    fun select(id: Int) {
        if (entries.any { it.id == id }) _activeId.value = id
    }

    /**
     * Close a shell's tab without ending what it is running.
     *
     * The pty goes, the tmux session does not, so the slot moves to [detached] and one tap
     * brings the same shell back with its scrollback. Close used to be the one destructive
     * control in the app with no confirm and no undo, and the id it freed was never handed
     * out again, so whatever was still building in there could not be found from the UI.
     *
     * The last one keeps its tab: an empty terminal pane with no way to get a shell back
     * is a dead end, and reopening one is what the plus button is for.
     */
    fun closeShell(id: Int) {
        if (shells().size <= 1) return
        val entry = entries.firstOrNull { it.id == id && it.kind == Kind.Shell } ?: return
        destroy(entry)
        _detached.value = (_detached.value + entry.slot).sorted()
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
        slot: Int = 0,
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
                    // One tmux session per slot, or they all attach to the same one - and
                    // per slot rather than per view, so a respawned or reopened tab comes
                    // back to the session it had instead of naming a brand new one.
                    LinuxRuntime.spawnShell(appContext, columns, rows, "kern-$slot")
                } else {
                    LinuxRuntime.spawnCommand(appContext, command, columns, rows)
                }
            },
            2000,
            KernTerminalSessionClient(appContext, view, id),
        )
        view.attachSession(session)

        val entry = Entry(id, kind, title, view, session, slot)
        entries += entry
        publish()
        return entry
    }

    private fun destroy(entry: Entry) {
        runCatching { (entry.view.parent as? ViewGroup)?.removeView(entry.view) }
        runCatching { entry.session.finishIfRunning() }
        entries.remove(entry)
        appContexts.remove(entry.id)
    }

    /**
     * End every session, for a guest that is about to be deleted.
     *
     * These are process scoped and outlive the rootfs. A shell kept across a delete and
     * reinstall has its cwd on an unlinked directory while every new absolute path
     * resolves into the *fresh* guest - half in each, with nothing on screen saying so.
     * The closed tabs go with them: their tmux server went with the rootfs, so a "reopen"
     * would attach to nothing.
     *
     * Touches views, so main thread only.
     */
    fun destroyAll() {
        entries.toList().forEach { destroy(it) }
        _activeId.value = null
        _detached.value = emptyList()
        _shellStuck.value = false
        respawns = 0
        publish()
    }

    /** Detach every view from its parent without ending anything. */
    fun detachAll() {
        entries.forEach { e -> (e.view.parent as? ViewGroup)?.removeView(e.view) }
    }

    /**
     * Detach only the shells, for a surface that was only ever showing shells.
     *
     * The editor's teardown used to detach everything. Compose applies all of its changes
     * before it dispatches remember observers, so opening the cockpit runs the cockpit's
     * attach first and the editor's dispose second - and a running agent came up as an
     * empty box under a green "running" dot, which reads as a dead agent and gets it
     * stopped. Nothing but the cockpit ever shows the agent's view, so leaving it alone
     * here is the whole fix.
     */
    fun detachShells() {
        entries.forEach { e ->
            if (e.kind == Kind.Shell) (e.view.parent as? ViewGroup)?.removeView(e.view)
        }
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
