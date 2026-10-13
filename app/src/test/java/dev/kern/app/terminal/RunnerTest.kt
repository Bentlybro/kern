package dev.kern.app.terminal

import dev.kern.app.guest.sq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunnerTest {

    @Test
    fun `run commands follow the file type`() {
        assertEquals("python3 'main.py'", Runner.commandFor("/root/p/main.py"))
        assertEquals("node 'app.js'", Runner.commandFor("/root/p/app.js"))
        assertEquals("make", Runner.commandFor("/root/p/Makefile"))
        assertEquals("cargo run", Runner.commandFor("/root/p/Cargo.toml"))
    }

    @Test
    fun `names with spaces and quotes stay one argument`() {
        assertEquals("python3 " + sq("it's here.py"), Runner.commandFor("/root/p/it's here.py"))
    }

    @Test
    fun `unknown types do not run`() {
        assertNull(Runner.commandFor("/root/p/notes.md"))
        assertNull(Runner.commandFor("/root/p/README"))
    }
}
