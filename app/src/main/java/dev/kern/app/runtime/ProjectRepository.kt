package dev.kern.app.runtime

import android.content.Context

/**
 * Native view of the guest Linux filesystem for project management: list projects, browse
 * directories, clone repositories, and remember what was opened recently.
 *
 * Everything runs through [LinuxRuntime], so the native UI never needs the workbench
 * or a terminal to answer "what projects exist".
 */
object ProjectRepository {

    const val HOME = "/root"
    const val PROJECTS_DIR = "$HOME/projects"

    private const val PREFS = "kern"
    private const val KEY_RECENTS = "recent_projects"
    private const val KEY_CURRENT = "current_folder"
    private const val MAX_RECENTS = 8

    data class Entry(val name: String, val path: String, val isRepo: Boolean)

    /** Directories directly under [path], marking which are git repositories. */
    suspend fun list(context: Context, path: String = PROJECTS_DIR): List<Entry> {
        // One shell round-trip for the whole listing: "name<TAB>isRepo".
        val script = """
            mkdir -p ${sq(PROJECTS_DIR)} 2>/dev/null
            cd ${sq(path)} 2>/dev/null || exit 1
            for d in */ ; do
              [ -d "${'$'}d" ] || continue
              n=${'$'}{d%/}
              if [ -d "${'$'}n/.git" ]; then echo "${'$'}n	1"; else echo "${'$'}n	0"; fi
            done
        """.trimIndent()

        val result = LinuxRuntime.run(context, script) ?: return emptyList()
        return result.lines.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
            val name = parts[0]
            Entry(
                name = name,
                path = if (path.endsWith('/')) "$path$name" else "$path/$name",
                isRepo = parts.getOrNull(1) == "1",
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Create an empty project folder and, optionally, start a git repository in it.
     *
     * The counterpart to [clone]: not everything starts life on a remote, and without
     * this the only ways to get a workspace were to clone one or to make the directory
     * by hand in the terminal.
     */
    suspend fun create(context: Context, name: String, initGit: Boolean): Outcome {
        val safe = sanitise(name)
        if (safe.isBlank()) return Outcome.Failure("Give the project a name")

        val target = "$PROJECTS_DIR/$safe"
        // The git check comes before the folder is made, and a failed init takes the folder
        // back out with it: otherwise the retry lands on the EXISTS branch and the user is
        // told the folder already exists, which is a dead end. rmdir, not rm -rf — the
        // folder was created empty moments earlier and nothing of the user's is in it.
        val script = """
            mkdir -p ${sq(PROJECTS_DIR)}
            if [ -e ${sq(target)} ]; then echo EXISTS >&2; exit 2; fi
            ${if (initGit) "command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }" else ""}
            mkdir -p ${sq(target)} || exit 1
            ${if (initGit) "cd ${sq(target)} && git init -q 2>&1 || { rmdir ${sq(target)} 2>/dev/null; exit 4; }" else ""}
        """.trimIndent()

        val result = LinuxRuntime.run(context, script, timeoutMs = 60_000)
            ?: return Outcome.Failure("The Linux environment did not respond")

        return when {
            result.exitCode == 2 -> Outcome.Failure("A folder named \"$safe\" already exists")
            result.exitCode == 3 -> Outcome.Failure(
                "git is not installed yet — check Status, it may still be setting up.",
            )
            result.exitCode == 4 -> Outcome.Failure("Created the folder, but git init failed")
            result.ok -> Outcome.Success(target, safe)
            else -> Outcome.Failure(result.lastLine() ?: "Could not create the folder")
        }
    }

    /** Clone [url] into the projects directory. Returns the new path, or an error string. */
    suspend fun clone(context: Context, url: String, nameOverride: String? = null): Outcome {
        val name = sanitise(nameOverride?.takeIf { it.isNotBlank() } ?: deriveName(url))
        if (name.isBlank()) return Outcome.Failure("Could not work out a folder name")

        val target = "$PROJECTS_DIR/$name"
        val script = """
            mkdir -p ${sq(PROJECTS_DIR)}
            if [ -e ${sq(target)} ]; then echo "EXISTS" >&2; exit 2; fi
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git clone --depth 1 ${sq(url)} ${sq(target)} 2>&1
        """.trimIndent()

        // Clones can be slow on mobile networks; give them room.
        val result = LinuxRuntime.run(context, script, timeoutMs = 600_000)
            ?: return Outcome.Failure("Timed out. The clone may still be running.")

        return when {
            result.exitCode == 2 -> Outcome.Failure("A folder named \"$name\" already exists")
            result.exitCode == 3 -> Outcome.Failure(
                "git is not installed yet — check Status, it may still be setting up.",
            )
            result.ok -> Outcome.Success(target, name)
            else -> Outcome.Failure(result.lastLine() ?: "git clone failed (exit ${result.exitCode})")
        }
    }

    sealed interface Outcome {
        data class Success(val path: String, val name: String) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /** Folder names come from human typing and from URLs; neither is shell-safe. */
    private fun sanitise(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-', '.')

    private fun deriveName(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    // ---- recents -------------------------------------------------------------

    fun recents(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENTS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun rememberOpened(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = (listOf(path) + recents(context).filter { it != path }).take(MAX_RECENTS)
        prefs.edit()
            .putString(KEY_RECENTS, updated.joinToString("\n"))
            .putString(KEY_CURRENT, path)
            .apply()
    }

    fun currentFolder(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT, null)
        // Paths from before the move to an in-app Linux no longer exist; falling back
        // avoids the workbench opening on a missing workspace.
        return saved?.takeIf { it.startsWith("/root") } ?: HOME
    }
}
