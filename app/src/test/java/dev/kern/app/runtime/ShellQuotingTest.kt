package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sq() is the only thing standing between the guest's bash and a string the app did not
 * write. Project names, branch names, commit messages and clone URLs all get pasted into
 * script templates that are then run as root inside the rootfs, so a value that escapes
 * its quotes is arbitrary code execution against the user's own files.
 *
 * These tests check sq() against a model of the shell rather than against its own source.
 * [shellWord] knows only the three rules bash applies to an unexpanded word - a single
 * quote opens a literal run, a backslash outside quotes escapes one character, anything
 * else unquoted has meaning - so any future rewrite of sq() has to satisfy the shell, not
 * merely resemble the version that was here when the tests were written.
 */
class ShellQuotingTest {

    @Test
    fun `every hostile value comes back out of the shell exactly as it went in`() {
        for (value in HOSTILE_VALUES) {
            val word = shellWord(sq(value))
            assertNotNull("sq(${describe(value)}) is not a single safe shell word", word)
            assertEquals("sq(${describe(value)}) did not survive the round trip", value, word)
        }
    }

    @Test
    fun `a value containing a single quote cannot break out of the word`() {
        // The whole reason sq() exists. Without the '\'' dance the closing quote of the
        // template lands here and everything after it is read as syntax.
        val payload = "'; rm -rf / #"
        assertEquals(payload, shellWord(sq(payload)))
    }

    @Test
    fun `a value containing a newline cannot start a second command`() {
        // A branch or project name is allowed to contain a newline, and the script it is
        // pasted into is line-oriented, so an unquoted newline is a free command.
        val payload = "feature\nrm -rf /root/projects"
        assertEquals(payload, shellWord(sq(payload)))
    }

    @Test
    fun `command substitution in a value stays literal text`() {
        // Both spellings, because only one of them looks dangerous at a glance.
        assertEquals("\$(id)", shellWord(sq("\$(id)")))
        assertEquals("`id`", shellWord(sq("`id`")))
        assertEquals("\${HOME}", shellWord(sq("\${HOME}")))
    }

    @Test
    fun `a value containing spaces stays one argument`() {
        // `rm -rf ${sq(target)}` on a project called "My Notes" must delete one directory,
        // not try to delete two.
        val value = "My Notes"
        assertEquals(value, shellWord(sq(value)))
    }

    @Test
    fun `the empty string still occupies a word`() {
        // An empty result would vanish from the command line altogether, turning
        // `[ -e ${sq(path)} ]` into `[ -e ]` - which is a different test that answers
        // true, rather than a test of an empty path that answers false.
        assertEquals("''", sq(""))
        assertEquals("", shellWord(sq("")))
    }

    @Test
    fun `the result already carries its own quotes so a call site must not add more`() {
        val quoted = sq("it's")
        assertTrue("sq() must return a quoted word, not a bare body", quoted.startsWith("'"))
        assertTrue("sq() must return a quoted word, not a bare body", quoted.endsWith("'"))

        // The documented failure mode: a template that writes '${sq(x)}' compiles, looks
        // careful, and hands bash a word with an unbalanced quote - so bash swallows the
        // rest of the script looking for the close. Null here is that runaway.
        assertNull(shellWord("'" + quoted + "'"))
    }

    @Test
    fun `quoting an already quoted value quotes it again rather than unwrapping it`() {
        // sq() is not idempotent and must not try to be: nesting is how a quoted script
        // gets passed to bash -c, and silently detecting "this looks quoted already" is
        // exactly how a value that merely resembles a quoted word gets let through raw.
        val once = sq("it's")
        assertEquals(once, shellWord(sq(once)))
    }

    @Test
    fun `a value that is nothing but quotes and backslashes is still one word`() {
        // The sequence sq() itself emits, fed back in. An implementation that escaped
        // only the first quote, or that treated a backslash as an escape inside single
        // quotes (bash does not), fails right here.
        for (value in listOf("'", "''", "'\\''", "\\", "\\'", "a'b'c")) {
            assertEquals(value, shellWord(sq(value)))
        }
    }

    @Test
    fun `a non-ASCII project name is passed through byte for byte`() {
        // Written with escapes to keep this file ASCII; the values are ordinary things to
        // call a folder.
        val values = listOf("caf\u00e9", "\u65e5\u672c\u8a9e", "na\u00efve repo")
        for (value in values) {
            assertEquals(value, shellWord(sq(value)))
        }
    }

    @Test
    fun `the shell model rejects the quoting mistakes sq exists to avoid`() {
        // Everything above is only worth running if [shellWord] would actually notice a
        // wrong sq(). These are the rewrites someone reaches for, and every one is a
        // hole; if any of them starts round-tripping, the model has gone blind and the
        // rest of this file has quietly stopped testing anything.
        val broken = listOf<Pair<String, (String) -> String>>(
            "no escaping" to { v -> "'" + v + "'" },
            "double quotes" to { v -> "\"" + v + "\"" },
            "backslash inside single quotes" to { v -> "'" + v.replace("'", "\\'") + "'" },
            "only the first quote escaped" to { v -> "'" + v.replaceFirst("'", "'\\''") + "'" },
            "no quotes at all" to { v -> v },
        )
        val value = "a'b'c"
        for ((name, quote) in broken) {
            assertNotEquals("shellWord() accepted $name", value, shellWord(quote(value)))
        }
    }

    // ---- a model of bash's word parsing --------------------------------------

    /**
     * Decode [text] as bash would decode a single word, or return null if it is not one.
     *
     * Null means the shell would not read [text] as one self-contained literal word:
     * either a quote is left open, or a character with meaning to the shell is sitting
     * outside quotes. Both are the bug this file exists to catch.
     */
    private fun shellWord(text: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> {
                    val close = text.indexOf('\'', i + 1)
                    // Unterminated: bash keeps consuming the script looking for the close.
                    if (close < 0) return null
                    out.append(text, i + 1, close)
                    i = close + 1
                }
                c == '\\' -> {
                    // A trailing backslash is a line continuation, not a word.
                    if (i + 1 >= text.length) return null
                    out.append(text[i + 1])
                    i += 2
                }
                // Unquoted. Correct sq() output never reaches here, because everything it
                // emits is either inside quotes or a backslash-escaped quote. Anything
                // that does reach here is only safe if bash would treat it as plain text
                // and as part of the same word - which whitespace and metacharacters are
                // not.
                c.isLetterOrDigit() || c in SAFE_BARE -> {
                    out.append(c)
                    i++
                }
                else -> return null
            }
        }
        return out.toString()
    }

    /** Renders control characters, so a failure message points at the right value. */
    private fun describe(value: String): String =
        "\"" + value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private companion object {
        /** The only characters bash reads as ordinary text when they are left unquoted. */
        const val SAFE_BARE = "_-./:@+,%"

        /**
         * Every shape of value the app can be handed: names typed by the user, URLs
         * pasted from a browser, commit messages, and the payloads someone would try.
         */
        val HOSTILE_VALUES = listOf(
            "plain",
            "",
            " ",
            "with space",
            "trailing space ",
            "it's",
            "'",
            "''",
            "'\\''",
            "a'b'c",
            "\\",
            "\\'",
            "\"double quoted\"",
            "\$HOME",
            "\${HOME}",
            "\$(id)",
            "`id`",
            "a;b",
            "a && b",
            "a || b",
            "a | b",
            "a > out",
            "a < in",
            "a & b",
            "*",
            "?",
            "[a-z]",
            "~",
            "#not a comment",
            "!!",
            "-rf",
            "--upload-pack=touch /tmp/pwned",
            "line\nline",
            "tab\there",
            "carriage\rreturn",
            "trailing newline\n",
            "'; rm -rf / ;'",
            "'; rm -rf ~ #",
            "x'\$(touch /tmp/pwned)'x",
            "https://github.com/owner/repo.git",
            "git@github.com:owner/repo.git",
            "/root/projects/My Repo",
            "fix: don't break the build",
        )
    }
}
