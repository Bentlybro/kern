package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentPrompt] decides, from nothing but a rendered terminal screen, whether a CLI agent
 * has stopped and is waiting for the user. That answer drives a notification, so it is
 * wrong in two directions: a miss leaves an agent parked for an hour while the phone is in
 * a pocket, and a false alarm trains the user to ignore the notification that matters.
 *
 * The input is not a tidy log. It is the emulator's screen buffer: every row padded out to
 * the terminal width, and blank rows all the way down to the bottom, so the *last* line of
 * a screen is almost never the last thing the agent said. [screen] reproduces that shape,
 * because reading the final row instead of the final non-blank one is the obvious way to
 * get this wrong and it fails silently - the notification simply never arrives.
 */
class AgentPromptTest {

    // ---- what the agent last said --------------------------------------------

    @Test
    fun `the rows a terminal pads its screen with are not the agent's last word`() {
        // isNotBlank(), not isNotEmpty(): the padding rows are full of spaces, not empty.
        assertEquals("Wrote 3 files.", AgentPrompt.lastLine(screen("Wrote 3 files.")))
    }

    @Test
    fun `the last line is reported without the width the terminal pads it to`() {
        assertEquals("done", AgentPrompt.lastLine("   done" + " ".repeat(70)))
    }

    @Test
    fun `a screen with nothing on it reports no last line and nobody waiting`() {
        // A session that has just started, or one whose agent has exited and cleared.
        for (empty in listOf("", "\n\n\n", "   \n\t\n ", screen())) {
            assertNull(AgentPrompt.lastLine(empty))
            assertFalse(AgentPrompt.awaitingInput(empty))
        }
    }

    @Test
    fun `the newest line is reported, not one the agent printed earlier`() {
        val s = screen(
            "Cloning into 'kern'...",
            "remote: Enumerating objects: 1204, done.",
            "Resolving deltas: 100% (612/612), done.",
        )
        assertEquals("Resolving deltas: 100% (612/612), done.", AgentPrompt.lastLine(s))
    }

    // ---- whether that was a question -----------------------------------------

    @Test
    fun `a question is still a question when the screen is padded beneath it`() {
        // The whole point. The prompt is on row 3 of 24; rows 4 to 24 are blank.
        val s = screen(
            "* Editing app/src/main/java/dev/kern/app/runtime/Apt.kt",
            "",
            "Apply this change? [y/n]",
        )
        assertTrue(AgentPrompt.awaitingInput(s))
    }

    @Test
    fun `the ways a CLI agent asks for an answer are all read as waiting`() {
        for (question in QUESTIONS) {
            val s = screen("* Editing app/src/main/java/dev/kern/app/Main.kt", "", question)
            assertTrue("not read as a question: \"$question\"", AgentPrompt.awaitingInput(s))
        }
    }

    @Test
    fun `an agent that is still working is not reported as waiting`() {
        for (line in WORKING) {
            val s = screen("* Editing app/src/main/java/dev/kern/app/Main.kt", "", line)
            assertFalse("mistaken for a question: \"$line\"", AgentPrompt.awaitingInput(s))
        }
    }

    @Test
    fun `an idle shell is not an agent waiting for an answer`() {
        // Every session starts here, and the session registry polls every screen it has.
        // If a bare prompt read as a question, every open tab would notify on startup.
        assertFalse(AgentPrompt.awaitingInput(screen("bently@kern:~/projects/kern\$")))
    }

    @Test
    fun `a question the user has already answered stops being a prompt`() {
        val s = screen(
            "Do you want me to run the tests? [y/n]",
            "y",
            "Running 42 tests...",
        )
        assertFalse(AgentPrompt.awaitingInput(s))
    }

    @Test
    fun `a question asked inside a paragraph is not a prompt`() {
        // The reason the pattern anchors the question mark to the end of the line: agents
        // narrate their reasoning as prose, and prose asks rhetorical questions constantly.
        val s = screen(
            "The failure looks like a race in the installer. Where does the temporary",
            "directory get removed? Reading RootfsInstaller.kt to find out.",
        )
        assertFalse(AgentPrompt.awaitingInput(s))
    }

    @Test
    fun `a question shouted in capitals is still a question`() {
        assertTrue(AgentPrompt.awaitingInput(screen("OVERWRITE THE EXISTING FILE [Y/N]")))
    }

    // ---- the shape of a terminal screen --------------------------------------

    @Test
    fun `the screens above really are padded, so the naive reading would be caught`() {
        // Insurance for the rest of the file. Everything here is only worth running if
        // [screen] reproduces the padding a real emulator produces - if it ever stops,
        // these tests go on passing while testing nothing at all, which is the failure
        // this suite was written to end.
        val rows = screen("Apply this change? [y/n]").lines()
        assertTrue("the fixture stopped filling the screen to its height", rows.size > 1)
        assertTrue("the fixture stopped padding the bottom of the screen", rows.last().isBlank())
        // Padded with spaces, not left empty: isNotEmpty() would accept a space-filled row
        // as the agent's last word, and only isNotBlank() rejects it.
        assertTrue("the fixture stopped padding with spaces", rows.last().isNotEmpty())
    }

    /**
     * A screen buffer as the emulator hands it over: [ROWS] rows of exactly [COLUMNS]
     * characters, content at the top and spaces the rest of the way down.
     */
    private fun screen(vararg lines: String): String {
        val padding = (ROWS - lines.size).coerceAtLeast(0)
        val rows = lines.map { it.padEnd(COLUMNS) } + List(padding) { " ".repeat(COLUMNS) }
        return rows.joinToString("\n")
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24

        /** How agents in the guest actually ask, taken from their real output. */
        val QUESTIONS = listOf(
            "Apply this change? [y/n]",
            "Do you want to overwrite build.gradle.kts?",
            "Proceed with force-pushing to main (y/N)",
            "Run `apt install ripgrep` in the guest? yes/no",
            "Press enter to continue",
            "Approve this command before it runs",
            "Kern is asking for permission to write outside the project",
        )

        /**
         * Output from an agent that is busy, which must never notify. Ordinary terminal
         * noise belongs here too: the check runs against whatever is on screen, and most
         * of what is ever on screen is a build log.
         */
        val WORKING = listOf(
            "Running 42 tests...",
            "* Thinking",
            "Wrote app/src/main/java/dev/kern/app/runtime/RootfsInstaller.kt (240 lines)",
            "BUILD SUCCESSFUL in 1m 12s",
            "Cloning into 'kern'...",
            "fatal: could not read Username: No such device or address",
            "Reading package lists... Done",
            "  30 files changed, 1204 insertions(+), 88 deletions(-)",
            // These four are why the pattern matches shapes rather than words. Each one
            // used to raise "your agent is waiting for you" while nothing was waiting, and
            // the first two are the likeliest ways a push and a build fail on this app, so
            // the notification fired precisely when the user was already stuck.
            "git@github.com: Permission denied (publickey).",
            "bash: ./gradlew: Permission denied",
            "Approved the change and moved on",
            "Waiting for permission checks to finish",
        )
    }
}
