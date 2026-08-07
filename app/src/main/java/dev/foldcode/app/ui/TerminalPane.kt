package dev.foldcode.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.foldcode.app.runtime.LinuxRuntime

/**
 * Native terminal surface (M3). Renders Termux's real terminal emulator/view over a
 * socket-backed [TerminalSession] — no xterm.js, no WebView in the input path, which is
 * what makes the terminal usable on touch (docs/10 F3).
 */
object TerminalHost {

    private var view: TerminalView? = null
    private var session: TerminalSession? = null

    fun acquire(context: Context): TerminalView {
        view?.let { return it }

        val terminalView = TerminalView(context, null).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setTextSize(spToPx(context, 13f))
            keepScreenOn = true
            isFocusableInTouchMode = true
        }
        terminalView.setTerminalViewClient(FoldCodeTerminalViewClient(terminalView))

        // Local pty into the Linux guest — no bridge, no sockets, no token.
        val appContext = context.applicationContext
        val newSession = TerminalSession(
            { columns, rows -> LinuxRuntime.spawnShell(appContext, columns, rows) },
            2000,
            FoldCodeTerminalSessionClient(appContext, terminalView),
        )
        terminalView.attachSession(newSession)

        view = terminalView
        session = newSession
        return terminalView
    }

    fun detach() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    /** Send a key to the terminal (used by the shared key row when the terminal has focus). */
    fun sendKey(keyCode: Int, meta: Int = 0) {
        val v = view ?: return
        val t = android.os.SystemClock.uptimeMillis()
        v.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        v.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    fun write(text: String) {
        session?.let { s ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            s.write(bytes, 0, bytes.size)
        }
    }

    fun hasFocus(): Boolean = view?.hasFocus() == true

    fun isKeyboardVisible(): Boolean {
        val v = view ?: return false
        return ViewCompat.getRootWindowInsets(v)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    fun toggleKeyboard() {
        val v = view ?: return
        v.requestFocus()
        val controller = ViewCompat.getWindowInsetsController(v) ?: return
        if (isKeyboardVisible()) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    fun isAttached(): Boolean = view != null

    fun current(): TerminalView? = view

    private fun spToPx(context: Context, sp: Float): Int =
        (sp * context.resources.displayMetrics.scaledDensity).toInt()
}

@Composable
fun TerminalPane(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalHost.detach()
            TerminalHost.acquire(ctx)
        },
    )
}

private const val TAG = "FoldCode"

private class FoldCodeTerminalViewClient(
    private val view: TerminalView,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1.0f

    override fun onSingleTapUp(e: MotionEvent?) {
        // Raise the soft keyboard on tap — the behaviour the WebView terminal never got right.
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

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

    override fun logError(tag: String?, message: String?) {
        Log.e(TAG, "$tag: $message")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(TAG, "$tag: $message")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(TAG, "$tag: $message")
    }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(TAG, "terminal", e)
    }
}

private class FoldCodeTerminalSessionClient(
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
        TerminalHost.write(text)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {
        Log.e(TAG, "$tag: $message")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(TAG, "$tag: $message")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(TAG, "$tag: $message")
    }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(TAG, "terminal", e)
    }
}

/** Shared modifier state so the key row can drive either the workbench or the terminal. */
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
