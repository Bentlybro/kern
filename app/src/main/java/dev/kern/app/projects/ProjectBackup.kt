package dev.kern.app.projects

import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.guest.StorageManager
import dev.kern.app.guest.sq
import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Getting your work out of the app, and back in.
 *
 * The guest lives in app-private storage and is deliberately kept out of Android backup
 * (see data_extraction_rules.xml), so before this an uninstall, a cleared app, or Delete
 * Linux took every unpushed commit and uncommitted file with it. git protects what has
 * been pushed; this protects the rest, as one .tar.gz the user puts wherever they like
 * through the system file picker.
 *
 * What goes in is ~/projects plus the few dotfiles that make the environment yours. What
 * stays out is what can be regenerated (node_modules, virtualenvs, build caches, which
 * would otherwise dwarf the code) and credentials: the archive lands on shared storage,
 * and a GitHub token or an SSH key does not belong in a file any app with storage access
 * can read. Signing in again takes one tap.
 */
object ProjectBackup {

    private const val STAGED = "kern-backup.tar.gz"

    /** Paths under /root that are worth keeping, when they exist. */
    private val INCLUDE = listOf(
        "projects",
        ".gitconfig",
        ".bashrc",
        ".profile",
        ".tmux.conf",
        ".local/share/code-server/User/settings.json",
        ".local/share/code-server/User/keybindings.json",
        ".local/share/code-server/User/snippets",
    )

    private val EXCLUDE = listOf(
        "node_modules", ".venv", "venv", "__pycache__", ".gradle", ".next", "target/debug",
        "target/release", ".cache",
    )

    /** Archive into the guest's /tmp, then copy out to [target]. Returns what to tell the user. */
    suspend fun export(context: Context, target: Uri): String = withContext(Dispatchers.IO) {
        val staged = File(LinuxRuntime.guestTmpDir(context), STAGED)
        try {
            val script = buildString {
                appendLine("cd /root || exit 1")
                appendLine("set --")
                INCLUDE.forEach { appendLine("[ -e ${sq(it)} ] && set -- \"\$@\" ${sq(it)}") }
                appendLine("[ \$# -gt 0 ] || { echo NOTHING >&2; exit 2; }")
                append("tar -czf /tmp/$STAGED")
                EXCLUDE.forEach { append(" --exclude=${sq(it)}") }
                appendLine(" \"\$@\" 2>&1")
            }
            // Big trees on phone storage are slow; give it the same room a clone gets.
            val r = LinuxRuntime.run(context, script, timeoutMs = 600_000)
                ?: return@withContext "Backup timed out"
            if (r.exitCode == 2) return@withContext "Nothing to back up yet"
            // tar exits 1 for "a file changed while being read", which on a live
            // workspace is routine and still produces a complete archive.
            if (r.exitCode > 1 || !staged.isFile) {
                return@withContext "Backup failed: ${r.lastLine() ?: "exit ${r.exitCode}"}"
            }

            val out = context.contentResolver.openOutputStream(target, "wt")
                ?: return@withContext "Could not write to that location"
            out.use { sink -> staged.inputStream().use { it.copyTo(sink) } }
            // KB below a megabyte: an empty ~/projects with dotfiles is ~2 KB, and "0 MB"
            // read as a backup that had saved nothing.
            val bytes = staged.length()
            val size = if (bytes < 1024 * 1024) {
                "${(bytes + 1023) / 1024} KB"
            } else {
                StorageManager.format(bytes / (1024 * 1024))
            }
            "Backed up $size"
        } catch (e: Exception) {
            "Backup failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            staged.delete()
        }
    }

    /**
     * Unpack an export back into /root. Files in the archive replace files of the same name;
     * anything not in it is left alone, so this never deletes work.
     */
    suspend fun restore(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val staged = File(LinuxRuntime.guestTmpDir(context), STAGED)
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: return@withContext "Could not read that file"
            input.use { src -> staged.outputStream().use { src.copyTo(it) } }

            // Refuse anything that would land outside /root: an archive is a list of paths
            // chosen by whoever made it, and this one came from shared storage.
            val script = """
                tar -tzf /tmp/$STAGED > /tmp/kern-backup.list 2>/dev/null || { echo NOTTAR >&2; exit 2; }
                if grep -qE '^/|(^|/)\.\.(/|${'$'})' /tmp/kern-backup.list; then echo UNSAFE >&2; exit 3; fi
                tar -xzf /tmp/$STAGED -C /root 2>&1
                rc=${'$'}?
                rm -f /tmp/kern-backup.list
                exit ${'$'}rc
            """.trimIndent()
            val r = LinuxRuntime.run(context, script, timeoutMs = 600_000)
                ?: return@withContext "Restore timed out"
            when (r.exitCode) {
                0 -> "Restored into ~/ — open it from projects"
                2 -> "That is not a Kern backup (.tar.gz)"
                3 -> "Refused: that archive writes outside your home folder"
                else -> "Restore failed: ${r.lastLine() ?: "exit ${r.exitCode}"}"
            }
        } catch (e: Exception) {
            "Restore failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            staged.delete()
        }
    }
}
