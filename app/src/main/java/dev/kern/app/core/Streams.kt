package dev.kern.app.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Copy at most [limit] bytes; false if the stream had more.
 *
 * For streams another app controls - a share, a document picker - which can be any size,
 * or endless, and would otherwise fill the storage the guest lives on.
 */
fun InputStream.copyCapped(to: OutputStream, limit: Long): Boolean {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return true
        total += n
        if (total > limit) return false
        to.write(buffer, 0, n)
    }
}
