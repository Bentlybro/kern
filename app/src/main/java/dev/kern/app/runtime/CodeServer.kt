package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The VS Code workbench the app actually shows: one code-server process inside the Linux
 * guest, plus everything that only makes sense next to it — the port it listens on, whether
 * it is installed, the URL the WebView loads, the handle on the running process, and the
 * settings that make the web layer render as an editor and nothing else.
 *
 * [LinuxRuntime] stays the guest itself — paths, install state, and running commands in it.
 * It knows nothing about this server beyond how to spawn a process for it.
 */
object CodeServer {

    private const val TAG = "Kern"

    const val PORT = 13337

    fun isInstalled(context: Context): Boolean =
        File(LinuxRuntime.rootfsDir(context), "usr/bin/code-server").exists()

    fun url(folder: String = "/root"): String =
        "http://127.0.0.1:$PORT/?folder=$folder"

    const val HEALTH_URL = "http://127.0.0.1:$PORT/healthz"

    /**
     * Start code-server inside the guest if it is not already listening. Idempotent via
     * a pidfile; deliberately not `pgrep`, whose pattern would also match the very shell
     * doing the checking.
     */
    /**
     * The long-lived PRoot process hosting code-server.
     *
     * Held for the app's lifetime on purpose. PRoot supervises everything it traces, so
     * this handle *is* the running guest — closing it would take the server down with
     * it, and letting it be collected would do the same.
     */
    @Volatile
    private var serverProcess: PtyProcess? = null

    /**
     * Start code-server in the guest. Returns as soon as it has been launched; whether it
     * actually came up is decided by health-polling, not by this call.
     */
    fun start(context: Context, token: String): Boolean {
        if (serverProcess != null) return true

        val script = """
            mkdir -p /root/.kern /root/.local/share/code-server/User
            export PASSWORD=${sq(token)}
            exec code-server --auth password --bind-addr 127.0.0.1:$PORT \
              --disable-telemetry --disable-update-check \
              >> /root/.kern/server.log 2>&1
        """.trimIndent()

        val process = LinuxRuntime.spawnInGuest(context, listOf("/bin/bash", "-lc", script))
            ?: return false

        serverProcess = process
        // Drain the pty in the background: the server writes to a log file, but anything
        // that does reach the pty would eventually fill the buffer and stall it.
        process.drainInBackground("KernServerDrain")

        return true
    }

    fun stop() {
        serverProcess?.close()
        serverProcess = null
    }

    fun isRunning(): Boolean = serverProcess != null

    /**
     * Push workbench settings so the web layer renders only the editor. Why the workbench's
     * own chrome is hidden rather than styled is in docs/05-ux.md, and what the native shell
     * puts in its place is in docs/10-native-ui-requirements.md.
     */
    suspend fun applyWorkbenchSettings(context: Context) {
        val settingsFile = File(
            LinuxRuntime.rootfsDir(context),
            "root/.local/share/code-server/User/settings.json",
        )
        runCatching {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(WORKBENCH_SETTINGS)
        }.onFailure { Log.w(TAG, "could not write workbench settings: ${it.message}") }
    }

    /**
     * Changing a layout key here is only half the change. VS Code persists its layout in
     * localStorage, which outlives a settings.json write, so an existing install keeps the
     * layout it already had until `WorkbenchWebView.LAYOUT_EPOCH` is bumped in the same
     * edit — that bump is what wipes the stored state so these defaults can take effect.
     */
    private val WORKBENCH_SETTINGS = """
        {
          "workbench.activityBar.location": "hidden",
          "workbench.statusBar.visible": false,
          "workbench.secondarySideBar.defaultVisibility": "hidden",
          "workbench.layoutControl.enabled": false,
          "workbench.editor.editorActionsLocation": "hidden",
          "window.menuBarVisibility": "hidden",
          "window.commandCenter": false,
          "workbench.startupEditor": "none",
          "workbench.colorTheme": "Default Dark Modern",
          "editor.minimap.enabled": false,
          "editor.wordWrap": "on",
          "editor.fontSize": 14,
          "editor.stickyScroll.enabled": false,
          "editor.acceptSuggestionOnEnter": "off",
          "terminal.integrated.fontSize": 13,
          "keyboard.dispatch": "keyCode",
          "security.workspace.trust.enabled": false,
          "update.mode": "none",
          "telemetry.telemetryLevel": "off",
          "chat.commandCenter.enabled": false
        }
    """.trimIndent()
}
