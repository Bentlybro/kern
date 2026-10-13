package dev.kern.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPathTest {

    @Test
    fun `home reads as a tilde, not as the filesystem root`() {
        assertEquals("~", displayPath("/root"))
        assertEquals("~/projects", displayPath("/root/projects"))
        assertEquals("/etc", displayPath("/etc"))
        assertEquals("/rootfs", displayPath("/rootfs"))
    }
}
