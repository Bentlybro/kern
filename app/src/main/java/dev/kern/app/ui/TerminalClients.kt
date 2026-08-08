package dev.kern.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "Kern"

internal class KernTerminalViewClient(
    private val view: TerminalView,
) : TerminalViewClient {

    /**
     * Pinch changes the terminal text size, the way every terminal app on Android has
     * taught fingers to expect. The view hands in an accumulated factor; crossing a
     * threshold spends it on one step of size and returns 1.0 so the next step needs the
     * same deliberate distance again — returning the raw factor instead turns a single
     * pinch into a size that runs away with it.
     */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val step = if (scale > 1f) 1 else -1
            val context = view.context
            TerminalSessions.setFontSp(context, TerminalSessions.fontSp(context) + step)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // The InputMethodManager on purpose, not the insets controller the rest of the
        // app drives the IME with (`Ime.kt`): there is no evidence the controller raises
        // the keyboard from a tap here.
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

    // Where a sticky modifier is spent. Each read clears only its own key: the vendored
    // view reads all three for one keystroke, so clearing the lot on the first read would
    // swallow ctrl+shift. Ctrl and alt are read again inside `inputCodePoint`, but OR'd
    // with the value captured before the clear, so spending them here is still safe.
    override fun readControlKey(): Boolean = ModifierKeys.consumeCtrl()

    override fun readAltKey(): Boolean = ModifierKeys.consumeAlt()

    override fun readShiftKey(): Boolean = ModifierKeys.consumeShift()

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
        false

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { TerminalLog.error(tag, message) }

    override fun logWarn(tag: String?, message: String?) { TerminalLog.warn(tag, message) }

    override fun logInfo(tag: String?, message: String?) { TerminalLog.info(tag, message) }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        TerminalLog.stackTrace(tag, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) { TerminalLog.stackTrace(tag, e) }
}

internal class KernTerminalSessionClient(
    private val appContext: Context,
    private val view: TerminalView,
    private val id: Int,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        view.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // The status and the last thing on screen, because a shell that dies at birth
        // with living siblings simply loses its tab — no error surface exists. The tmux
        // ACL regression sat behind exactly this: two launches, two silent
        // disappearances, and a log that said only "finished" with nothing to grep for.
        val lastLine = runCatching {
            finishedSession.emulator?.screen?.transcriptText
                ?.trimEnd()?.lineSequence()?.lastOrNull()
        }.getOrNull()
        Log.i(
            TAG,
            "terminal session finished (exit ${finishedSession.exitStatus}, " +
                "last: ${lastLine ?: "(nothing)"})",
        )
        // Post rather than call straight through: this arrives on the session's own
        // thread, and replacing views has to happen on the main one.
        view.post { TerminalSessions.handleFinished(id) }
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

    override fun logError(tag: String?, message: String?) { TerminalLog.error(tag, message) }

    override fun logWarn(tag: String?, message: String?) { TerminalLog.warn(tag, message) }

    override fun logInfo(tag: String?, message: String?) { TerminalLog.info(tag, message) }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        TerminalLog.stackTrace(tag, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) { TerminalLog.stackTrace(tag, e) }
}

/**
 * The logging half of both vendored SPIs, which declare the same seven methods abstractly
 * and share no supertype — an interface with default bodies would leave a class that
 * implements both inheriting abstract-and-default, so it would have to override them
 * anyway. Forwarding to one object is what is left.
 */
internal object TerminalLog {

    fun error(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }

    fun warn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }

    fun info(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }

    fun stackTrace(tag: String?, message: String?, e: Exception?) {
        Log.e(TAG, "$tag: $message", e)
    }

    fun stackTrace(tag: String?, e: Exception?) { Log.e(TAG, "terminal", e) }
}
