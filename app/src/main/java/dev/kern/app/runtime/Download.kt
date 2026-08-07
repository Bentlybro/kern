package dev.kern.app.runtime

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared helper for downloading into app storage with progress.
 *
 * Writes to a `.part` file and only renames it into place once the whole body has
 * arrived. A phone drops its connection mid-transfer routinely, and a truncated file
 * sitting at the final name is indistinguishable from a good one - setup reused a
 * fragment of the base image forever, with nothing in the app able to clear it.
 */
internal fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
    }
    dest.parentFile?.mkdirs()
    val part = File(dest.parentFile, dest.name + ".part")
    try {
        connection.inputStream.use { input ->
            val total = connection.contentLengthLong
            var written = 0L
            part.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - lastReport > 512 * 1024) {
                        onProgress(written, total)
                        lastReport = written
                    }
                }
                onProgress(written, total)
            }
            // A connection that dies mid-body reads as a clean end of stream, so short
            // and no exception is the shape a failed transfer actually takes.
            if (total > 0 && written != total) {
                throw IOException("Download stopped at $written of $total bytes")
            }
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not move the download into place")
    } catch (e: Exception) {
        part.delete()
        throw e
    } finally {
        connection.disconnect()
    }
}
