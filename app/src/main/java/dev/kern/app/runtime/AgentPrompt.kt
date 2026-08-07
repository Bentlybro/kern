package dev.kern.app.runtime

/**
 * Reading an agent's screen: what it last said, and whether that was a question.
 *
 * Kept out of the session registry because none of it touches a terminal — it is text
 * matching, and it is the part most likely to change, since every CLI agent phrases
 * "shall I?" differently and the pattern below is openly a guess.
 */
object AgentPrompt {

    private val PATTERN = Regex(
        "(\\?\\s*$)|(\\[y/n\\])|(\\(y/N\\))|(yes/no)|(continue\\??)|(approve)|(permission)",
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
