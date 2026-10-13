package dev.kern.app.terminal

import android.content.Context
import dev.kern.app.guest.LinuxRuntime
import dev.kern.app.guest.PtyProcess
import dev.kern.app.guest.sq
import dev.kern.app.projects.ProjectRepository
import java.io.File

/**
 * The guest processes a terminal tab runs: a login shell inside tmux, or an agent command.
 *
 * Split from [LinuxRuntime], which stays the guest itself. Everything here exists for the
 * terminal - the tmux session naming, the tmux config it needs, and where a shell starts.
 */
object GuestShells {

    /**
     * Spawn an arbitrary command on its own pty.
     *
     * Backs the agent cockpit. A login shell so the guest's profile is loaded — agents
     * are usually installed by a package manager that puts them somewhere only a login
     * shell knows about — and `exec` so the command replaces bash rather than leaving a
     * shell waiting behind it, which would keep the pty open after the agent exits.
     */
    fun spawnCommand(
        context: Context,
        command: String,
        columns: Int,
        rows: Int,
        workingDir: String = ProjectRepository.currentFolder(context),
    ): PtyProcess? = LinuxRuntime.spawnInGuest(
        context,
        // not sq(): the user's own agent command line must stay a command, not a word.
        listOf("/bin/bash", "-lc", "exec $command"),
        workingDir,
        columns = columns,
        rows = rows,
    )

    /** Spawn an interactive login shell on its own pty — this backs the terminal. */
    fun spawnShell(
        context: Context,
        columns: Int,
        rows: Int,
        /**
         * Distinct per terminal. `tmux new-session -A` attaches to an existing session of
         * the same name, so a shared name meant every new tab attached to the first one
         * and showed identical output — several terminals that were all the same terminal.
         */
        sessionName: String = "kern",
        /**
         * The open project, not the home directory. A terminal that opens somewhere other
         * than the thing you are working on just means typing `cd` before every session.
         */
        workingDir: String = ProjectRepository.currentFolder(context),
    ): PtyProcess? {
        // tmux here is plumbing, not UI: the native tab strip is what says which shell
        // this is, so the status bar only eats a terminal row repeating it in green.
        // Seeded as a file rather than set inline on every attach, and only when absent,
        // so it is the user's file: edit it to turn the bar back on and Kern will never
        // touch it again.
        runCatching {
            val conf = File(LinuxRuntime.rootfsDir(context), "root/.tmux.conf")
            if (!conf.exists()) conf.writeText("set -g status off\n")
        }

        // tmux 3.5+ guards its server socket with a uid ACL, and the uids here can
        // never match on their own: every terminal is its own PRoot, the server
        // believes it is uid 0, and the kernel reports the app's real uid for any
        // client arriving from a sibling PRoot. Ubuntu 26.04's tmux 3.6 enforces the
        // check where 24.04's did not, so the second tab and every reattach died at
        // birth with "access not allowed" — and died silently, because a shell that
        // exits with living siblings just loses its tab. Teaching tmux the app's real
        // identity takes two lines: a passwd entry naming the uid, and a system-wide
        // conf admitting it. /etc/tmux.conf rather than the user's ~/.tmux.conf,
        // because this is load-bearing plumbing, not a preference — verified on
        // device: with these two lines a fresh PRoot client attaches to a surviving
        // session, without them it is refused.
        runCatching {
            val uid = android.os.Process.myUid()
            val passwd = File(LinuxRuntime.rootfsDir(context), "etc/passwd")
            if (passwd.isFile && passwd.readLines().none { it.startsWith("kernhost:") }) {
                passwd.appendText("kernhost:x:$uid:$uid::/root:/bin/bash\n")
            }
            // Appended when missing rather than written whole on every spawn, which wiped
            // anything else put in the system-wide conf each time a tab opened.
            val conf = File(LinuxRuntime.rootfsDir(context), "etc/tmux.conf")
            val existing = if (conf.isFile) conf.readLines().map { it.trim() } else emptyList()
            listOf(
                "server-access -a kernhost",
                // The status bar is off, so the native tab is the only place left to say
                // what a shell is doing. tmux sends it as the outer terminal's title.
                "set -g set-titles on",
                "set -g set-titles-string '#{pane_current_command} · #{b:pane_current_path}'",
            ).filter { it !in existing }.forEach { conf.appendText("$it\n") }
        }

        return LinuxRuntime.spawnInGuest(
            context,
            // tmux keeps the session alive across terminal detach/reattach. `-c` so the
            // session it creates starts in the project too, not just the bash that ran it.
            listOf(
                "/bin/bash",
                "-lc",
                "tmux new-session -A -s ${sq(sessionName)} -c ${sq(workingDir)} || exec bash -l",
            ),
            workingDir,
            columns = columns,
            rows = rows,
        )
    }
}
