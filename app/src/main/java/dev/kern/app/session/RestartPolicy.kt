package dev.kern.app.session

/**
 * What the supervisor should do about the server, given how the last health check went.
 *
 * Pure, and separate from [SessionService], for two reasons. The first is that this is the
 * only part of the service that is a decision rather than an effect, and it was previously
 * four lines of arithmetic tangled into a `while` loop with a wakelock, a notification and
 * two network calls - untestable in place, and it was wrong.
 *
 * The second is what it was wrong about. The old loop reset `misses` only on a *successful*
 * restart, so a server that could not come back left it at or above the restart threshold
 * forever: every tick from then on stopped and restarted the guest's code-server, every
 * fifteen seconds, for as long as the service lived. Nothing capped it, nothing backed off,
 * and the state never left [SessionState.Reconnecting] - so the screen said "reconnecting"
 * indefinitely while the phone held a partial wakelock and spawned a PRoot twice a minute.
 * On a guest broken in a way restarting cannot fix - no code-server, a full disk, a dpkg
 * stopped mid-unpack - that is a flat battery and a screen that never says what is wrong.
 *
 * So the policy here gives up, and says so. Giving up is not the same as ceasing to watch:
 * [Decision.GiveUp] leaves the supervisor polling, because the user's way out is Repair,
 * and a Repair that works should be noticed rather than needing the app restarted.
 */
internal object RestartPolicy {

    /**
     * One missed check is not a dead server.
     *
     * The health check is a 2-second-timeout HTTP request to a server running inside PRoot
     * on a phone, and it loses that race routinely while the guest is busy - during an apt,
     * or when Android has parked the app's threads on the little cores. Restarting on the
     * first miss would take the editor down underneath someone whose server was merely slow.
     */
    const val MISSES_BEFORE_RESTART = 2

    /**
     * After this many consecutive failed restarts, stop trying and report it.
     *
     * Five is enough to ride out a transient cause - the toolchain apt still running, a
     * device briefly out of memory - and few enough that a guest which is genuinely broken
     * is reported inside a couple of minutes rather than never.
     */
    const val MAX_RESTARTS = 5

    /**
     * How long to wait *before* a restart attempt, by attempt number.
     *
     * The first is immediate: the overwhelmingly common case is a server that died once and
     * comes straight back, and making that user wait buys nothing. After that it backs off,
     * because a restart that failed is evidence about the next one - and a tight retry loop
     * against a guest that cannot start is exactly what drains the battery.
     */
    fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 0L
        2 -> 5_000L
        3 -> 15_000L
        else -> 30_000L
    }

    /**
     * Everything carried between ticks. [restarts] counts *consecutive* failures, so any
     * healthy check clears it - a server that dies once a day is not a server on its fifth
     * restart.
     */
    data class State(
        val misses: Int = 0,
        val restarts: Int = 0,
        val gaveUp: Boolean = false,
    )

    sealed interface Decision {
        /** The server answered. */
        data object Healthy : Decision

        /** It did not, but not often enough to act on yet. */
        data object Wait : Decision

        /** Stop and start the server. [attempt] is 1-based; wait [backoffMs] first. */
        data class Restart(val attempt: Int, val backoffMs: Long) : Decision

        /** Out of attempts. Report it once, keep watching, stop restarting. */
        data class GiveUp(val message: String) : Decision

        /**
         * Already given up, and still not answering. Distinct from [Wait] so the caller
         * knows not to overwrite the failure it has already reported.
         */
        data object Abandoned : Decision
    }

    /**
     * The next state and what to do, from the current state and one health result.
     *
     * A healthy check always resets everything, including [State.gaveUp] - a guest the user
     * has repaired is a working guest, and nothing here should remember that it used to be
     * broken.
     */
    fun next(state: State, healthy: Boolean): Pair<State, Decision> {
        if (healthy) return State() to Decision.Healthy

        if (state.gaveUp) return state to Decision.Abandoned

        val misses = state.misses + 1
        if (misses < MISSES_BEFORE_RESTART) {
            return state.copy(misses = misses) to Decision.Wait
        }

        val attempt = state.restarts + 1
        if (attempt > MAX_RESTARTS) {
            return state.copy(misses = misses, gaveUp = true) to Decision.GiveUp(GIVE_UP_MESSAGE)
        }
        // misses resets here, not on success: the threshold counts checks missed since the
        // last thing we did about it, and we are about to do something about it. Leaving it
        // to climb is what made every later tick restart immediately.
        return state.copy(misses = 0, restarts = attempt) to
            Decision.Restart(attempt, backoffMs(attempt))
    }

    /**
     * Deliberately the same advice [SessionService] gives when the *first* start never
     * comes up. From the user's side the two are one situation - the editor is not there
     * and something has to be done - and the something is the same.
     */
    const val GIVE_UP_MESSAGE: String =
        "code-server stopped and could not be restarted after $MAX_RESTARTS attempts. " +
            "See /root/.kern/server.log in the terminal, or try Repair in settings."
}
