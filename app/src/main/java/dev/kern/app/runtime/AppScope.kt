package dev.kern.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Where guest work runs when it has to finish whether or not the screen that asked for it
 * is still on top.
 *
 * A composable's `rememberCoroutineScope()` is cancelled the moment it leaves the
 * composition, and [LinuxRuntime.run] closes the pty from its `finally` - so backing out
 * of a screen killed the guest command mid-flight. On a phone that is the normal case, not
 * an edge one: a call arrives, or a clone has said "Cloning..." for four minutes and gets
 * swiped away. The wreckage then outlives the screen by a long way - a `.git/index.lock`
 * that fails every later git operation in that repository including the workbench's SCM
 * panel, a half-cloned folder that refuses the retry, dpkg stopped mid-unpack.
 *
 * [RootfsInstaller] has owned a scope like this from the start for exactly this reason;
 * setup was only the first operation long enough to hit it.
 */
object AppScope {

    /** Supervisor: one failed operation must not take the scope down with it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Run [work] to completion regardless of the caller, and hand back its result.
     *
     * Awaiting the [Deferred] from a screen's own scope is the intended shape: cancelling
     * that await abandons the reporting only, so the UI still updates while the screen is
     * there and nothing writes to it once it is gone. Give [work]
     * `context.applicationContext` rather than the activity's - it runs for minutes.
     */
    fun <T> start(work: suspend () -> T): Deferred<T> = scope.async { work() }
}
