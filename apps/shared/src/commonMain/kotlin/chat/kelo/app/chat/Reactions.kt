package chat.kelo.app.chat

/**
 * The emoji on the user's own messages, set by the server (reactions.py): [READ] (👀) once Kelo starts on a message, then one emoji
 * that fits what was done or talked about when the work for it is over (✅ when the model chose none, ⚠️ when the reply failed or
 * was stopped). Work handed to a background job, or waiting for the user's answer, keeps 👀 until it ends.
 *
 * It comes with the stored messages (GET /api/messages `reaction`) and live in a reply's events ({"type": "reaction"}). Plain Kotlin
 * (the desktop app shares it).
 */
object Reactions {
    const val READ = "👀"   // 👀

    /**
     * The row the reaction for message [messageId] goes on: the user's message with that id, else (the message was sent from this
     * phone and has no id on screen yet) the newest of the user's messages without an id. -1 when there is none.
     */
    fun target(items: List<ChatItem>, messageId: Long): Int {
        val byId = items.indexOfLast { it is ChatItem.Msg && it.mine && it.id == messageId }
        if (byId >= 0) return byId
        return items.indexOfLast { it is ChatItem.Msg && it.mine && it.id == null }
    }

    /** Whether [emoji] is the "read" mark (Kelo is still on it), not a final one. */
    fun isRead(emoji: String): Boolean = emoji == READ

    /**
     * Where the reaction chip starts, measured from the bubble's start edge (px or dp, any one unit): [inset] in from the bubble's end
     * edge, or centred under the bubble when that would bring it closer than that to the start edge (a short bubble: "ㅇㅇ", one emoji).
     * The layout places it relative to the start, so in RTL the chip is mirrored to the left end.
     */
    fun chipStart(bubbleWidth: Int, chipWidth: Int, inset: Int): Int = maxOf(bubbleWidth - inset - chipWidth, (bubbleWidth - chipWidth) / 2)

    /** What a screen reader says for the chip: [readWord] ("읽음" / "Read") for 👀, else [reacted] with the emoji filled in. */
    fun spoken(emoji: String, readWord: String, reacted: (String) -> String): String = if (isRead(emoji)) readWord else reacted(emoji)
}
