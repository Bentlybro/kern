package dev.foldcode.app.runtime

import android.content.Context
import android.os.StatFs
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

    private const val PREFS = "foldcode"
    private const val KEY_LIMIT_MB = "storage_limit_mb"
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
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB)

    fun setLimitMb(context: Context, value: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT_MB, value).apply()
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
            rm -rf /tmp/* 2>/dev/null
            : > /root/.foldcode/server.log 2>/dev/null
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
        LinuxRuntime.stopCodeServer()
        val root = LinuxRuntime.rootfsDir(context)
        runCatching { root.deleteRecursively() }.getOrDefault(false)
        // Tell the UI the world changed, or it keeps routing to a guest that is gone.
        LinuxRuntime.notifyInstallChanged()
        !LinuxRuntime.isInstalled(context)
    }

    fun format(mb: Long): String = when {
        mb < 1024 -> "$mb MB"
        else -> "%.1f GB".format(mb / 1024.0)
    }
}
