package chat.kelo.app.ui.components

/**
 * Long unbroken tokens (ids, model names, file names, hosts, e-mail addresses) must never be split in the middle of a word by
 * line wrapping. Text that shows them is kept to one line with an ellipsis, and a token longer than fits a row is shortened in
 * the middle here ("claude-subscripti…experimental"), so both of its ends stay readable.
 */
object TokenText {
    /** [text] with every run of non-space characters longer than [max] cut in the middle. */
    fun fit(text: String, max: Int = ROW_MAX): String {
        if (text.length <= max) return text
        return Regex("\\S{${max + 1},}").replace(text) { m -> middle(m.value, max) }
    }

    /** One token cut in the middle to [max] characters (the ellipsis included); shorter ones are left alone. */
    fun middle(token: String, max: Int = ROW_MAX): String {
        if (token.length <= max || max < 3) return token
        val keep = max - 1
        val head = (keep + 1) / 2
        return token.take(head) + "…" + token.takeLast(keep - head)
    }

    /** Whether [text] is a single token (no spaces): such text gets one line with an ellipsis, never a wrap. */
    fun isToken(text: String): Boolean = text.isNotEmpty() && text.none { it.isWhitespace() }

    /** about what a row's text fits on a phone in one line */
    const val ROW_MAX = 32
}
