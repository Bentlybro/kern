package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure pieces of running something inside the guest: how PRoot is invoked, and how
 * the exit code comes back.
 *
 * Neither has a loud failure mode, which is the reason they are worth pinning here. A
 * missing bind produces a guest that works until the first package with a hard link; a
 * misread exit code produces a command that failed and said it succeeded. Both surface a
 * long way from the code that caused them.
 */
class GuestCommandTest {

    // ---- exit codes ---------------------------------------------------------

    @Test
    fun `an exit code is read back as itself`() {
        // `echo $?` writes digits and a newline, so this is the ordinary case.
        assertEquals(0, LinuxRuntime.parseExitCode("0\n"))
        assertEquals(1, LinuxRuntime.parseExitCode("1\n"))
        // 100 is apt's "package problem", the exact code a caller most wants to see.
        assertEquals(100, LinuxRuntime.parseExitCode("100\n"))
        // 130 is a command killed by SIGINT, and 127 is command-not-found - which is what
        // the guest returns while apt is briefly replacing every coreutils hard link.
        assertEquals(127, LinuxRuntime.parseExitCode("127"))
        assertEquals(130, LinuxRuntime.parseExitCode("  130  "))
    }

    @Test
    fun `a file that exists but has not been written yet is not exit zero`() {
        // The bug this pins: the shell creates fc-N.rc by truncation *before* `echo`
        // writes into it, so there is a window where the file exists and is empty. The
        // old code read that as `toIntOrNull() ?: 0` - and every caller in the app treats
        // 0 as success. A failed apt, a failed clone or a failed `git config` was
        // occasionally reported as having worked, silently.
        //
        // null means "not finished", which is what makes the caller keep waiting.
        assertNull(LinuxRuntime.parseExitCode(null))
        assertNull(LinuxRuntime.parseExitCode(""))
        assertNull(LinuxRuntime.parseExitCode("   "))
        assertNull(LinuxRuntime.parseExitCode("\n"))
    }

    @Test
    fun `anything that is not a whole number is a read that arrived too early`() {
        // No shell writes these, so seeing one means the read raced the write rather than
        // that the command exited strangely. Waiting is right; guessing is not.
        for (garbage in listOf("1", "12").map { it + "\u0000" } + listOf("x", "-", "1.5", "0x0")) {
            assertNull("read <$garbage> as an exit code", LinuxRuntime.parseExitCode(garbage))
        }
    }

    // ---- proot argv ---------------------------------------------------------

    @Test
    fun `every option precedes the guest command`() {
        // PRoot stops parsing options at the first non-option word. An option that lands
        // after the command is not rejected - it is passed through to bash, which reports
        // it as its own bad argument and says nothing about PRoot.
        val argv = argv(binds = listOf("/proc", "/dev"))
        val command = argv.indexOf("/bin/bash")
        assertTrue("the guest command is missing", command > 0)
        for (option in listOf("-0", "-l", "-r", "-w", "-b")) {
            assertTrue(
                "$option appears at or after the guest command",
                argv.indexOf(option) in 0 until command,
            )
        }
    }

    @Test
    fun `fake root and link translation are always passed`() {
        // -0 is what makes apt and dpkg work at all. -l rewrites hard links to symlinks,
        // and SELinux forbids hard links in app storage, so without it the first
        // `apt install` fails. Neither is conditional and neither may become so.
        val argv = argv(binds = emptyList())
        assertTrue("-0 (fake root) is missing", "-0" in argv)
        assertTrue("-l (link translation) is missing", "-l" in argv)
    }

    @Test
    fun `the rootfs and working directory are attached to their own flags`() {
        // Both take a value as the next word. A list built by hand can drift so that -r is
        // followed by the working directory, which mounts the wrong tree without erroring.
        val argv = argv(binds = listOf("/proc"))
        assertEquals("/data/linux", argv[argv.indexOf("-r") + 1])
        assertEquals("/root/projects/x", argv[argv.indexOf("-w") + 1])
    }

    @Test
    fun `each bind is passed as its own flag and value, in order`() {
        val binds = listOf("/data/linux/.l2s:/data/linux/.l2s", "/proc", "/proc/self/fd:/dev/fd")
        val argv = argv(binds = binds)
        val passed = argv.withIndex()
            .filter { it.value == "-b" }
            .map { argv[it.index + 1] }
        assertEquals(binds, passed)
    }

    @Test
    fun `the hard-link farm is bound onto its own path`() {
        // PRoot writes the *host* path of the real file as a translated symlink's target,
        // and that path is then resolved from inside the guest. Binding the directory at
        // its own path is the only thing that makes it resolve. 26.04's coreutils is one
        // binary behind ~115 hard links, so getting this wrong takes out `ls` and `cat`
        // together rather than breaking something obscure.
        val l2s = "/data/user/0/dev.kern.app/files/linux/.l2s"
        assertTrue(
            "the l2s directory is not bound onto itself",
            "$l2s:$l2s" in LinuxRuntime.bindCandidates(l2s),
        )
    }

    @Test
    fun `the guest sees the standard streams and the device nodes`() {
        // /dev/pts is what makes a terminal inside the guest work at all, and the
        // /proc/self/fd binds are what make /dev/stdout and friends resolve. A package's
        // install script redirecting to /dev/null on a guest without /dev fails in a way
        // that reads as a broken package.
        val candidates = LinuxRuntime.bindCandidates("/l2s")
        for (required in listOf(
            "/proc",
            "/dev",
            "/dev/pts",
            "/proc/self/fd:/dev/fd",
            "/proc/self/fd/1:/dev/stdout",
        )) {
            assertTrue("$required is not among the binds", required in candidates)
        }
    }

    @Test
    fun `a bind list with nothing in it still produces a runnable argv`() {
        // Every bind is filtered against the host filesystem, so on a device that exposes
        // none of them this is what is left. It has to still be a valid invocation rather
        // than, say, a dangling -b.
        val argv = argv(binds = emptyList())
        assertEquals(listOf("/proot", "-0", "-l", "-r", "/data/linux", "-w", "/root/projects/x"),
            argv.dropLast(2))
        assertTrue("-b" !in argv)
    }

    private fun argv(binds: List<String>) = LinuxRuntime.prootArgv(
        proot = "/proot",
        rootfs = "/data/linux",
        binds = binds,
        workingDir = "/root/projects/x",
        guestCommand = listOf("/bin/bash", "-lc"),
    )
}
