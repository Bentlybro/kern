package dev.kern.app.guest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Finishing an unpack dpkg never got to finish is fast when there is nothing to do. */
    private const val RECOVER_TIMEOUT_MS = 600_000L

    /**
     * One apt at a time, app wide.
     *
     * Setup's toolchain, GitHub sign-in's install, Status's Install and Repair all reach
     * apt, and nothing stopped two of them running at once. The second then died on
     * dpkg's lock - or, worse, its `dpkg --configure -a` ran into the middle of the first
     * one's unpack. Not reentrant: hold it through [exclusive] only, never around a call
     * to [install].
     */
    private val lock = Mutex()

    /** How many callers are inside [exclusive], running or waiting for their turn. */
    private var inside = 0

    private val _busy = MutableStateFlow(false)

    /**
     * True while any apt or dpkg run is in progress or queued, setup's included. For the UI
     * to disable its own install buttons rather than queue a second run behind the first.
     */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Run [block] holding the app-wide apt lock. Everything that runs apt-get or dpkg in
     * the guest goes through here, so they queue rather than collide.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        enter(1)
        try {
            return lock.withLock { block() }
        } finally {
            enter(-1)
        }
    }

    /** The count and the flag move together, or two callers could publish out of order. */
    private fun enter(delta: Int) = synchronized(this) {
        inside += delta
        _busy.value = inside > 0
    }

    /**
     * Recover dpkg, apt-get update, then install, then update-ca-certificates if it was in
     * the set.
     *
     * Both scripts fold stderr into stdout: [onLine] tails the output file, and a caller
     * watching an install wants the failure most of all.
     */
    suspend fun install(
        context: Context,
        packages: List<String>,
        onLine: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) { exclusive { installLocked(context, packages, onLine) } }

    private suspend fun installLocked(
        context: Context,
        packages: List<String>,
        onLine: ((String) -> Unit)?,
    ): Boolean {
        // The guest's resolv.conf was written whenever it was last set up or repaired, and
        // a phone changes network several times a day. apt is the thing that most needs DNS
        // and the thing whose failure says least about why, so re-point it at whatever the
        // device is using now before asking.
        GuestConfig.refreshDns(context)
        // Android kills this app whenever it likes, and an apt killed mid-unpack leaves dpkg
        // in a state where every later install refuses to start with "dpkg was interrupted,
        // you must manually run 'dpkg --configure -a'". Nothing in the app used to run it,
        // so Repair could not repair the one failure it most needed to, and the only way out
        // was a terminal command a phone-only user has no reason to know. Observed on a real
        // device: a guest with code-server and no git, and a Repair that could not fix it.
        // It is a no-op on a healthy guest, so it costs nothing to always try.
        LinuxRuntime.run(
            context,
            "dpkg --configure -a 2>&1",
            timeoutMs = RECOVER_TIMEOUT_MS,
            onLine = onLine,
        )
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
        return result?.ok == true
    }
}
