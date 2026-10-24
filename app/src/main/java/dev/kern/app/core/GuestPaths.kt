package dev.kern.app.core

import java.io.File
import java.nio.file.Files

/**
 * Guest paths resolved on the host, the way PRoot would resolve them.
 *
 * The rootfs is a plain directory under the app's files, so a lookup that only reads a
 * listing or a small file does not need a PRoot spawn - and spawning one cost a few hundred
 * milliseconds per Projects open and per folder-browser step. What PRoot adds is how
 * symlinks resolve: an absolute target is relative to the guest's `/`, not the device's.
 * Ubuntu has plenty of those (`/var/run -> /run`), and left to Android they either vanish
 * or, worse, point into the app's own private files. So links are followed here, one
 * component at a time, against the rootfs.
 */
object GuestPaths {

    /** Linux's own limit on links followed in one lookup. */
    private const val MAX_LINKS = 40

    /**
     * The host file for [guestPath] under [root], every symlink on the way resolved inside
     * the guest. Never outside [root]: `..` stops at the guest's `/`, as it does in Linux.
     * Null when the links loop or cannot be read; a path that simply does not exist still
     * maps to a (missing) file, so callers ask it the usual questions.
     */
    fun resolve(root: File, guestPath: String): File? {
        val pending = guestPath.split('/').filter { it.isNotEmpty() }.toMutableList()
        val resolved = mutableListOf<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeAt(0)
            when (part) {
                "." -> continue
                ".." -> {
                    if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                    continue
                }
            }
            resolved.add(part)
            val here = File(root, resolved.joinToString("/")).toPath()
            if (!Files.isSymbolicLink(here)) continue
            if (++links > MAX_LINKS) return null
            val target = runCatching { Files.readSymbolicLink(here).toString() }.getOrNull()
                ?: return null
            resolved.removeAt(resolved.lastIndex)
            if (target.startsWith("/")) resolved.clear()
            pending.addAll(0, target.split('/').filter { it.isNotEmpty() })
        }
        return if (resolved.isEmpty()) root else File(root, resolved.joinToString("/"))
    }
}
