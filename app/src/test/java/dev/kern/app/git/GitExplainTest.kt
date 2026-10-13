package dev.kern.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitExplainTest {

    @Test
    fun `an unauthenticated remote points at sign-in, not at the terminal`() {
        val line = "fatal: could not read Username for 'https://github.com': terminal prompts disabled"
        assertTrue(GitCommands.explain(line)!!.contains("sign in"))
    }

    @Test
    fun `ssh failures say to use https`() {
        assertTrue(GitCommands.explain("Host key verification failed.")!!.contains("https://"))
        assertTrue(
            GitCommands.explain("git@github.com: Permission denied (publickey).")!!.contains("SSH"),
        )
    }

    @Test
    fun `a repo with no remote says to add one`() {
        val out = "fatal: No configured push destination.\nEither specify the URL from the " +
            "command-line or configure a remote repository using\n\n    git remote add <name> <url>\n\n" +
            "and then push using the remote name\n\n    git push <name>"
        assertTrue(GitCommands.explain(out)!!.contains("no remote"))
    }

    @Test
    fun `anything else passes through unchanged`() {
        assertEquals("Already up to date.", GitCommands.explain("Already up to date."))
        assertEquals(null, GitCommands.explain(null))
    }
}
