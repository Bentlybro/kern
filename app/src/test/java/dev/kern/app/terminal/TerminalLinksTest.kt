package dev.kern.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinksTest {

    @Test
    fun `finds the newest url on screen`() {
        val screen = "Local:   http://localhost:5173/\nNetwork: http://192.168.0.5:5173/\n"
        assertEquals("http://192.168.0.5:5173/", TerminalLinks.lastUrl(screen))
    }

    @Test
    fun `drops trailing punctuation and quotes`() {
        assertEquals("http://localhost:3000", TerminalLinks.lastUrl("listening on http://localhost:3000."))
        assertEquals("http://127.0.0.1:8000/api", TerminalLinks.lastUrl("see 'http://127.0.0.1:8000/api'"))
    }

    @Test
    fun `no url is null`() {
        assertNull(TerminalLinks.lastUrl("root@localhost:~# ls"))
    }
}
