package dev.kern.app.workbench

import android.content.Context
import android.util.Log
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.guest.sq
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Opening a particular file in the VS Code workbench, from outside it. */
object WorkbenchFiles {

    private const val TAG = "Kern"

    /**
     * Open [path] in the connected workbench window, waiting for one if the server is still
     * starting. Returns whether it got there.
     *
     * code-server's own CLI, which opens the file in the window already connected over its
     * IPC socket - no reload, workspace untouched. VS Code web's `payload` URL parameter was
     * tried first and silently opened nothing (verified on device). The retries cover a
     * window that is still connecting: the CLI fails until one has.
     */
    suspend fun open(context: Context, path: String): Boolean {
        // The server itself, not the session state: with the native editor the session is
        // ready long before code-server is, and the switch to the workbench is what
        // starts it.
        withTimeoutOrNull(120_000) {
            while (!withContext(Dispatchers.IO) { CodeServer.isHealthy() }) delay(1_000)
        } ?: return false
        repeat(6) { attempt ->
            if (attempt > 0) delay(3_000)
            val r = LinuxRuntime.run(
                context,
                "code-server --reuse-window ${sq(path)} >/dev/null 2>&1",
                timeoutMs = 20_000,
            )
            if (r?.ok == true) return true
        }
        Log.w(TAG, "could not open $path in the workbench")
        return false
    }
}
