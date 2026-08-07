package dev.kern.app.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installing packages into the guest, once.
 *
 * Three places did this three slightly different ways, and every difference was a defect:
 * the toolchain install had no `apt-get update` in front of it on the resume path and so
 * fetched against package lists that could be months stale, and Status's Install button
 * skipped `update-ca-certificates` — which decided whether `gh` ended up with a working
 * trust store based on which of three buttons the user happened to press.
 */
object Apt {

    private const val UPDATE_TIMEOUT_MS = 300_000L

    /** apt on phone storage is slow, and a full toolchain is minutes of it. */
    private const val INSTALL_TIMEOUT_MS = 1_200_000L

    /**
     * apt-get update, then install, then update-ca-certificates if it was in the set.
     *
     * Both scripts fold stderr into stdout: [onLine] tails the output file, and a caller
     * watching an install wants the failure most of all.
     */
    suspend fun install(
        context: Context,
        packages: List<String>,
        onLine: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        LinuxRuntime.run(
            context,
            "apt-get update 2>&1",
            timeoutMs = UPDATE_TIMEOUT_MS,
            onLine = onLine,
        )
        // ca-certificates is only a trust store once update-ca-certificates has run, so it
        // belongs here rather than with whichever caller remembers to ask for it.
        val script = buildString {
            append("apt-get install -y ${packages.joinToString(" ")} 2>&1")
            if ("ca-certificates" in packages) append(" && update-ca-certificates 2>&1")
        }
        val result = LinuxRuntime.run(
            context,
            script,
            timeoutMs = INSTALL_TIMEOUT_MS,
            onLine = onLine,
        )
        result?.ok == true
    }
}
