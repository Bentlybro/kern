package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LinuxRuntime.Result] is what every guest command comes back as, and its two accessors
 * are what the UI shows the user when something goes wrong. lastLine() is the text of a
 * failure toast; lines() is parsed as data by the project list and the health check. Both
 * are handed raw pty-adjacent output - trailing newlines, apt's carriage returns, blank
 * separators - so the interesting cases are all about what gets thrown away.
 */
class ResultTest {

    private fun result(stdout: String = "", stderr: String = "", exitCode: Int = 0) =
        LinuxRuntime.Result(exitCode, stdout, stderr)

    // ---- lastLine ------------------------------------------------------------

    @Test
    fun `the trailing newline every command ends with is not reported as the last line`() {
        assertEquals("done", result(stdout = "cloning\ndone\n").lastLine())
    }

    @Test
    fun `blank and whitespace-only lines are skipped in favour of the last real one`() {
        val out = "fatal: repository not found\n   \n\n\t\n"
        assertEquals("fatal: repository not found", result(stdout = out).lastLine())
    }

    @Test
    fun `an error on stderr is preferred over ordinary progress on stdout`() {
        // git writes both, and only one of them tells the user why the clone failed.
        val r = result(
            stdout = "Cloning into 'repo'...\n",
            stderr = "fatal: could not read Username: No such device or address\n",
            exitCode = 128,
        )
        assertEquals("fatal: could not read Username: No such device or address", r.lastLine())
    }

    @Test
    fun `a command that wrote nothing to stderr still reports its stdout`() {
        assertEquals("ok", result(stdout = "ok\n").lastLine())
        // Whitespace on stderr must not count as having said something, or every command
        // whose wrapper flushed a newline would report an empty failure reason.
        assertEquals("ok", result(stdout = "ok\n", stderr = "\n   \n").lastLine())
    }

    @Test
    fun `a command that printed nothing at all has no last line`() {
        assertNull(result().lastLine())
        assertNull(result(stdout = "\n\n", stderr = "   ").lastLine())
    }

    @Test
    fun `apt progress redrawn with carriage returns reports the final state`() {
        // apt overwrites one line with \r rather than printing new ones, so treating the
        // whole burst as a single line would show the user "Reading" forever.
        val out = "Reading package lists\rReading package lists 50%\rReading package lists 100%\n"
        assertEquals("Reading package lists 100%", result(stdout = out).lastLine())
    }

    @Test
    fun `the reported line is trimmed of the indentation the guest printed`() {
        assertEquals("fatal: bad object", result(stdout = "    fatal: bad object   \n").lastLine())
    }

    @Test
    fun `a runaway line is cut to the limit so it cannot fill the screen`() {
        val huge = "E: " + "x".repeat(500)
        assertEquals(160, result(stdout = huge).lastLine()?.length)

        val short = result(stdout = huge).lastLine(limit = 20)
        assertEquals(20, short?.length)
        // Cut from the end, not the start: the front of the line is the part that says
        // what went wrong.
        assertEquals("E: ", short?.take(3))
    }

    // ---- lines ---------------------------------------------------------------

    @Test
    fun `lines reports stdout only so a warning on stderr is never parsed as data`() {
        // Callers treat these as records - one project directory, one missing binary -
        // and apt is happy to write advice to stderr in the middle of either.
        val r = result(
            stdout = "repo-one\nrepo-two\n",
            stderr = "WARNING: apt does not have a stable CLI interface\n",
        )
        assertEquals(listOf("repo-one", "repo-two"), r.lines)
    }

    @Test
    fun `lines drops the blank entries a trailing newline and paragraph breaks leave behind`() {
        // Without this a caller counting entries sees one more project than exists.
        assertEquals(listOf("one", "two"), result(stdout = "one\n\n  \ntwo\n").lines)
        assertEquals(emptyList<String>(), result(stdout = "\n\n").lines)
    }
}
