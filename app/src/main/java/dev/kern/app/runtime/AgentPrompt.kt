package dev.kern.app.runtime

/**
 * Reading an agent's screen: what it last said, and whether that was a question.
 *
 * Kept out of the session registry because none of it touches a terminal — it is text
 * matching, and it is the part most likely to change, since every CLI agent phrases
 * "shall I?" differently and the pattern below is openly a guess.
 */
object AgentPrompt {

    /**
     * Every alternative here is a shape a question takes, not merely a word it contains.
     *
     * `permission` and `approve` were once matched bare, and both had to be narrowed.
     * "Permission denied" is among the most common last lines a Linux terminal ever shows
     * — `git@github.com: Permission denied (publickey).` is the likeliest way a push fails
     * in this app, and `bash: ./gradlew: Permission denied` the next — so the bare word
     * raised "your agent is waiting for you" on exactly the screens where the user was
     * already having a bad time. `approve` did the same for "Approved" in ordinary output.
     * A notification that cries wolf is worse than one that never fires, because the next
     * real one gets ignored too.
     *
     * `permission to` keeps the agent asking to act ("asking for permission to write
     * outside the project") while dropping the denial, and the word boundary on `approve`
     * keeps the imperative while dropping the past tense.
     */
    private val PATTERN = Regex(
        "(\\?\\s*$)|(\\[y/n\\])|(\\(y/n\\))|(yes/no)|(press enter)|(continue\\?)" +
            "|(\\bapprove\\b)|(permission to\\b)",
        RegexOption.IGNORE_CASE,
    )

    /** The last thing the screen shows, ignoring the blank lines a TUI pads itself with. */
    fun lastLine(screen: String): String? =
        screen.lines().lastOrNull { it.isNotBlank() }?.trim()

    /**
     * Guess whether the agent is waiting on the user. Heuristic by necessity, since
     * agents do not announce it, but it only drives a notification so a wrong guess is
     * cheap.
     */
    fun awaitingInput(screen: String): Boolean {
        val last = lastLine(screen) ?: return false
        return PATTERN.containsMatchIn(last)
    }
}
