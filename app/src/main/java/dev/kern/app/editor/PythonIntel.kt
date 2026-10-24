package dev.kern.app.editor

import android.content.Context

/**
 * Python code intelligence from jedi, the engine under most Python tooling, running in a
 * [WarmProcess]. Absent jedi (python3-jedi not installed) it answers nothing and the editor
 * falls back to words from the file.
 */
object PythonIntel {

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
    print("${WarmProcess.MARK}" + json.dumps({"i": r.get("i"), "r": out}), flush=True)
""".trimIndent()

    private val process = WarmProcess("Jedi", "jedi_server.py", SERVER, "python3 -u {}")

    fun warm(context: Context) = process.warm(context)

    /** Completions at [line] (1-based) and [column] (0-based) in [source]. Blocking. */
    fun complete(context: Context, path: String, source: String, line: Int, column: Int): List<IntelCompletion> =
        process.ask(context, "c", path, source, line, column, timeoutMs = 3_000, onlyIfIdle = true).toCompletions()

    /** Where the name at [line] (1-based), [column] (0-based) is defined, if jedi knows. Blocking. */
    fun definition(context: Context, path: String, source: String, line: Int, column: Int): IntelLocation? =
        process.ask(context, "g", path, source, line, column, timeoutMs = 5_000).toLocation()
}
