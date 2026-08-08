package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Between them, deriveName() and sanitise() decide which directory a clone lands in, from
 * a URL nobody in this codebase wrote. Everything downstream trusts the answer: the path is
 * built by string concatenation onto PROJECTS_DIR, handed to mkdir and to git clone, and
 * the same name later comes back to delete() as the argument to an rm -rf.
 *
 * So the property under test is not "the name looks nice". It is that the name can only
 * ever be ONE directory sitting directly inside the projects folder, spelled with
 * characters that mean nothing to bash - or nothing at all, which both callers refuse with
 * a message. sq() is the second line of defence for the quoting half of that; this is the
 * first, and the only defence against a name that walks upwards.
 */
class ProjectNameTest {

    /** What clone() computes when the user did not override the name. */
    private fun folderFor(url: String): String =
        ProjectRepository.sanitise(ProjectRepository.deriveName(url))

    // ---- naming a clone ------------------------------------------------------

    @Test
    fun `a repository URL names the folder after the repository, without the git suffix`() {
        // Otherwise every project in the list reads "repo.git", and the folder no longer
        // matches what the user sees on the forge.
        assertEquals("repo", folderFor("https://github.com/owner/repo.git"))
        assertEquals("repo", folderFor("https://github.com/owner/repo"))
        assertEquals("repo", folderFor("https://user:token@github.com/owner/repo.git"))
        assertEquals("repo", folderFor("https://git.example.com:8443/owner/repo.git"))
    }

    @Test
    fun `trailing slashes are not part of the repository name`() {
        // A URL copied out of a browser address bar routinely has one.
        assertEquals("repo", folderFor("https://github.com/owner/repo/"))
        assertEquals("repo", folderFor("https://github.com/owner/repo///"))
        assertEquals("repo", folderFor("https://github.com/owner/repo.git/"))
        assertEquals("repo", folderFor("  https://github.com/owner/repo.git  "))
        assertEquals("repo", folderFor("https://github.com/owner/repo.git\n"))
    }

    @Test
    fun `an ssh clone address names the folder after the repository`() {
        assertEquals("repo", folderFor("git@github.com:owner/repo.git"))
        assertEquals("repo", folderFor("ssh://git@github.com:22/owner/repo.git"))
        assertEquals("repo", folderFor("git://github.com/owner/repo.git"))
    }

    @Test
    fun `a URL that names no repository yields nothing, so the clone is refused`() {
        // clone() answers "Could not work out a folder name" on a blank result. The
        // alternative is a mkdir of PROJECTS_DIR itself followed by a clone into a
        // directory full of other people's projects.
        val nameless = listOf(
            "",
            "   ",
            "/",
            "//",
            "..",
            "https://github.com/owner/..",
            "https://github.com/owner/repo/../..",
            "https://github.com/owner/.",
            // A repository whose last path segment is literally ".git" would otherwise be
            // stripped to the empty string and then concatenated onto the projects dir.
            "https://github.com/owner/.git",
        )
        for (url in nameless) {
            assertEquals("$url must not name a folder", "", folderFor(url))
        }
    }

    // ---- naming a folder from what someone typed ------------------------------

    @Test
    fun `a name that tries to climb out is flattened into a single folder`() {
        // The separator is not stripped but replaced, so the segments cannot rejoin into a
        // path: "../../etc" must become one oddly-named project, never a write to /etc.
        assertEquals("etc-passwd", ProjectRepository.sanitise("/etc/passwd"))
        assertEquals("etc-cron.d-evil", ProjectRepository.sanitise("../../../etc/cron.d/evil"))
        assertEquals("root-projects-other", ProjectRepository.sanitise("/root/projects/other"))
    }

    @Test
    fun `a name that is nothing but separators and dots yields nothing`() {
        // create() answers "Give the project a name" here. These are the values that would
        // otherwise resolve to the projects directory or to its parent.
        for (name in listOf(".", "..", "...", "./.", "../..", "-", "---", "/", "  ")) {
            assertEquals("\"$name\" must not name a folder", "", ProjectRepository.sanitise(name))
        }
    }

    @Test
    fun `a leading dot is stripped so a project cannot be created invisible`() {
        // list() globs */ and so never shows a dot-directory: a project created as ".work"
        // would exist, occupy the name, and be missing from the only screen that lists
        // projects. Cloning ".github" or ".dotfiles" is a real thing people do.
        assertEquals("config", ProjectRepository.sanitise(".config"))
        assertEquals("hidden", ProjectRepository.sanitise("..hidden"))
        assertEquals("dotfiles", folderFor("https://github.com/owner/.dotfiles"))
    }

    @Test
    fun `a name cannot begin with a dash and be read as an option`() {
        // Every call site quotes with sq(), which stops a name being read as syntax but not
        // as an argument: git clone would still take "--upload-pack=..." as a flag, and
        // that flag runs a command on this machine.
        assertEquals("rf", ProjectRepository.sanitise("-rf"))
        assertEquals(
            "upload-pack-touch--tmp-pwned",
            ProjectRepository.sanitise("--upload-pack=touch /tmp/pwned"),
        )
    }

    @Test
    fun `a typed name keeps the characters that make it recognisable`() {
        // Spaces become a separator rather than disappearing, or "my repo" and "myrepo"
        // become the same folder and the second one is refused as already existing.
        assertEquals("My-Notes", ProjectRepository.sanitise("My Notes"))
        assertEquals("My-Notes", ProjectRepository.sanitise("  My Notes  "))
        assertEquals("my-repo.v2", ProjectRepository.sanitise("my-repo.v2"))
        assertEquals("Kern_app", ProjectRepository.sanitise("Kern_app"))
    }

    // ---- the properties that hold for every input -----------------------------

    @Test
    fun `no typed name and no pasted URL can name anything but a direct child of the projects folder`() {
        for (name in TYPED_NAMES) {
            assertSafeComponent("sanitise(${describe(name)})", ProjectRepository.sanitise(name))
        }
        for (url in URLS) {
            assertSafeComponent("the folder for ${describe(url)}", folderFor(url))
        }
    }

    @Test
    fun `sanitising an already sanitised name changes nothing`() {
        // delete() takes the name back as an rm -rf argument and refuses anything it cannot
        // vouch for rather than correcting it, so a name that shifts on a second pass names
        // a directory that is not the one on disk. Same for a clone retried after a
        // failure: the existence check and the clone must agree on the target.
        for (name in TYPED_NAMES + URLS) {
            val once = ProjectRepository.sanitise(name)
            assertEquals(
                "sanitise() is not settled after one pass for ${describe(name)}",
                once,
                ProjectRepository.sanitise(once),
            )
        }
    }

    /**
     * The contract both callers rely on: either nothing at all - which create() and clone()
     * turn into a message - or one path component made only of characters that are inert to
     * bash and unambiguous to the filesystem.
     */
    private fun assertSafeComponent(source: String, name: String) {
        if (name.isEmpty()) return

        assertFalse("$source contains a path separator: $name", name.contains('/'))
        assertNotEquals("$source is the projects directory itself", ".", name)
        assertNotEquals("$source is the projects directory's parent", "..", name)
        assertFalse("$source is option-shaped: $name", name.startsWith("-"))
        assertFalse("$source is a hidden directory: $name", name.startsWith("."))

        val stray = name.filterNot { isAllowed(it) }
        assertTrue(
            "$source kept characters that are not inert to the shell: ${describe(stray)} in $name",
            stray.isEmpty(),
        )
    }

    /** Renders control characters, so a failure message points at the right value. */
    private fun describe(value: String): String =
        "\"" + value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private companion object {
        /**
         * Everything a filename may be built from. Deliberately spelled out rather than
         * reusing the implementation's regex, so a widening has to disagree with this in
         * the open rather than by both sides changing together.
         *
         * This IS such a widening, and it was made on purpose: letters and digits in any
         * script now count, because an ASCII-only rule reduced a repository named in
         * Chinese, Arabic, Cyrillic or Greek to nothing and made it unclonable. What keeps
         * a name safe was never the alphabet — it is that a separator is not a letter, that
         * leading dots and dashes are trimmed, and that every use is shell-quoted. Those
         * are asserted above and are unchanged.
         */
        fun isAllowed(c: Char): Boolean =
            c.isLetter() || c.isDigit() || c == '.' || c == '_' || c == '-'

        /** What someone might type into the new-project field, including what they should not. */
        val TYPED_NAMES = listOf(
            "",
            " ",
            ".",
            "..",
            "...",
            "-",
            "---",
            "./.",
            "../..",
            "/",
            "//",
            "/etc/passwd",
            "../../../etc/cron.d/evil",
            "..%2f..%2froot",
            ".git",
            ".ssh",
            "..hidden",
            "My Notes",
            "my-repo.v2",
            "repo.",
            "-rf",
            "--upload-pack=touch /tmp/pwned",
            "a'b'c",
            "a; rm -rf /",
            "\$(id)",
            "`id`",
            "\${HOME}",
            "a|b",
            "a&b",
            "a>b",
            "*",
            "?",
            "~",
            "name\nrm -rf /root/projects",
            "tab\there",
            "carriage\rreturn",
            // Written with escapes to keep this file ASCII; ordinary things to call a folder.
            "caf\u00e9",
            "\u65e5\u672c\u8a9e",
            "na\u00efve repo",
        )

        /** Every URL shape the clone field can be handed, and the ones it should not be. */
        val URLS = listOf(
            "https://github.com/owner/repo.git",
            "https://github.com/owner/repo",
            "https://github.com/owner/repo/",
            "https://github.com/owner/repo///",
            "https://github.com/owner/repo.git/",
            "git@github.com:owner/repo.git",
            // scp-style with no path at all: the host is the only thing before the name.
            "git@github.com:repo.git",
            "ssh://git@github.com:22/owner/repo.git",
            "https://git.example.com:8443/owner/repo.git",
            "https://github.com/owner/repo.git?ref=main",
            "https://github.com/owner/repo#readme",
            "https://user:token@github.com/owner/repo.git",
            "file:///etc/passwd",
            "https://github.com/owner/..",
            "https://github.com/owner/repo/../..",
            "https://github.com/owner/.git",
            // Percent-encoded traversal: nothing here decodes, and nothing should.
            "https://github.com/owner/%2e%2e%2fetc",
            "https://github.com/owner/my repo.git",
            "https://github.com/owner/\u65e5\u672c\u8a9e.git",
            "https://github.com/owner/repo\nrm -rf /root",
            "-u ext::sh -c touch% /tmp/pwned",
            "",
            "   ",
            "/",
            "..",
        )
    }
}
