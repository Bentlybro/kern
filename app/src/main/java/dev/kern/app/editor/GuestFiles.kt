package dev.kern.app.editor

import android.content.Context
import dev.kern.app.guest.LinuxRuntime
import java.io.File

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

    /** Directories first, then files, each case-insensitively. `.git` is never interesting. */
    fun list(context: Context, guestDir: String): List<Node> {
        val children = host(context, guestDir).listFiles() ?: return emptyList()
        val base = guestDir.trimEnd('/')
        return children
            .filter { it.name !in HIDDEN }
            .map { Node(it.name, "$base/${it.name}", it.isDirectory) }
            .sortedWith(compareBy<Node>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun read(context: Context, guestPath: String): Read {
        val file = host(context, guestPath)
        if (!file.isFile) return Read.Missing
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
        val temp = File(target.parentFile, ".${target.name}.kern-tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
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

    fun delete(context: Context, guestPath: String): Boolean =
        runCatching { host(context, guestPath).deleteRecursively() }.getOrDefault(false)
}
