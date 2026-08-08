package dev.kern.app.runtime

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

    /**
     * Clone [url] into the projects directory, reporting git's own output to [onProgress].
     *
     * Cancelling the calling job is a supported way out: the half-cloned folder goes with
     * it. It used to stay, and then failed the "already exists" test on every retry of that
     * URL, with nothing in the app able to remove it.
     */
    suspend fun clone(
        context: Context,
        url: String,
        nameOverride: String? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Outcome {
        val name = sanitise(nameOverride?.takeIf { it.isNotBlank() } ?: deriveName(url))
        if (name.isBlank()) return Outcome.Failure("Could not work out a folder name")

        val target = "$PROJECTS_DIR/$name"
        // The "does it exist already" test is its own round trip so that the cleanup below
        // knows the folder is ours to remove. Folded into the clone script it could not: a
        // cancel arriving in the first moments would rm -rf a project that was already
        // there and had nothing to do with this clone.
        val existing = LinuxRuntime.run(
            context,
            "mkdir -p ${sq(PROJECTS_DIR)}; [ -e ${sq(target)} ]",
            timeoutMs = 30_000,
        ) ?: return Outcome.Failure("The Linux environment did not respond")
        if (existing.ok) return Outcome.Failure("A folder named \"$name\" already exists")

        // --progress or git says nothing at all: it only draws progress when stderr is a
        // terminal, and both streams here are files the runtime tails.
        val script = """
            command -v git >/dev/null 2>&1 || { echo NOGIT >&2; exit 3; }
            git clone --progress --depth 1 ${sq(url)} ${sq(target)} 2>&1
        """.trimIndent()

        // Clones can be slow on mobile networks; give them room.
        val result = try {
            LinuxRuntime.run(context, script, timeoutMs = 600_000, onLine = onProgress)
        } catch (cancelled: CancellationException) {
            removePartial(context, target)
            throw cancelled
        }
        if (result == null) {
            removePartial(context, target)
            return Outcome.Failure("The clone took too long and was stopped")
        }

        return when {
            result.exitCode == 3 -> Outcome.Failure(
                "git is not installed yet — check Status, it may still be setting up.",
            )
            result.ok -> Outcome.Success(target, name)
            else -> {
                // git clears up after itself when it fails cleanly, but not when the
                // failure lands mid-checkout, and never when it was killed.
                removePartial(context, target)
                Outcome.Failure(result.lastLine() ?: "git clone failed (exit ${result.exitCode})")
            }
        }
    }

    /**
     * Remove a project folder for good.
     *
     * Nothing in the app could do this, which is what made every other accident permanent:
     * a clone that stopped halfway, a folder a failed create left behind, a repository that
     * arrived broken. The only ways out were rm -rf in the terminal or the workbench's
     * explorer menu, and both are hostile one-handed.
     */
    suspend fun delete(context: Context, name: String): Outcome {
        val safe = name.trim()
        // Refused rather than sanitised: this is an rm -rf, and a name "corrected" into a
        // different one would delete a different project. Barring the separator and the two
        // dot names is what keeps the path a direct child of the projects directory.
        if (safe.isBlank() || safe == "." || safe == ".." || safe.contains('/')) {
            return Outcome.Failure("That is not a project folder")
        }

        val target = "$PROJECTS_DIR/$safe"
        val script = """
            [ -d ${sq(target)} ] || { echo MISSING >&2; exit 2; }
            rm -rf ${sq(target)}
        """.trimIndent()

        val result = LinuxRuntime.run(context, script, timeoutMs = 300_000)
            ?: return Outcome.Failure("The Linux environment did not respond")

        return when {
            result.exitCode == 2 -> Outcome.Failure("\"$safe\" is not there any more")
            result.ok -> {
                forget(context, target)
                Outcome.Success(target, safe)
            }
            else -> Outcome.Failure(result.lastLine() ?: "Could not delete \"$safe\"")
        }
    }

    sealed interface Outcome {
        data class Success(val path: String, val name: String) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /**
     * Take a clone's target back out after it failed or was cancelled.
     *
     * [NonCancellable] because the usual reason we are here is that our own job was just
     * cancelled, and a cleanup cancelled along with it cleans nothing - which is exactly
     * the state that left a folder no one could remove from inside the app.
     */
    private suspend fun removePartial(context: Context, target: String) {
        withContext(NonCancellable) {
            LinuxRuntime.run(context, "rm -rf ${sq(target)}", timeoutMs = 120_000)
        }
    }

    /** Folder names come from human typing and from URLs; neither is shell-safe. */
    // internal rather than private so ProjectNameTest can exercise it directly.
    /**
     * Reduce a typed name or a URL's last segment to one safe path component.
     *
     * Letters and digits in ANY script, not just ASCII. An ASCII-only allowlist turned a
     * repository whose name is written in Chinese, Arabic, Cyrillic or Greek into a run of
     * dashes and then into nothing, so the app answered "could not work out a folder name"
     * for a URL git would have cloned without complaint — and for a name the user had typed
     * correctly. Linux filenames are bytes and git, tar and code-server all handle UTF-8;
     * the app was the only thing that did not.
     *
     * Safety does not come from the alphabet: `/` is still not a letter, leading dots and
     * dashes are still trimmed, and every use of the result is quoted with [sq] before it
     * reaches a shell.
     */
    internal fun sanitise(raw: String): String =
        raw.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "-")
            .trim('-', '.')

    // internal rather than private so ProjectNameTest can exercise it directly.
    internal fun deriveName(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    // ---- recents -------------------------------------------------------------

    fun recents(context: Context): List<String> =
        Prefs.of(context)
            .getString(Prefs.KEY_RECENTS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun rememberOpened(context: Context, path: String) {
        val prefs = Prefs.of(context)
        val updated = (listOf(path) + recents(context).filter { it != path }).take(MAX_RECENTS)
        prefs.edit()
            .putString(Prefs.KEY_RECENTS, updated.joinToString("\n"))
            .putString(Prefs.KEY_CURRENT_FOLDER, path)
            .apply()
    }

    /** A deleted folder left in here is a row that opens a workspace that is gone. */
    private fun forget(context: Context, path: String) {
        val prefs = Prefs.of(context)
        val kept = recents(context).filter { it != path }
        val edit = prefs.edit().putString(Prefs.KEY_RECENTS, kept.joinToString("\n"))
        // and the workbench would reopen on it at next launch.
        if (prefs.getString(Prefs.KEY_CURRENT_FOLDER, null) == path) {
            edit.putString(Prefs.KEY_CURRENT_FOLDER, HOME)
        }
        edit.apply()
    }

    /**
     * Forget every remembered path, for a guest that has been deleted.
     *
     * These are paths inside the rootfs, and the prefs outlive it: a reinstall made them
     * resolvable again while naming projects that are not there, so a fresh guest opened
     * on the deleted one's recents list.
     */
    fun forgetAll(context: Context) {
        Prefs.of(context).edit()
            .remove(Prefs.KEY_RECENTS)
            .remove(Prefs.KEY_CURRENT_FOLDER)
            .apply()
    }

    fun currentFolder(context: Context): String {
        val saved = Prefs.of(context).getString(Prefs.KEY_CURRENT_FOLDER, null)
        // Paths from before the move to an in-app Linux no longer exist; falling back
        // avoids the workbench opening on a missing workspace.
        return saved?.takeIf { it.startsWith("/root") } ?: HOME
    }
}
