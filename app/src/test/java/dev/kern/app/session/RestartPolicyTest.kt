package dev.kern.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the supervisor restarts the server, when it stops trying, and when it forgets that
 * anything was ever wrong.
 *
 * This is the one part of [SessionService] that is a decision rather than an effect, and it
 * governs whether a phone with a broken guest reports it or quietly flattens its battery.
 * The version this replaced did the latter, which is what the first two tests here pin.
 */
class RestartPolicyTest {

    @Test
    fun `one missed check is not a dead server`() {
        // The health check is a 2s HTTP request to a server inside PRoot on a phone, and it
        // loses that race whenever the guest is busy under apt. Restarting on the first
        // miss would take the editor down underneath someone whose server was merely slow.
        val (state, decision) = RestartPolicy.next(RestartPolicy.State(), healthy = false)
        assertEquals(RestartPolicy.Decision.Wait, decision)
        assertEquals(1, state.misses)
    }

    @Test
    fun `a second miss restarts, immediately and once`() {
        val (state, decision) = run(false, false)
        assertEquals(RestartPolicy.Decision.Restart(attempt = 1, backoffMs = 0L), decision)
        // The miss count resets when we act on it. Leaving it to climb is precisely the
        // bug: it stayed above the threshold forever, so every later tick restarted again.
        assertEquals(0, state.misses)
        assertEquals(1, state.restarts)
    }

    @Test
    fun `a failing server does not restart on every single tick forever`() {
        // The bug this file exists for. The old loop reset its counter only on a
        // *successful* restart, so a guest that could not come back was stopped and
        // restarted every fifteen seconds for as long as the service lived - holding a
        // partial wakelock, spawning a PRoot twice a minute, with the UI stuck on
        // "Reconnecting" and never once saying what was wrong.
        var state = RestartPolicy.State()
        var restarts = 0
        var gaveUp = false
        // An hour of ticks against a server that never answers.
        repeat(240) {
            val (next, decision) = RestartPolicy.next(state, healthy = false)
            state = next
            when (decision) {
                is RestartPolicy.Decision.Restart -> restarts++
                is RestartPolicy.Decision.GiveUp -> gaveUp = true
                else -> Unit
            }
        }
        assertEquals(
            "the server was restarted more times than the policy allows",
            RestartPolicy.MAX_RESTARTS,
            restarts,
        )
        assertTrue("an hour of failure was never reported", gaveUp)
    }

    @Test
    fun `giving up is reported exactly once, not on every later tick`() {
        // The message becomes SessionState.Failed and a notification. Re-raising it every
        // fifteen seconds would be its own bug.
        var state = RestartPolicy.State()
        var announcements = 0
        repeat(100) {
            val (next, decision) = RestartPolicy.next(state, healthy = false)
            state = next
            if (decision is RestartPolicy.Decision.GiveUp) announcements++
        }
        assertEquals(1, announcements)
    }

    @Test
    fun `after giving up it keeps watching rather than going quiet`() {
        // Abandoned, not Wait: the caller must not overwrite the failure it has already
        // reported. But it is still a tick, because the user's way out is Repair and a
        // Repair that works has to be noticed without restarting the app.
        var state = RestartPolicy.State()
        repeat(50) { state = RestartPolicy.next(state, healthy = false).first }
        assertTrue(state.gaveUp)

        val (_, decision) = RestartPolicy.next(state, healthy = false)
        assertEquals(RestartPolicy.Decision.Abandoned, decision)
    }

    @Test
    fun `giving up is a state the caller can be got out of`() {
        // The policy has no reset of its own on purpose - SessionService drops the whole
        // supervise job and starts a new one, which begins from a fresh State. This pins
        // the property that makes that work: a fresh State is not given up, so restarting
        // the loop genuinely lifts it.
        //
        // Worth stating because the alternative was shipped and was wrong. The loop stays
        // alive after giving up, so the service's "already supervising" check saw a live
        // job and made the Retry button inert - found on device, with no way back short of
        // force-stopping the app.
        var state = RestartPolicy.State()
        repeat(50) { state = RestartPolicy.next(state, healthy = false).first }
        assertTrue(state.gaveUp)
        assertFalse("a fresh state must not inherit the give-up", RestartPolicy.State().gaveUp)
    }

    @Test
    fun `a repaired guest is a working guest`() {
        // Nothing here may remember that the server used to be broken. A guest the user has
        // just repaired that came back on its fifth restart must get a full budget again,
        // or the next unrelated hiccup weeks later gives up on the first attempt.
        var state = RestartPolicy.State()
        repeat(50) { state = RestartPolicy.next(state, healthy = false).first }
        assertTrue("the fixture did not reach the given-up state", state.gaveUp)

        val (recovered, decision) = RestartPolicy.next(state, healthy = true)
        assertEquals(RestartPolicy.Decision.Healthy, decision)
        assertEquals(RestartPolicy.State(), recovered)
    }

    @Test
    fun `an intermittent server never accumulates its way to giving up`() {
        // A miss, a miss, a recovery, repeated all day. This is a server that is annoying,
        // not one that is dead, and it must never be abandoned - restarts counts
        // *consecutive* failures for exactly this reason.
        var state = RestartPolicy.State()
        repeat(500) { tick ->
            state = RestartPolicy.next(state, healthy = tick % 3 == 2).first
            assertTrue("gave up on a server that keeps coming back", !state.gaveUp)
        }
    }

    @Test
    fun `the backoff grows and then stops growing`() {
        // The first attempt is immediate because the common case is a server that died once
        // and comes straight back; making that user wait buys nothing. After that a failed
        // restart is evidence about the next one, so it backs off - but it is bounded, or a
        // long-lived session would eventually stop reacting in any useful time.
        val delays = (1..RestartPolicy.MAX_RESTARTS).map { RestartPolicy.backoffMs(it) }
        assertEquals(0L, delays.first())
        assertTrue("the backoff does not grow", delays[1] > delays[0])
        assertEquals("the backoff is not monotonic", delays.sorted(), delays)
        assertTrue("the backoff is unbounded", delays.last() <= 60_000L)
    }

    @Test
    fun `the whole budget is spent well inside the time a user would wait`() {
        // The point of giving up is to say something while the user is still watching. If
        // the backoff were generous enough that five attempts took twenty minutes, the
        // screen would sit on "Reconnecting" for twenty minutes, which is the symptom this
        // was written to end rather than a fix for it.
        val worst = (1..RestartPolicy.MAX_RESTARTS).sumOf { RestartPolicy.backoffMs(it) }
        assertTrue("the backoff budget alone is over two minutes: ${worst}ms", worst <= 120_000L)
    }

    @Test
    fun `the give-up message says what to do about it`() {
        // It is rendered as the failure state and a notification, and it is the only thing
        // the user gets. A bare "failed" would leave Repair - the thing that fixes it -
        // undiscoverable.
        val message = RestartPolicy.GIVE_UP_MESSAGE
        assertTrue("no mention of Repair: $message", message.contains("Repair"))
        assertTrue("no mention of the log: $message", message.contains("server.log"))
    }

    // ---- fixtures -----------------------------------------------------------

    /** The fixture the tests above lean on: that a fresh state is distinguishable. */
    @Test
    fun `a fresh state is not equal to a degraded one`() {
        // SessionService tells "something had gone wrong" from a plain equality check
        // against a fresh State, and uses it to decide whether to rewrite the notification.
        // If State ever loses its data-class equality that check silently becomes false
        // forever, and the "back to healthy" notification stops appearing.
        assertNotEquals(RestartPolicy.State(), RestartPolicy.State(misses = 1))
        assertEquals(RestartPolicy.State(), RestartPolicy.State())
    }

    /** Feed a sequence of health results, returning where it ends up. */
    private fun run(vararg results: Boolean): Pair<RestartPolicy.State, RestartPolicy.Decision> {
        var state = RestartPolicy.State()
        var decision: RestartPolicy.Decision = RestartPolicy.Decision.Healthy
        for (healthy in results) {
            val step = RestartPolicy.next(state, healthy)
            state = step.first
            decision = step.second
        }
        return state to decision
    }
}
