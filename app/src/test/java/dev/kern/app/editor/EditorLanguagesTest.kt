package dev.kern.app.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLanguagesTest {

    @Test
    fun `an ordinary source file is coloured`() {
        val text = (1..2_000).joinToString("\n") { "    \"key$it\": \"value $it\"," }
        assertFalse(EditorLanguages.tooLargeToColour(text))
    }

    @Test
    fun `minified json on one line is not`() {
        val text = "[" + (1..2_000).joinToString(",") { "{\"id\":$it,\"name\":\"n$it\"}" } + "]"
        assertTrue(EditorLanguages.tooLargeToColour(text))
    }

    @Test
    fun `one long line in the middle is enough`() {
        val text = "a\n" + "x".repeat(10_001) + "\nb"
        assertTrue(EditorLanguages.tooLargeToColour(text))
    }

    @Test
    fun `a line exactly at the limit is coloured`() {
        assertFalse(EditorLanguages.tooLargeToColour("x".repeat(10_000)))
        assertFalse(EditorLanguages.tooLargeToColour("x".repeat(10_000) + "\n"))
    }

    @Test
    fun `a very large file of short lines is not`() {
        val text = "short line\n".repeat(100_000)
        assertTrue(EditorLanguages.tooLargeToColour(text))
    }
}
