package dev.kern.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.kern.app.runtime.LinuxRuntime
import dev.kern.app.runtime.ProjectRepository
import dev.kern.app.runtime.Secrets

/**
 * Process-scoped WebView so the workbench survives activity recreation (fold/unfold,
 * rotation, posture change). Recreating it would reload the whole workbench.
 *
 * Built against a [MutableContextWrapper] so the same instance can be re-parented to a
 * new Activity: WebViews need an Activity context for dialogs/IME, but holding a dead
 * Activity leaks it.
 */
@SuppressLint("StaticFieldLeak")
object WorkbenchWebView {

    private var instance: WebView? = null
    private var contextWrapper: MutableContextWrapper? = null

    /** Set by the selection-handle overlay to receive live selection geometry. */
    var selectionListener: ((WorkbenchBridge.SelectionInfo?) -> Unit)? = null

    /**
     * Bump when workbench settings change in a way that layout state would override.
     * VS Code persists its layout (open panels, sidebar visibility) in localStorage,
     * which outlives a settings.json change - so a one-time storage wipe is the only
     * deterministic way to make new layout defaults take effect.
     */
    private const val LAYOUT_EPOCH = 3
    private const val PREFS = "kern"
    private const val KEY_LAYOUT_EPOCH = "layout_epoch"

    private fun resetLayoutIfStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAYOUT_EPOCH, 0) == LAYOUT_EPOCH) return
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        prefs.edit().putInt(KEY_LAYOUT_EPOCH, LAYOUT_EPOCH).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(activityContext: Context): WebView {
        val existing = instance
        if (existing != null) {
            contextWrapper?.baseContext = activityContext
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        resetLayoutIfStale(activityContext)

        val wrapper = MutableContextWrapper(activityContext)
        contextWrapper = wrapper

        val webView = WebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportZoom(false)
            isFocusableInTouchMode = true

            val debuggable =
                (activityContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            WebView.setWebContentsDebuggingEnabled(debuggable)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val host = request.url.host
                    if (host == "127.0.0.1" || host == "localhost") return false
                    runCatching {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        view.postDelayed({ view.reload() }, 3_000)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // code-server runs with --auth password (risk R16). The password is
                    // our per-install token, so log in silently - the user never sees a
                    // login screen and other apps on the device cannot reach the IDE.
                    if (url.contains("/login")) {
                        val token = Secrets.token(view.context.applicationContext)
                        view.evaluateJavascript(loginScript(token), null)
                        return
                    }
                    // The workbench boots asynchronously; re-inject a few times so the
                    // listeners attach once its DOM exists. The script self-guards.
                    listOf(400L, 1500L, 4000L).forEach { delay ->
                        view.postDelayed(
                            { view.evaluateJavascript(WorkbenchBridge.SCRIPT, null) },
                            delay,
                        )
                    }
                }
            }

            // Tap-to-type, driven by the injected bridge: the WebView cannot tell us
            // whether a tap landed on code or on the file tree, but the page can.
            addJavascriptInterface(
                WorkbenchBridge(
                    onEditorTap = { inEditor ->
                        post {
                            if (inEditor && !isKeyboardVisible()) showKeyboard()
                        }
                    },
                    onSelection = { info -> post { selectionListener?.invoke(info) } },
                ),
                WorkbenchBridge.NAME,
            )

            loadUrl(LinuxRuntime.codeServerUrl(ProjectRepository.currentFolder(activityContext)))
        }
        instance = webView
        return webView
    }

    /** Detach from the current parent without destroying, before an activity goes away. */
    fun detach() {
        instance?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun current(): WebView? = instance

    /** Switch the workbench to a different workspace folder. */
    fun openFolder(path: String) {
        instance?.loadUrl(LinuxRuntime.codeServerUrl(path))
    }

    /**
     * Send a real platform key event to the workbench. Used by the native chrome and key
     * row so the web app sees genuine key input rather than synthesized JS events.
     */
    fun sendKey(keyCode: Int, meta: Int = 0) {
        val wv = instance ?: return
        val t = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    // ---- keyboard control ---------------------------------------------------
    //
    // Monaco does not reliably raise the soft keyboard when tapped inside a WebView
    // (monaco-editor#4946). Since the host app owns the window, we drive the IME
    // directly - which is the whole argument for a native shell.

    fun isKeyboardVisible(): Boolean {
        val wv = instance ?: return false
        return ViewCompat.getRootWindowInsets(wv)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    fun showKeyboard() {
        val wv = instance ?: return
        wv.requestFocus()
        ViewCompat.getWindowInsetsController(wv)?.show(WindowInsetsCompat.Type.ime())
    }

    fun hideKeyboard() {
        val wv = instance ?: return
        ViewCompat.getWindowInsetsController(wv)?.hide(WindowInsetsCompat.Type.ime())
    }

    fun toggleKeyboard() {
        if (isKeyboardVisible()) hideKeyboard() else showKeyboard()
    }

    // ---- selection ----------------------------------------------------------

    /**
     * Drive a mouse drag inside Monaco from CSS-pixel coordinates.
     *
     * Monaco has always supported mouse-drag selection; what it lacks is touch selection
     * handles (monaco-editor#1504, open since 2019). So the handles are drawn natively
     * and the drag is replayed as a mouse gesture - no Monaco API needed, which matters
     * because the workbench page does not expose one.
     */
    fun dragSelect(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val js = """
            (function(){
              function ev(t,x,y,b){
                return new MouseEvent(t,{bubbles:true,cancelable:true,view:window,
                  clientX:x,clientY:y,button:0,buttons:b,detail:1});
              }
              var a=document.elementFromPoint($fromX,$fromY);
              var b=document.elementFromPoint($toX,$toY);
              if(!a) return;
              a.dispatchEvent(ev('mousedown',$fromX,$fromY,1));
              (b||a).dispatchEvent(ev('mousemove',$toX,$toY,1));
              (b||a).dispatchEvent(ev('mouseup',$toX,$toY,0));
            })();
        """.trimIndent()
        instance?.evaluateJavascript(js, null)
    }

    /** Expand the selection outward from the caret - Monaco's smart-select. */
    fun expandSelection() =
        sendKey(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON,
        )

    fun selectAll() = sendKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)

    fun copySelection() = sendKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)

    fun pasteClipboard() = sendKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)

    private fun loginScript(token: String): String = """
        (function () {
          var f = document.querySelector('form');
          var p = document.querySelector('input[type=password], input[name=password]');
          if (!f || !p) return;
          p.value = ${'"'}$token${'"'};
          f.submit();
        })();
    """.trimIndent()

    /** Workbench commands we drive from native chrome, via their default keybindings. */
    object Commands {
        fun toggleSidebar() = sendKey(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)

        fun commandPalette() =
            sendKey(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)

        fun quickOpen() = sendKey(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON)

        fun toggleTerminal() = sendKey(KeyEvent.KEYCODE_GRAVE, KeyEvent.META_CTRL_ON)

        /** Secondary side bar (the AI chat panel) - Ctrl+Alt+B. */
        fun toggleChatPanel() =
            sendKey(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)

        fun save() = sendKey(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)

        fun find() = sendKey(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)

        fun escape() = sendKey(KeyEvent.KEYCODE_ESCAPE)
    }
}
