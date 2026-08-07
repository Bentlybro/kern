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
            mkdir -p '$PROJECTS_DIR' 2>/dev/null
            cd '$path' 2>/dev/null || exit 1
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

    /** Clone [url] into the projects directory. Returns the new path, or an error string. */
    suspend fun clone(context: Context, url: String, nameOverride: String? = null): CloneResult {
        val name = (nameOverride?.takeIf { it.isNotBlank() } ?: deriveName(url))
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        if (name.isBlank()) return CloneResult.Failure("Could not work out a folder name")

        val target = "$PROJECTS_DIR/$name"
        val script = """
            mkdir -p '$PROJECTS_DIR'
            if [ -e '$target' ]; then echo "EXISTS" >&2; exit 2; fi
            command -v git >/dev/null 2>&1 || pkg install -y git >/dev/null 2>&1
            git clone --depth 1 '$url' '$target' 2>&1
        """.trimIndent()

        // Clones can be slow on mobile networks; give them room.
        val result = LinuxRuntime.run(context, script, timeoutMs = 600_000)
            ?: return CloneResult.Failure("Timed out. The clone may still be running in Termux.")

        return when {
            result.exitCode == 2 -> CloneResult.Failure("A folder named \"$name\" already exists")
            result.ok -> CloneResult.Success(target, name)
            else -> {
                val detail = (result.stdout + "\n" + result.stderr)
                    .split('\n').map { it.trim() }.lastOrNull { it.isNotEmpty() }
                CloneResult.Failure(detail ?: "git clone failed (exit ${result.exitCode})")
            }
        }
    }

    sealed interface CloneResult {
        data class Success(val path: String, val name: String) : CloneResult
        data class Failure(val message: String) : CloneResult
    }

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
