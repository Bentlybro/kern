package dev.kern.app.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Shared helper for downloading into app storage with progress. */
internal fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
    }
    try {
        connection.inputStream.use { input ->
            val total = connection.contentLengthLong
            dest.parentFile?.mkdirs()
            dest.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var written = 0L
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
        }
    } finally {
        connection.disconnect()
    }
}
