package dev.kern.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutTest {

    @Test
    fun `new keys land before the symbols, symbols at the end`() {
        val row = listOf("esc", "tab", "|", "/")
        assertEquals(listOf("esc", "tab", "m:ls\n", "|", "/"), withAdded(row, "m:ls\n"))
        assertEquals(listOf("esc", "tab", "|", "/", "@"), withAdded(row, "@"))
        assertEquals(listOf("esc", "ctrl-z"), withAdded(listOf("esc"), "ctrl-z"))
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
