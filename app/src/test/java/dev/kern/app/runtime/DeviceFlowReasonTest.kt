package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told when signing in to GitHub does not work.
 *
 * This is the whole of the failure: the sign-in screen shows this string and nothing else.
 * Every case here sends the reader somewhere different — back to GitHub, to the Status
 * screen, or to try again — and sending them to the wrong one costs them the time it takes
 * to rule it out.
 */
class DeviceFlowReasonTest {

    @Test
    fun `an expired code says so, rather than blaming the network`() {
        // The script writes EXIT=timeout after fifteen minutes, which is how long a GitHub
        // device code is valid. The answer is to start again, not to check anything.
        val reason = GitHubDeviceFlow.reasonFor(pane = "some stale screen", rc = "EXIT=timeout")
        assertTrue(reason, reason.contains("expired"))
    }

    @Test
    fun `a helper that never ran points at the guest, not at GitHub`() {
        // No rc at all means the script did not get as far as writing one - a PRoot that
        // would not spawn, or a guest with no tmux. GitHub was never contacted, so
        // "sign-in did not complete" sends the reader to check an account that is fine.
        val reason = GitHubDeviceFlow.reasonFor(pane = "", rc = "")
        assertTrue(reason, reason.contains("Status"))
        assertTrue(reason, reason.contains("tmux"))
    }

    @Test
    fun `gh's own error is preferred over whatever else is on screen`() {
        // gh leaves its answered prompts on the pane, so the last line is routinely a
        // question rather than the problem.
        val pane = """
            ? Authenticate Git with your GitHub credentials? (Y/n)
            ! First copy your one-time code: ABCD-1234
            error: could not connect to github.com
            Press Enter to open github.com in your browser...
        """.trimIndent()
        val reason = GitHubDeviceFlow.reasonFor(pane, rc = "EXIT=1")
        assertEquals("error: could not connect to github.com", reason)
    }

    @Test
    fun `the one-time code is never shown as the reason it failed`() {
        // It is on the pane, it is the most recent interesting-looking line, and it is
        // meaningless as an explanation - it is the thing the user was asked to type.
        val pane = """
            Something went wrong
            ! First copy your one-time code: WXYZ-9876
        """.trimIndent()
        val reason = GitHubDeviceFlow.reasonFor(pane, rc = "EXIT=1")
        assertTrue("the code leaked into the reason: $reason", !reason.contains("WXYZ-9876"))
        assertEquals("Something went wrong", reason)
    }

    @Test
    fun `a failure with nothing on screen still says something`() {
        // The pane can be empty if gh died before drawing anything. A blank message would
        // leave the sign-in screen showing an error with no text in it.
        val reason = GitHubDeviceFlow.reasonFor(pane = "", rc = "EXIT=1")
        assertTrue("the reason is blank", reason.isNotBlank())
    }

    @Test
    fun `a long line is cut to something a phone can show`() {
        val pane = "error: " + "x".repeat(500)
        val reason = GitHubDeviceFlow.reasonFor(pane, rc = "EXIT=1")
        assertTrue("the reason is ${reason.length} characters", reason.length <= 160)
    }
}
