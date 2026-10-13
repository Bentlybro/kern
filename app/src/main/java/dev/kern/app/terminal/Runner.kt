package dev.kern.app.terminal

import android.content.Context
import dev.kern.app.guest.sq
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Run the file being edited, in a terminal - the one-tap loop of edit, run, read.
 *
 * The command comes from the file's type; it is typed into the active shell rather than run
 * hidden, so its output, its errors and any input it asks for are all in a real terminal,
 * and pressing up re-runs it. Unknown types return null, and the Run control hides.
 */
object Runner {

    /** Asks the shell to show the terminal pane; the shell resets it once handled. */
    private val _showTerminal = MutableStateFlow(0)
    val showTerminal: StateFlow<Int> = _showTerminal.asStateFlow()

    /** The command that runs [path], from the directory it is in. Internal for tests. */
    internal fun commandFor(path: String): String? {
        val name = path.substringAfterLast('/')
        val file = sq(name)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "py" -> "python3 $file"
            "js", "mjs", "cjs" -> "node $file"
            "ts" -> "npx --yes tsx $file"
            "sh", "bash" -> "bash $file"
            "rb" -> "ruby $file"
            "pl" -> "perl $file"
            "php" -> "php $file"
            "lua" -> "lua $file"
            "go" -> "go run $file"
            "c" -> "cc $file -o /tmp/kern-run && /tmp/kern-run"
            "cpp", "cc", "cxx" -> "c++ $file -o /tmp/kern-run && /tmp/kern-run"
            // Inside a cargo project, cargo: a lone rustc cannot see the crate's
            // dependencies or its other modules. A single loose file still compiles alone.
            "rs" -> "if cargo locate-project >/dev/null 2>&1; then cargo run -q; " +
                "else rustc $file -o /tmp/kern-run && /tmp/kern-run; fi"
            "java" -> "java $file"
            "kt", "kts" -> if (name.endsWith(".kts")) "kotlin $file" else null
            else -> when (name) {
                "Makefile", "makefile" -> "make"
                "package.json" -> "npm start"
                "Cargo.toml" -> "cargo run"
                else -> null
            }
        }
    }

    fun canRun(path: String?): Boolean = path != null && commandFor(path) != null

    /** Type the run command for [path] into the active shell, and bring the terminal up. */
    fun run(context: Context, path: String) {
        val command = commandFor(path) ?: return
        val dir = path.substringBeforeLast('/').ifEmpty { "/" }
        if (TerminalSessions.shells().isEmpty()) TerminalSessions.openShell(context)
        // Ctrl+C first: Run while the last run is still going - a server, say - means run it
        // again, and without this the command was typed into that program's input. Then
        // `clear`, so the output on screen is this run's, not the last one's.
        TerminalSessions.writeToActive("\u0003")
        // A beat before the command: bash resets its prompt after the interrupt, and input
        // that arrives during that ate the first character ("lear: command not found",
        // caught on device).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ typeCommand(dir, command) }, 200)
        _showTerminal.value++
    }

    private fun typeCommand(dir: String, command: String) {
        TerminalSessions.writeToActive("clear; cd ${sq(dir)} && $command\n")
    }
}
