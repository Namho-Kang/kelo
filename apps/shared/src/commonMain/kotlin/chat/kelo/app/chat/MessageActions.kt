package chat.kelo.app.chat

/** What a long press on a chat bubble offers. */
enum class MsgAction { COPY, SHARE, SELECT, RETRY, EDIT, REPORT }

/** Which actions a bubble's long-press menu shows, and what 다시 시도 sends; kept apart from the UI so it can be tested. */
object MessageActions {
    /** [actions] as this app can do them: without 공유 when it has no way to share a text (the desktop, for now). */
    fun offered(actions: List<MsgAction>, canShare: Boolean): List<MsgAction> = if (canShare) actions else actions.filter { it != MsgAction.SHARE }

    /**
     * The actions for the row [key]: the user's own message can be copied, shared, selected and edited into the composer; a reply can
     * be copied, shared, selected, when it is the newest reply tried again, and reported to the Kelo developer (신고하기); an error
     * can be copied and tried again. Nothing is tried again while a reply is in progress.
     */
    fun actions(items: List<ChatItem>, key: String, busy: Boolean): List<MsgAction> {
        val item = items.firstOrNull { it.key == key } ?: return emptyList()
        val retry = !busy && retryText(items, key) != null
        return when (item) {
            is ChatItem.Msg ->
                if (item.mine) listOf(MsgAction.COPY, MsgAction.SHARE, MsgAction.SELECT, MsgAction.EDIT)
                else listOfNotNull(MsgAction.COPY, MsgAction.SHARE, MsgAction.SELECT, MsgAction.RETRY.takeIf { retry }, MsgAction.REPORT)
            is ChatItem.Error -> listOfNotNull(MsgAction.COPY, MsgAction.RETRY.takeIf { retry })
            else -> emptyList()
        }
    }

    /**
     * The user message that 다시 시도 on [key] sends again: the newest message of the user before it. Only for the newest reply
     * (no message of the user after it, and no newer reply) or an error with no message of the user after it; null otherwise.
     */
    fun retryText(items: List<ChatItem>, key: String): String? {
        val at = items.indexOfFirst { it.key == key }
        if (at < 0) return null
        val item = items[at]
        val later = items.subList(at + 1, items.size)
        if (later.any { it is ChatItem.Msg && it.mine }) return null
        when (item) {
            is ChatItem.Msg -> if (item.mine || later.any { it is ChatItem.Msg }) return null
            is ChatItem.Error -> {}
            else -> return null
        }
        val asked = items.subList(0, at).lastOrNull { it is ChatItem.Msg && it.mine } as? ChatItem.Msg ?: return null
        return asked.text.takeIf { it.isNotBlank() }
    }
}
