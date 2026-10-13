package dev.kern.app.editor

import android.content.Context
import android.util.Base64
import android.os.SystemClock
import android.util.Log
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.guest.PtyProcess
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * A helper process in the guest, started on first use and kept warm, answering one JSON
 * line per request - how the editor talks to jedi (Python) and to TypeScript's language
 * service (JavaScript and TypeScript).
 *
 * Warm because a fresh PRoot per keystroke costs more than the answer is worth. Over a
 * pty in raw mode because canonical mode caps a line at 4 KB and a source file is longer.
 * Each request carries an id; the reply line starts with [MARK] and echoes it, so stray
 * output (warnings, the shell) is ignored. Every request gets the keys `i` (id), `k`
 * (kind), `p` (path), `l` (1-based line), `c` (0-based column) and `s` (base64 source).
 *
 * @param scriptName where [script] is written, under /root/.kern
 * @param command the guest command that runs it, with the script's path substituted for {}
 */
class WarmProcess(
    private val name: String,
    private val scriptName: String,
    private val script: String,
    private val command: String,
) {
    @Volatile
    private var process: PtyProcess? = null
    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableFuture<JSONArray>>()

    /** When the last process started, and the earliest the next may; see [read]. */
    @Volatile
    private var startedAt = 0L

    @Volatile
    private var retryAfter = 0L

    private fun ensure(context: Context): PtyProcess? {
        process?.let { return it }
        synchronized(this) {
            process?.let { return it }
            if (SystemClock.elapsedRealtime() < retryAfter) return null
            val file = File(LinuxRuntime.rootfsDir(context), "root/.kern/$scriptName")
            runCatching { file.parentFile?.mkdirs(); file.writeText(script) }
            val run = command.replace("{}", "/root/.kern/$scriptName")
            val p = LinuxRuntime.spawnInGuest(context, listOf("/bin/bash", "-lc", "stty raw -echo; exec $run"))
                ?: return null
            Thread({ read(p) }, "Kern$name").apply { isDaemon = true }.start()
            startedAt = SystemClock.elapsedRealtime()
            process = p
            return p
        }
    }

    private fun read(p: PtyProcess) {
        runCatching {
            p.input.bufferedReader().forEachLine { raw ->
                val at = raw.indexOf(MARK)
                if (at < 0) return@forEachLine
                val json = runCatching { JSONObject(raw.substring(at + MARK.length)) }.getOrNull()
                    ?: return@forEachLine
                waiting.remove(json.optInt("i", -1))?.complete(json.optJSONArray("r") ?: JSONArray())
            }
        }
        // Ended - killed with the guest, crashed, or the server is not installed. The next
        // request starts a new one - but not straight away if this one died at once, or a
        // missing gopls would be respawned on every keystroke.
        // A deliberate [stop] has already cleared [process], and is no reason to wait.
        if (process === p) {
            process = null
            if (SystemClock.elapsedRealtime() - startedAt < 10_000) retryAfter = SystemClock.elapsedRealtime() + 60_000
        }
        runCatching { p.close() }
        waiting.values.forEach { it.complete(JSONArray()) }
        waiting.clear()
    }

    /** One request; an empty answer on any failure or after [timeoutMs]. Blocking. */
    fun ask(context: Context, kind: String, path: String, source: String, line: Int, column: Int, timeoutMs: Long): JSONArray {
        val p = ensure(context) ?: return JSONArray()
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JSONArray>()
        waiting[id] = future
        val request = JSONObject()
            .put("i", id).put("k", kind).put("p", path).put("l", line).put("c", column)
            .put("s", Base64.encodeToString(source.toByteArray(), Base64.NO_WRAP))
        return runCatching {
            synchronized(p) {
                p.output.write((request.toString() + "\n").toByteArray())
                p.output.flush()
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse {
            waiting.remove(id)
            Log.d("Kern", "$name: no answer (${it.javaClass.simpleName})")
            JSONArray()
        }
    }

    /** End the process, if running; the next request starts a fresh one. */
    fun stop() {
        val p = synchronized(this) { process.also { process = null } } ?: return
        retryAfter = 0
        runCatching { p.close() }
    }

    /** Start ahead of the first request, so the first completion is not the slow one. */
    fun warm(context: Context) {
        val app = context.applicationContext
        Thread({ ensure(app) }, "Kern${name}Warm").apply { isDaemon = true }.start()
    }

    companion object {
        /** What starts every reply line; anything else on the pty is not an answer. */
        const val MARK = "@@KERN"
    }
}

/** A completion from a helper: what to insert, a kind for the icon, and how much it replaces. */
data class IntelCompletion(val name: String, val kind: String, val prefixLength: Int)

/** Where a definition is: a guest path, a 1-based line, a 0-based column. */
data class IntelLocation(val path: String, val line: Int, val column: Int)

/** Replies in the shared shape: `[[name, kind, prefixLength], ...]`. */
fun JSONArray.toCompletions(): List<IntelCompletion> = (0 until length()).mapNotNull { i ->
    optJSONArray(i)?.let { IntelCompletion(it.optString(0), it.optString(1), it.optInt(2)) }
}

/** Replies in the shared shape: `[[path, line, column], ...]`; the first is taken. */
fun JSONArray.toLocation(): IntelLocation? =
    optJSONArray(0)?.let { IntelLocation(it.optString(0), it.optInt(1), it.optInt(2)) }
