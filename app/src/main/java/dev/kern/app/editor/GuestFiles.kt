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
        data class Text(val content: String) : Read
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
    private fun contained(context: Context, file: File): Boolean = runCatching {
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

    fun read(context: Context, guestPath: String): Read {
        val file = host(context, guestPath)
        if (!file.isFile || !contained(context, file)) return Read.Missing
        if (file.length() > MAX_BYTES) return Read.TooLarge(file.length())
        val bytes = file.readBytes()
        // A NUL in the first few KB is the same test git uses for "binary".
        if (bytes.take(8192).any { it == 0.toByte() }) return Read.Binary
        return Read.Text(String(bytes, Charsets.UTF_8))
    }

    /**
     * Write [text] to [guestPath], atomically: a temp file beside it, then a rename. A
     * process killed mid-write - which Android does without warning - leaves either the
     * old file or the new one, never half of each.
     */
    fun write(context: Context, guestPath: String, text: String): Boolean = runCatching {
        val target = host(context, guestPath)
        target.parentFile?.mkdirs()
        check(contained(context, target)) { "outside the guest" }
        // Unique per write: two writers sharing one temp name could rename half of each.
        val temp = File.createTempFile(".${target.name}.", ".kern-tmp", target.parentFile)
        try {
            temp.writeText(text)
            if (!temp.renameTo(target)) target.writeText(text)
        } finally {
            temp.delete()
        }
        true
    }.getOrDefault(false)

    fun createFile(context: Context, guestPath: String): Boolean = runCatching {
        val f = host(context, guestPath)
        f.parentFile?.mkdirs()
        f.createNewFile()
    }.getOrDefault(false)

    fun createDirectory(context: Context, guestPath: String): Boolean =
        runCatching { host(context, guestPath).mkdirs() }.getOrDefault(false)

    fun rename(context: Context, from: String, to: String): Boolean =
        runCatching { host(context, from).renameTo(host(context, to)) }.getOrDefault(false)

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
