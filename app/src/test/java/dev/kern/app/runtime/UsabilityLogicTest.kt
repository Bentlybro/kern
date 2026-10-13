package dev.kern.app.runtime

import dev.kern.app.ui.KeyLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsabilityLogicTest {

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

    @Test
    fun `a shared file name cannot escape the inbox`() {
        assertEquals("_etc_passwd", IncomingFiles.safeName("/etc/passwd"))
        assertEquals("_.._x", IncomingFiles.safeName("/../x"))
        assertEquals("bashrc", IncomingFiles.safeName("..bashrc"))
        assertEquals("shared-file", IncomingFiles.safeName("   "))
        assertEquals("notes.md", IncomingFiles.safeName("notes.md"))
    }

    @Test
    fun `new keys land before the symbols, symbols at the end`() {
        val row = listOf("esc", "tab", "|", "/")
        assertEquals(listOf("esc", "tab", "m:ls\n", "|", "/"), dev.kern.app.ui.withAdded(row, "m:ls\n"))
        assertEquals(listOf("esc", "tab", "|", "/", "@"), dev.kern.app.ui.withAdded(row, "@"))
        assertEquals(listOf("esc", "ctrl-z"), dev.kern.app.ui.withAdded(listOf("esc"), "ctrl-z"))
    }

    @Test
    fun `key ids are validated, labelled and built from typed macros`() {
        assertTrue(KeyLayout.isValid("esc"))
        assertTrue(KeyLayout.isValid("|"))
        assertTrue(KeyLayout.isValid("m:ls"))
        assertFalse(KeyLayout.isValid("m:"))
        assertFalse(KeyLayout.isValid("nonsense"))

        assertEquals("m:git status\n", KeyLayout.macro("git status\\n"))
        assertEquals("git status⏎", KeyLayout.label("m:git status\n"))
        assertEquals("^C", KeyLayout.label("ctrl-c"))
        assertTrue(KeyLayout.DEFAULT.all(KeyLayout::isValid))
    }
}
