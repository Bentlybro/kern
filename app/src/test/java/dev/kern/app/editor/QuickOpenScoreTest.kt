package dev.kern.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickOpenScoreTest {

    @Test
    fun `a name match beats a folder match`() {
        val inName = QuickOpenIndex.score("src/util/parser.py", "pars")!!
        val inFolder = QuickOpenIndex.score("parsing/util/a.py", "pars")!!
        assertTrue(inName > inFolder)
    }

    @Test
    fun `consecutive letters beat scattered ones`() {
        val tight = QuickOpenIndex.score("main.py", "main")!!
        val loose = QuickOpenIndex.score("m_a_i_n.py", "main")!!
        assertTrue(tight > loose)
    }

    @Test
    fun `not a subsequence is no match`() {
        assertNull(QuickOpenIndex.score("readme.md", "xyz"))
    }

    @Test
    fun `shorter paths win ties`() {
        assertTrue(QuickOpenIndex.score("a/b.py", "b")!! > QuickOpenIndex.score("a/c/d/b.py", "b")!!)
    }

    @Test
    fun `rank drops non-matches and puts the best first`() {
        val files = listOf("parsing/util/a.py", "readme.md", "src/util/parser.py")
        assertEquals(listOf("src/util/parser.py", "parsing/util/a.py"), QuickOpenIndex.rank(files, "pars"))
    }

    @Test
    fun `rank keeps only the best few`() {
        val files = (1..100).map { "dir/file$it.txt" }
        assertEquals(5, QuickOpenIndex.rank(files, "file", limit = 5).size)
    }
}
