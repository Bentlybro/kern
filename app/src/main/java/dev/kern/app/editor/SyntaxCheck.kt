package dev.kern.app.editor

import android.content.Context
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.guest.sq
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content

/**
 * Syntax errors, underlined, from the real tools in the guest.
 *
 * Not a language server - those are a much larger job (docs/19). This is the cheap half
 * that catches most of what goes wrong typing on a phone: an unclosed bracket, a stray
 * indent, a missing colon. After each save the file is handed to the language's own
 * checker - `python3 -m py_compile`, `node --check`, `bash -n` - and the line it names
 * is underlined, with the message on tap.
 */
object SyntaxCheck {

    data class Problem(val line: Int, val message: String)

    /** The checker for [path], as a guest command, or null when there is none. */
    internal fun commandFor(path: String): String? {
        val file = sq(path)
        return when (path.substringAfterLast('.', "").lowercase()) {
            "py" -> "python3 -m py_compile $file"
            "js", "mjs", "cjs" -> "node --check $file"
            "sh", "bash" -> "bash -n $file"
            "json" -> "python3 -m json.tool $file >/dev/null"
            else -> null
        }
    }

    /**
     * The first error in a checker's output, as a 1-based line. Each tool says it
     * differently: Python's `line 3`, node's `file.js:3`, bash's `line 3:`, json.tool's
     * `line 3 column 5`. Internal for tests.
     */
    internal fun parse(path: String, output: String): Problem? {
        if (output.isBlank()) return null
        val name = Regex.escape(path.substringAfterLast('/'))
        val line = Regex("""line (\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""$name:(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val message = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains("Error") || it.contains("error") }
            ?: output.lineSequence().map { it.trim() }.last { it.isNotEmpty() }
        return Problem(line, message.take(200))
    }

    /** Run the checker for [path]. Null when it is clean, has no checker, or did not run. */
    suspend fun check(context: Context, path: String): Problem? {
        val command = commandFor(path) ?: return null
        val r = LinuxRuntime.run(context, "$command 2>&1", timeoutMs = 20_000) ?: return null
        if (r.ok) return null
        return parse(path, r.stdout + r.stderr)
    }

    /** [problem] as an editor underline across its line, or an empty set for none. */
    fun diagnostics(content: Content, problem: Problem?): DiagnosticsContainer {
        val container = DiagnosticsContainer()
        if (problem == null || content.lineCount == 0) return container
        val line = (problem.line - 1).coerceIn(0, content.lineCount - 1)
        val start = content.getCharIndex(line, 0)
        val end = content.getCharIndex(line, content.getColumnCount(line))
        container.addDiagnostic(
            DiagnosticRegion(start, maxOf(end, start + 1), DiagnosticRegion.SEVERITY_ERROR, 0L, DiagnosticDetail(problem.message)),
        )
        return container
    }
}
