package chat.kelo.app.chat

/**
 * Where the chat list is and what the "↓" pill shows. The list is drawn newest first from the bottom (reverseLayout), so index 0
 * is the newest row and "at the bottom" means index 0 with (almost) no offset.
 */
object ChatScroll {
    fun atBottom(firstIndex: Int, firstOffsetPx: Int, slopPx: Int): Boolean = firstIndex == 0 && firstOffsetPx <= slopPx

    /** Whether new rows should pull the list down: only when the reader was already near the newest, or just sent something. */
    fun follow(firstIndex: Int, lastIsMine: Boolean): Boolean = lastIsMine || firstIndex <= 1

    /** The dot on the pill: lit when the assistant wrote more while the reader was up the list, cleared at the bottom. */
    fun unread(was: Boolean, atBottom: Boolean, replyGrew: Boolean): Boolean = when {
        atBottom -> false
        replyGrew -> true
        else -> was
    }

    /**
     * Changes whenever the assistant's output grows (a new row from it, or more text in its newest message); rows the user adds
     * do not count, and neither do older messages loaded above. -1 for nothing yet.
     */
    fun replySignature(items: List<ChatItem>): Long {
        val last = items.lastOrNull { !(it is ChatItem.Msg && it.mine) } ?: return -1
        val len = (last as? ChatItem.Msg)?.text?.length ?: 0
        return (last.key.hashCode().toLong() shl 32) or len.toLong()
    }
}
