package dev.kern.app.terminal

import android.os.SystemClock
import android.view.KeyEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * Getting input into terminals: keys, text, and lines to particular sessions.
 *
 * Split from [TerminalSessions], which owns the sessions; this is only how things are
 * typed into them. Extension functions, so every caller still writes
 * `TerminalSessions.write(...)`.
 */

/** The focused terminal, if one holds the keyboard. */
private fun TerminalSessions.focusedEntry(): TerminalSessions.Entry? =
    entries.firstOrNull { it.view.hasFocus() }

private fun TerminalSessions.byId(id: Int): TerminalSessions.Entry? =
    entries.firstOrNull { it.id == id }

private fun TerminalSessions.activeEntry(): TerminalSessions.Entry? =
    entries.firstOrNull { it.id == _activeId.value }

private fun TerminalSession.send(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    write(bytes, 0, bytes.size)
}

private fun TerminalView.press(keyCode: Int, meta: Int) {
    val now = SystemClock.uptimeMillis()
    dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
    dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
}

fun TerminalSessions.hasFocus(): Boolean = focusedEntry() != null

fun TerminalSessions.current(): TerminalView? = (focusedEntry() ?: activeEntry())?.view

fun TerminalSessions.sendKey(keyCode: Int, meta: Int = 0) {
    current()?.press(keyCode, meta)
}

fun TerminalSessions.write(text: String) {
    (focusedEntry() ?: activeEntry())?.session?.send(text)
}

/**
 * Input for sessions whose emulator does not exist yet - a shell opened a moment ago has
 * no process to write to until its view is laid out and the pty spawned. Flushed by
 * [flushPending] when the emulator arrives, so a command sent to a brand-new terminal is
 * run rather than dropped.
 */
private val pending = mutableMapOf<Int, String>()

/** Type [text] into the active shell, now or as soon as it can take input. */
fun TerminalSessions.writeToActive(text: String) {
    val entry = entries.firstOrNull { it.id == _activeId.value && it.kind == TerminalSessions.Kind.Shell }
        ?: shells().lastOrNull() ?: return
    if (entry.session.emulator == null) {
        pending[entry.id] = pending[entry.id].orEmpty() + text
        return
    }
    entry.session.send(text)
}

/** Called once a view's emulator exists; sends whatever was queued for its session. */
fun TerminalSessions.flushPending(view: TerminalView) {
    val entry = entries.firstOrNull { it.view === view } ?: return
    val text = pending.remove(entry.id) ?: return
    entry.session.send(text)
}

/** Send a line to a specific session, which is how the cockpit replies to an agent. */
fun TerminalSessions.writeTo(id: Int, text: String) {
    byId(id)?.session?.send(text + "\n")
}

/**
 * Send bytes exactly as given - no newline. A TUI agent reads Esc and Ctrl+C as single
 * bytes, and [writeTo]'s appended newline turns "cancel" into "cancel, and also confirm
 * whatever was highlighted".
 */
fun TerminalSessions.writeRawTo(id: Int, text: String) {
    byId(id)?.session?.send(text)
}

/**
 * Send a key through a session's own emulator rather than as fixed bytes.
 *
 * Arrows have two encodings and the guest chooses: normal mode wants `ESC [ A`,
 * application cursor mode (which python's REPL, vim and most TUIs switch on) wants
 * `ESC O A`. Only the emulator knows which mode the session is in, so hardcoded bytes are
 * right half the time - measured, not hypothetical: CSI arrows fell on the floor in python
 * 3.14's REPL. Dispatching to the view routes through [com.termux.terminal.KeyHandler],
 * which encodes per the live terminal state.
 */
fun TerminalSessions.sendKeyTo(id: Int, keyCode: Int) {
    byId(id)?.view?.press(keyCode, 0)
}
