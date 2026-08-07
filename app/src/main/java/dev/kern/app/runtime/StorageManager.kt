package dev.kern.app.runtime

import android.content.Context
import android.os.StatFs
import dev.kern.app.ui.TerminalSessions
import dev.kern.app.ui.WorkbenchWebView
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage accounting and cleanup for the Linux guest.
 *
 * A note on why there is no hard size cap: the guest is a *directory* in app-private
 * storage, not a disk image, so nothing can enforce a ceiling on it. A real quota would
 * need a loopback ext4 image, and Android does not let an unprivileged app mount one.
 * What the user gets instead is an honest picture of usage, a threshold that warns before
 * the phone fills up, and one-tap ways to reclaim space.
 */
object StorageManager {

    private const val DEFAULT_LIMIT_MB = 4096

    data class Usage(
        val guestMb: Long,
        val aptCacheMb: Long,
        val projectsMb: Long,
        val freeMb: Long,
        val limitMb: Int,
    ) {
        /** Share of the soft limit in use, clamped for display. */
        val fraction: Float
            get() = if (limitMb <= 0) 0f else (guestMb.toFloat() / limitMb).coerceIn(0f, 1f)

        val overLimit: Boolean get() = limitMb > 0 && guestMb > limitMb
    }

    fun limitMb(context: Context): Int =
        Prefs.of(context).getInt(Prefs.KEY_STORAGE_LIMIT_MB, DEFAULT_LIMIT_MB)

    fun setLimitMb(context: Context, value: Int) {
        Prefs.of(context).edit().putInt(Prefs.KEY_STORAGE_LIMIT_MB, value).apply()
    }

    /** Options offered in the UI, in MB. */
    val LIMIT_CHOICES = listOf(2048, 4096, 8192, 16384)

    /**
     * Measure the guest. This walks the whole tree, so it is deliberately explicit and
     * only ever run when the settings screen asks for it.
     */
    suspend fun measure(context: Context): Usage = withContext(Dispatchers.IO) {
        val root = LinuxRuntime.rootfsDir(context)
        Usage(
            guestMb = sizeMb(root),
            aptCacheMb = sizeMb(File(root, "var/cache/apt")),
            projectsMb = sizeMb(File(root, "root/projects")),
            freeMb = StatFs(context.filesDir.absolutePath).availableBytes / (1024 * 1024),
            limitMb = limitMb(context),
        )
    }

    /**
     * Size of a tree, counting each byte once.
     *
     * Symlinks are skipped rather than measured. PRoot rewrites every hard link in the
     * guest into a symlink pointing into `.l2s`, and `File.length()` on a symlink reports
     * the *target's* size — so counting them bills the same bytes once per link. Ubuntu is
     * full of hard links, and the result was a rootfs reported at 3.2 GB that `du` put at
     * 1.2 GB. Symlinked directories are not descended into either, for the same reason.
     */
    private fun sizeMb(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .onFail { _, _ -> /* unreadable entries are not worth failing the whole scan */ }
            .forEach { entry ->
                if (entry.isFile && !Files.isSymbolicLink(entry.toPath())) {
                    total += entry.length()
                }
            }
        return total / (1024 * 1024)
    }

    /** Reclaim the space that is safe to throw away without breaking the install. */
    suspend fun cleanUp(context: Context): String {
        val before = measure(context).guestMb
        LinuxRuntime.run(
            context,
            """
            apt-get clean 2>/dev/null
            rm -rf /var/lib/apt/lists/* 2>/dev/null
            rm -rf /root/.cache/* 2>/dev/null
            # Not `rm -rf /tmp/*`: fc-* is another guest command's scratch and kern-gh* a
            # sign-in in progress; deleting either makes its caller time out and misreport.
            find /tmp -mindepth 1 -maxdepth 1 ! -name 'fc-*' ! -name 'kern-gh*' -exec rm -rf {} + 2>/dev/null
            : > /root/.kern/server.log 2>/dev/null
            """.trimIndent(),
            timeoutMs = 180_000,
        )
        val after = measure(context).guestMb
        val freed = (before - after).coerceAtLeast(0)
        return if (freed > 0) "Freed $freed MB" else "Nothing to reclaim"
    }

    /**
     * Delete the whole guest. The app falls back to the setup screen afterwards, so this
     * is the recovery path when an install goes wrong.
     */
    suspend fun deleteGuest(context: Context): Boolean = withContext(Dispatchers.IO) {
        CodeServer.stop()
        // The terminals and the workbench are process scoped, so they used to survive this
        // and carry on addressing a guest that is gone - a shell whose cwd is an unlinked
        // directory while its next absolute path lands in the *replacement* rootfs, and a
        // WebView still showing the old workbench. Before the tree goes, so nothing is
        // still writing into it, and on the main thread because both own views.
        withContext(Dispatchers.Main) {
            TerminalSessions.destroyAll()
            WorkbenchWebView.destroy()
        }
        // The paths the app remembers are inside the guest and outlive it too.
        ProjectRepository.forgetAll(context)
        val root = LinuxRuntime.rootfsDir(context)
        runCatching { root.deleteRecursively() }.getOrDefault(false)
        // The setup downloads are staged outside the rootfs, so deleting only the guest
        // left a failed transfer's bytes on the device with nothing in the app able to
        // reclaim them, and the next setup resuming into what was left.
        RootfsInstaller.clearCachedDownloads(context)
        // Tell the UI the world changed, or it keeps routing to a guest that is gone.
        LinuxRuntime.notifyInstallChanged()
        !LinuxRuntime.isInstalled(context)
    }

    fun format(mb: Long): String = when {
        mb < 1024 -> "$mb MB"
        else -> "%.1f GB".format(mb / 1024.0)
    }
}
