package dev.kern.app.guest

import dev.kern.app.core.Prefs
import dev.kern.app.projects.ProjectRepository
import dev.kern.app.session.SessionService
import dev.kern.app.terminal.TerminalSessions
import dev.kern.app.workbench.CodeServer
import dev.kern.app.workbench.WorkbenchWebView
import android.content.Context
import android.os.StatFs
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
        val root = LinuxRuntime.rootfsDir(context).toPath()
        val apt = root.resolve("var/cache/apt")
        val projects = root.resolve("root/projects")
        var guest = 0L
        var aptCache = 0L
        var projectBytes = 0L
        // One walk, bucketed by where each file sits. This used to be three - the whole
        // rootfs, then the apt cache and the projects again inside it - with two lstat
        // calls per entry on each, all of it on phone storage.
        walk(root) { file, size ->
            guest += size
            if (file.startsWith(apt)) aptCache += size
            else if (file.startsWith(projects)) projectBytes += size
        }
        Usage(
            guestMb = guest / MB,
            aptCacheMb = aptCache / MB,
            projectsMb = projectBytes / MB,
            freeMb = StatFs(context.filesDir.absolutePath).availableBytes / MB,
            limitMb = limitMb(context),
        )
    }

    private const val MB = 1024L * 1024

    /**
     * Every regular file under [root] with its size, counting each byte once.
     *
     * Symlinks are skipped rather than measured. PRoot rewrites every hard link in the
     * guest into a symlink pointing into `.l2s`, and following one reports the *target's*
     * size — so counting them bills the same bytes once per link. Ubuntu is full of hard
     * links, and the result was a rootfs reported at 3.2 GB that `du` put at 1.2 GB.
     * walkFileTree neither follows nor descends into links, and hands over the attributes
     * it already read, so each entry costs one lstat.
     */
    private fun walk(root: Path, onFile: (Path, Long) -> Unit) {
        if (!Files.isDirectory(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) onFile(file, attrs.size())
                    return FileVisitResult.CONTINUE
                }

                // Unreadable entries are not worth failing the whole scan.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    }

    /** Reclaim the space that is safe to throw away without breaking the install. */
    suspend fun cleanUp(context: Context): String {
        val before = measure(context).guestMb
        // Under the apt lock: emptying the package lists out from under a running apt-get
        // fails it halfway, and apt-get clean takes the very lock that apt holds.
        Apt.exclusive { LinuxRuntime.run(
            context,
            """
            apt-get clean 2>/dev/null
            rm -rf /var/lib/apt/lists/* 2>/dev/null
            rm -rf /root/.cache/* 2>/dev/null
            # Not `rm -rf /tmp/*`: fc-* is another guest command's scratch and kern-gh* a
            # sign-in in progress; deleting either makes its caller time out and misreport.
            find /tmp -mindepth 1 -maxdepth 1 ! -name 'fc-*' ! -name 'kern-gh*' ! -name 'kern-backup*' -exec rm -rf {} + 2>/dev/null
            : > /root/.kern/server.log 2>/dev/null
            """.trimIndent(),
            timeoutMs = 180_000,
        ) }
        val after = measure(context).guestMb
        val freed = (before - after).coerceAtLeast(0)
        return if (freed > 0) "Freed $freed MB" else "Nothing to reclaim"
    }

    /**
     * Delete the whole guest. The app falls back to the setup screen afterwards, so this
     * is the recovery path when an install goes wrong.
     */
    suspend fun deleteGuest(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Not while setup is writing into the tree. It would go on unpacking and installing
        // into a directory being deleted around it, and the result is neither a guest nor
        // its absence. The caller reports false as a failed delete; setup can be waited
        // out, or will fail on its own.
        if (RootfsInstaller.isRunning) return@withContext false
        // The supervisor first, or it outlives the guest it is supervising. It polls every
        // fifteen seconds and restarts a server it finds missing - which, once the rootfs
        // is gone, means spawning PRoot at a directory that no longer exists, on a timer,
        // for as long as the service lives. Neither of the two Delete buttons did this,
        // and it belongs here rather than at either of them: it is a fact about deleting a
        // guest, not about the screen that asked.
        SessionService.stop(context)
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
        deleteTree(LinuxRuntime.rootfsDir(context).toPath())
        // The setup downloads are staged outside the rootfs, so deleting only the guest
        // left a failed transfer's bytes on the device with nothing in the app able to
        // reclaim them, and the next setup resuming into what was left.
        RootfsInstaller.clearCachedDownloads(context)
        // Tell the UI the world changed, or it keeps routing to a guest that is gone.
        LinuxRuntime.notifyInstallChanged()
        !LinuxRuntime.isInstalled(context)
    }

    /**
     * Delete [root] and everything under it, never following a symlink: Kotlin's
     * deleteRecursively descends into a linked directory, so a guest holding a link to
     * shared storage - `/sdcard` is bound in, and a project can link anywhere - would have
     * had whatever it pointed at emptied too. walkFileTree visits a link as a file, so only
     * the link goes (the same approach as GuestFiles.delete).
     *
     * Carries on past what it cannot delete, rather than leaving most of a gigabyte behind
     * over one stubborn file; the caller judges by whether the guest is still installed.
     */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // dpkg leaves some directories without the write bit, which under
                        // PRoot's fake root is a real permission and stops their children
                        // being unlinked.
                        runCatching { dir.toFile().setWritable(true, true) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.delete(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    fun format(mb: Long): String = when {
        mb < 1024 -> "$mb MB"
        else -> "%.1f GB".format(mb / 1024.0)
    }
}
