package dev.kern.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingFilesTest {

    @Test
    fun `a shared file name cannot escape the inbox`() {
        assertEquals("_etc_passwd", IncomingFiles.safeName("/etc/passwd"))
        assertEquals("_.._x", IncomingFiles.safeName("/../x"))
        assertEquals("bashrc", IncomingFiles.safeName("..bashrc"))
        assertEquals("shared-file", IncomingFiles.safeName("   "))
        assertEquals("notes.md", IncomingFiles.safeName("notes.md"))
    }
}
