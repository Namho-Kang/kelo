package chat.kelo.app.chat

/**
 * A message the server refused with 409 `secret_in_message`: [text] is exactly what was sent (the spans point into it), with the
 * [answers] it carried. [fromComposer]: it was the typed text (put back in the composer, with the attachments when [tookAttachments]).
 * Memory only; never printed (toString leaves the text out).
 */
class SecretAsk(
    val found: SecretFound, val text: String, val answers: List<ReplyAnswer>, val fromComposer: Boolean, val tookAttachments: Boolean,
    /** The composer's text as it was put back, and the spans moved onto it (empty for a button's text) */
    val composer: String = "", val composerSpans: List<SecretSpan> = emptyList(),
) {
    /** The detected values, for the save step's field (cut from [text]; memory only) */
    val values: List<String> get() = SecretGuard.values(text, found.spans)
    override fun toString(): String = "SecretAsk(${found.target.type}, ${found.spans.size} spans)"
}
