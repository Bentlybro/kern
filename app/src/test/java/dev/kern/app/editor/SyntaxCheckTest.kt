package dev.kern.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyntaxCheckTest {

    @Test
    fun `python names the line`() {
        val out = """  File "/root/p/a.py", line 3
    if x
        ^
SyntaxError: expected ':'"""
        val p = SyntaxCheck.parse("/root/p/a.py", out)!!
        assertEquals(3, p.line)
        assertEquals("SyntaxError: expected ':'", p.message)
    }

    @Test
    fun `node names file and line`() {
        val out = "/root/p/app.js:2\nconst x = ;\n          ^\n\nSyntaxError: Unexpected token ';'"
        assertEquals(2, SyntaxCheck.parse("/root/p/app.js", out)!!.line)
    }

    @Test
    fun `bash names the line`() {
        val out = "/root/p/run.sh: line 4: syntax error: unexpected end of file"
        assertEquals(4, SyntaxCheck.parse("/root/p/run.sh", out)!!.line)
    }

    @Test
    fun `clean output and unknown types are nothing`() {
        assertNull(SyntaxCheck.parse("/root/p/a.py", ""))
        assertNull(SyntaxCheck.commandFor("/root/p/notes.md"))
    }
}
