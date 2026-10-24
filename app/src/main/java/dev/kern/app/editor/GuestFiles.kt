package dev.kern.app.editor

import android.content.Context
import dev.kern.app.guest.LinuxRuntime
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The guest's files, read and written directly from Kotlin.
 *
 * The rootfs is an ordinary directory in the app's own storage, so a guest path is just a
 * host path with the rootfs in front of it. Going through [LinuxRuntime.run] instead would
 * cost a PRoot spawn per file - hundreds of milliseconds to open what is a few kilobytes -
 * and that round trip is most of what makes the workbench feel slow to open things.
 */
object GuestFiles {

    /** Larger than any source file anyone edits on a phone, smaller than an OOM. */
    const val MAX_BYTES = 4L * 1024 * 1024

    /** Never interesting to browse: version-control internals and generated caches. */
    private val HIDDEN = setOf(".git", "__pycache__", ".pytest_cache", ".mypy_cache")

    data class Node(val name: String, val path: String, val isDirectory: Boolean)

    sealed interface Read {
        /** [stamp]: the file's mtime as of [content]; see [read]. */
        data class Text(val content: String, val stamp: Long) : Read
        data object Missing : Read
        data object Binary : Read
        data class TooLarge(val bytes: Long) : Read
    }

    fun host(context: Context, guestPath: String): File =
        File(LinuxRuntime.rootfsDir(context), guestPath.removePrefix("/"))

    /**
     * Whether [file] really is inside the rootfs once symlinks are resolved. A guest
     * symlink is resolved here by Android, not by PRoot, so an absolute one - a cloned
     * repository's `link -> /data/data/dev.kern.app/shared_prefs`, say - would point into
     * the app's own private files. Such a path is treated as missing.
     */
    internal fun contained(context: Context, file: File): Boolean = runCatching {
        val root = LinuxRuntime.rootfsDir(context).canonicalPath + File.separator
        // A file about to be created has no canonical form of its own yet; its folder does.
        val probe = if (file.exists()) file else file.parentFile ?: return false
        (probe.canonicalPath + File.separator).startsWith(root)
    }.getOrDefault(false)

    /** Directories first, then files, each case-insensitively. `.git` is never interesting. */
    fun list(context: Context, guestDir: String): List<Node> {
        val dir = host(context, guestDir)
        if (!contained(context, dir)) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        val base = guestDir.trimEnd('/')
        return children
            .filter { it.name !in HIDDEN && contained(context, it) }
            .map { Node(it.name, "$base/${it.name}", it.isDirectory) }
            .sortedWith(compareBy<Node>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Read [guestPath] as text, with the file's mtime as of that text. The stamp is taken
     * before the read, never after: a stamp taken after could belong to an outside write
     * that landed in between, which [DiskSync] would then take for the version the tab
     * holds - and the next autosave would overwrite it unseen. A stamp older than the text
     * costs at most a needless reload. A file that changes while it is read is read again.
     */
    fun read(context: Context, guestPath: String): Read {
        val file = host(context, guestPath)
        if (!file.isFile || !contained(context, file)) return Read.Missing
        if (file.length() > MAX_BYTES) return Read.TooLarge(file.length())
        var attempts = 0
        while (true) {
            val stamp = file.lastModified()
            val bytes = file.readBytes()
            if (file.lastModified() != stamp && ++attempts < 3) continue
            // A NUL in the first few KB is the same test git uses for "binary".
            if (bytes.take(8192).any { it == 0.toByte() }) return Read.Binary
            return Read.Text(String(bytes, Charsets.UTF_8), stamp)
        }
    }

    /**
     * Write [text] to [guestPath], atomically: a temp file beside it, then a rename. A
     * process killed mid-write - which Android does without warning - leaves either the
     * old file or the new one, never half of each.
     *
     * Returns the written file's mtime, or null if the write failed. Taken from the temp
     * file before the rename, which keeps it: a stat afterwards could see an outside write
     * that landed in between and take it for ours.
     */
    fun write(context: Context, guestPath: String, text: String): Long? = runCatching {
        val requested = host(context, guestPath)
        requested.parentFile?.mkdirs()
        check(contained(context, requested)) { "outside the guest" }
        // Through a symlink to the file it names - inside the rootfs, as just checked - so
        // the rename replaces that file. Renamed over the link itself, every save turned a
        // linked file into a detached copy and the link was gone.
        val target = if (requested.exists()) requested.canonicalFile else requested
        // Unique per write: two writers sharing one temp name could rename half of each.
        val temp = File.createTempFile(".${target.name}.", ".kern-tmp", target.parentFile)
        try {
            temp.writeText(text)
            // The original's mode, or the temp file's 0600 replaces it: a script saved in
            // the editor stopped being executable, and a shared file stopped being readable.
            if (target.exists()) {
                runCatching { Files.setPosixFilePermissions(temp.toPath(), Files.getPosixFilePermissions(target.toPath())) }
            }
            val stamp = temp.lastModified()
            if (temp.renameTo(target)) {
                stamp
            } else {
                target.writeText(text)
                target.lastModified()
            }
        } finally {
            temp.delete()
        }
    }.getOrNull()

    /** Whether anything - a file, a folder, even a dangling link - is at [guestPath]. */
    fun exists(context: Context, guestPath: String): Boolean =
        Files.exists(host(context, guestPath).toPath(), LinkOption.NOFOLLOW_LINKS)

    fun createFile(context: Context, guestPath: String): Boolean = runCatching {
        val f = host(context, guestPath)
        f.parentFile?.mkdirs()
        f.createNewFile()
    }.getOrDefault(false)

    fun createDirectory(context: Context, guestPath: String): Boolean =
        runCatching { host(context, guestPath).mkdirs() }.getOrDefault(false)

    /**
     * Move [from] to [to] - never over the top of something already there, which
     * File.renameTo replaces without a word. The tree checks first and says why; this is
     * the backstop for a file that appeared in between.
     */
    fun rename(context: Context, from: String, to: String): Boolean = runCatching {
        if (exists(context, to)) return false
        host(context, from).renameTo(host(context, to))
    }.getOrDefault(false)

    /**
     * Delete a file or folder, never following a symlink inside it: Kotlin's
     * deleteRecursively descends into a linked folder, and a project holding a link to
     * somewhere else would have that somewhere else emptied too. walkFileTree visits a
     * link as a file, so only the link goes.
     */
    fun delete(context: Context, guestPath: String): Boolean = runCatching {
        val start = host(context, guestPath).toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return false
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        true
    }.getOrDefault(false)
}
