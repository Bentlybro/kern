package dev.kern.app.editor

import android.content.Context
import android.util.Base64
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
 * Python code intelligence from jedi, the engine under most Python tooling.
 *
 * One python3 process in the guest, started on first use and kept warm: a fresh PRoot per
 * keystroke would cost more than the answer is worth, while a warm jedi answers in tens of
 * milliseconds. Requests and replies are one JSON line each over its pty, which is put in
 * raw mode - canonical mode caps a line at 4 KB, and a source file is longer than that.
 *
 * Absent jedi (python3-jedi not installed) everything here quietly answers nothing, and
 * the editor falls back to words from the file.
 */
object PythonIntel {

    private const val TAG = "Kern"
    private const val MARK = "@@KERN"

    data class Completion(val name: String, val kind: String, val prefixLength: Int)
    data class Location(val path: String, val line: Int, val column: Int)

    private val SERVER = """
import sys, json, base64
try:
    import jedi
except ImportError:
    jedi = None
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    r = {}
    out = []
    try:
        r = json.loads(line)
        if jedi is not None:
            src = base64.b64decode(r["s"]).decode("utf-8", "replace")
            s = jedi.Script(src, path=r["p"])
            if r["k"] == "c":
                out = [[c.name, c.type, len(c.name) - len(c.complete)] for c in s.complete(r["l"], r["c"])[:80]]
            else:
                out = [[str(d.module_path), d.line, d.column] for d in s.goto(r["l"], r["c"], follow_imports=True) if d.line and d.module_path]
    except Exception:
        out = []
    print("$MARK" + json.dumps({"i": r.get("i"), "r": out}), flush=True)
""".trimIndent()

    @Volatile
    private var process: PtyProcess? = null
    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableFuture<JSONArray>>()

    private fun ensure(context: Context): PtyProcess? {
        process?.let { return it }
        synchronized(this) {
            process?.let { return it }
            val script = File(LinuxRuntime.rootfsDir(context), "root/.kern/jedi_server.py")
            runCatching { script.parentFile?.mkdirs(); script.writeText(SERVER) }
            val p = LinuxRuntime.spawnInGuest(
                context,
                listOf("/bin/bash", "-c", "stty raw -echo; exec python3 -u /root/.kern/jedi_server.py"),
            ) ?: return null
            Thread({ read(p) }, "KernJedi").apply { isDaemon = true }.start()
            process = p
            return p
        }
    }

    private fun read(p: PtyProcess) {
        runCatching {
            p.input.bufferedReader().forEachLine { raw ->
                val at = raw.indexOf(MARK)
                if (at < 0) return@forEachLine
                val json = runCatching { JSONObject(raw.substring(at + MARK.length)) }.getOrNull() ?: return@forEachLine
                waiting.remove(json.optInt("i", -1))?.complete(json.optJSONArray("r") ?: JSONArray())
            }
        }
        // The process ended - killed with the guest, or crashed. The next request starts
        // a new one.
        if (process === p) process = null
        waiting.values.forEach { it.complete(JSONArray()) }
        waiting.clear()
    }

    /** One request; an empty answer on any failure or after [timeoutMs]. Blocking. */
    private fun ask(context: Context, kind: String, path: String, source: String, line: Int, column: Int, timeoutMs: Long): JSONArray {
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
            Log.d(TAG, "jedi: no answer (${it.javaClass.simpleName})")
            JSONArray()
        }
    }

    /** Completions at [line] (1-based) and [column] (0-based) in [source]. Blocking. */
    fun complete(context: Context, path: String, source: String, line: Int, column: Int): List<Completion> {
        val r = ask(context, "c", path, source, line, column, timeoutMs = 3_000)
        return (0 until r.length()).mapNotNull { i ->
            r.optJSONArray(i)?.let { Completion(it.optString(0), it.optString(1), it.optInt(2)) }
        }
    }

    /** Where the name at [line] (1-based), [column] (0-based) is defined, if jedi knows. Blocking. */
    fun definition(context: Context, path: String, source: String, line: Int, column: Int): Location? {
        val r = ask(context, "g", path, source, line, column, timeoutMs = 5_000)
        val first = r.optJSONArray(0) ?: return null
        return Location(first.optString(0), first.optInt(1), first.optInt(2))
    }

    /** Start jedi ahead of the first request, so the first completion is not the slow one. */
    fun warm(context: Context) {
        Thread({ ensure(context.applicationContext) }, "KernJediWarm").apply { isDaemon = true }.start()
    }
}
