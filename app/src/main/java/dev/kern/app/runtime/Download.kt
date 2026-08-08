package dev.kern.app.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Enough attempts to ride out a tunnel; few enough that a real failure is still reported. */
private const val MAX_ATTEMPTS = 5
private const val RETRY_DELAY_MS = 2_000L

/** No constant for it in HttpURLConnection: Range Not Satisfiable. */
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

/** A finished transfer whose bytes do not hash to what the publisher advertises. */
private class CorruptDownload(val resumed: Boolean) : IOException(
    "The download was corrupted in transit - it does not match the checksum its " +
        "publisher advertises, so it was deleted rather than installed.",
)

/** An answer that will be the same however often we ask: the file moved, or is refused. */
private class PermanentHttp(message: String) : IOException(message)

/**
 * Shared helper for downloading into app storage with progress, resume and verification.
 *
 * Writes to a `.part` file and only renames it into place once the whole body has
 * arrived. A phone drops its connection mid-transfer routinely, and a truncated file
 * sitting at the final name is indistinguishable from a good one - setup reused a
 * fragment of the base image forever, with nothing in the app able to clear it.
 *
 * A failed attempt now keeps the `.part` and asks for the rest with a `Range` header.
 * Deleting it meant one dropped connection cost the whole 218 MB again, which on a phone
 * is the difference between setup finishing and the user giving up. Resuming is only safe
 * next to [sha256]: a `.part` left by an earlier run against a file the server has since
 * replaced appends into nonsense that nothing downstream would notice, and the checksum is
 * what turns that into a clean restart instead of a corrupt install.
 */
internal suspend fun download(
    url: String,
    dest: File,
    /** Lowercase hex, when the publisher says what the finished file should hash to. */
    sha256: String? = null,
    onProgress: (Long, Long) -> Unit,
) = withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    val part = File(dest.parentFile, dest.name + ".part")

    var attempt = 1
    while (true) {
        try {
            transfer(url, part, sha256, onProgress)
            break
        } catch (e: IOException) {
            if (e is CorruptDownload) {
                // Whatever is on disk is not the file, so it goes whether we retry or not.
                part.delete()
                // A resume is the one way we can cause this ourselves, by appending to a
                // `.part` an earlier run wrote against a file that has since moved. That
                // earns one clean start. A whole transfer that still mismatched is the
                // mirror's problem, and asking again will not change its mind.
                if (!e.resumed) throw e
            }
            if (e is PermanentHttp || attempt >= MAX_ATTEMPTS) throw e
            // The usual cause is a radio briefly gone rather than a server that is down,
            // so back off instead of spending all five attempts inside one second.
            delay(RETRY_DELAY_MS * attempt)
            attempt++
        }
    }

    dest.delete()
    if (!part.renameTo(dest)) throw IOException("Could not move the download into place")
}

/**
 * One attempt. Appends to [part] when the server honours our range, replaces it when it
 * does not, and leaves it alone on failure so the next attempt has somewhere to start.
 */
private fun transfer(
    url: String,
    part: File,
    sha256: String?,
    onProgress: (Long, Long) -> Unit,
) {
    val have = part.length()
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        // Both hosts advertise Accept-Ranges: bytes, but a mirror or a proxy in between
        // need not honour it - so what we asked for decides nothing, the status code does.
        if (have > 0) setRequestProperty("Range", "bytes=$have-")
    }
    try {
        val code = connection.responseCode
        if (code == HTTP_RANGE_NOT_SATISFIABLE) {
            // Our start offset is past the end of what is being served, so the `.part`
            // belongs to some other file. Drop it and let the retry start from zero.
            part.delete()
            throw IOException("The partial download does not match the file on the server")
        }
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            // Not left to getInputStream: its exception for a 404 carries the URL as its
            // whole message, which is what setup used to render at the user in red.
            val message = "The server returned HTTP $code for $url"
            // A 4xx says the same thing however often we ask - except the two that exist
            // to ask for another go.
            val permanent = code in 400..499 && code != 408 && code != 429
            throw if (permanent) PermanentHttp(message) else IOException(message)
        }

        // A 200 to a ranged request means the server ignored the range and is sending the
        // file from the start, so what is already on disk has to go.
        val resumed = code == HttpURLConnection.HTTP_PARTIAL
        val base = if (resumed) have else 0L
        val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
        if (digest != null && base > 0) {
            // The digest covers the whole file, and the first `base` bytes of it are the
            // ones an earlier attempt already wrote.
            part.inputStream().use { existing ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = existing.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }

        // On a 206 this is what is left to come, not the size of the file.
        val remaining = connection.contentLengthLong
        val total = if (remaining > 0) base + remaining else 0L
        var written = 0L
        val source = connection.inputStream.let {
            if (digest != null) DigestInputStream(it, digest) else it
        }
        source.use { input ->
            FileOutputStream(part, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - lastReport > 512 * 1024) {
                        onProgress(base + written, total)
                        lastReport = written
                    }
                }
                onProgress(base + written, total)
            }
        }
        // A connection that dies mid-body reads as a clean end of stream, so short
        // and no exception is the shape a failed transfer actually takes.
        if (remaining > 0 && written != remaining) {
            throw IOException("Download stopped at ${base + written} of $total bytes")
        }
        if (digest != null && !hex(digest.digest()).equals(sha256, ignoreCase = true)) {
            throw CorruptDownload(resumed = base > 0)
        }
    } finally {
        connection.disconnect()
    }
}

/**
 * Fetch a small text resource whole.
 *
 * For the metadata files that say what to download - cdimage's SHA256SUMS. Bounded, so a
 * redirect to something enormous cannot turn a checksum lookup into an out-of-memory kill.
 */
internal fun fetchText(url: String, limitBytes: Int = 256 * 1024): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        instanceFollowRedirects = true
    }
    try {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw IOException("The server returned HTTP $code for $url")
        }
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (out.size() < limitBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
        return out.toString("UTF-8")
    } finally {
        connection.disconnect()
    }
}

/** Written out by hand rather than with %02x, so no device's locale can reshape a digest. */
private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xff
        out.append(digits[value ushr 4]).append(digits[value and 0x0f])
    }
    return out.toString()
}
