package dev.kern.app.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestPathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun link(root: File, at: String, target: String) {
        Files.createSymbolicLink(File(root, at).toPath(), Paths.get(target))
    }

    @Test
    fun `plain paths map under the rootfs`() {
        val root = temp.newFolder("rootfs")
        File(root, "root/projects").mkdirs()
        assertEquals(File(root, "root/projects"), GuestPaths.resolve(root, "/root/projects"))
        assertEquals(root, GuestPaths.resolve(root, "/"))
    }

    @Test
    fun `an absolute link resolves against the guest root, not the device root`() {
        val root = temp.newFolder("rootfs")
        File(root, "run").mkdirs()
        File(root, "var").mkdirs()
        link(root, "var/run", "/run")
        assertEquals(File(root, "run"), GuestPaths.resolve(root, "/var/run"))
    }

    @Test
    fun `a relative link resolves from its own folder`() {
        val root = temp.newFolder("rootfs")
        File(root, "usr/lib").mkdirs()
        File(root, "etc").mkdirs()
        link(root, "etc/os-release", "../usr/lib/os-release")
        assertEquals(File(root, "usr/lib/os-release"), GuestPaths.resolve(root, "/etc/os-release"))
    }

    @Test
    fun `dot-dot stops at the guest root`() {
        val root = temp.newFolder("rootfs")
        File(root, "etc").mkdirs()
        link(root, "etc/escape", "../../../../outside")
        assertEquals(File(root, "outside"), GuestPaths.resolve(root, "/etc/escape"))
        assertEquals(File(root, "etc"), GuestPaths.resolve(root, "/../../etc"))
    }

    @Test
    fun `a link loop gives up instead of spinning`() {
        val root = temp.newFolder("rootfs")
        link(root, "a", "/b")
        link(root, "b", "/a")
        assertNull(GuestPaths.resolve(root, "/a"))
    }
}
