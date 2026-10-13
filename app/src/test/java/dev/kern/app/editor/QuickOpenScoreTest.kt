package dev.kern.app.editor

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
}
