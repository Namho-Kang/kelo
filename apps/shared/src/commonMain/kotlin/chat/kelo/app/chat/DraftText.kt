package chat.kelo.app.chat


/** Shared text joining what is already typed. */
object DraftText {
    fun append(draft: String, text: String): String = when {
        text.isBlank() -> draft
        draft.isBlank() -> text
        else -> draft.trimEnd() + "\n" + text
    }
}

