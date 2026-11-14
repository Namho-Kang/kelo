package chat.kelo.desktop.chat

import chat.kelo.app.agent.PendingApproval
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.TurnEvent

/**
 * A site's check whether a person is there, handed to the user (the human_check turn event and pending item), as the chat shows
 * it: the card offers "solve it" (the tab's live view with control taken; the server ends the card by itself once the check is
 * gone) and "skip this site" (POST /api/approvals/{id} {"approve": false}). Also the approval titles: the server's whole question
 * when it sends one.
 */
object Handover {
    /** The tool name of a human check's approval card (ChatItem.Approval keeps no kind) */
    const val CHECK_TOOL = "browser_check"

    /** The server's question for an approval card's title ("로그인할까요?"), or null (an older server: the title is built as before). */
    fun question(ask: String): String? = ask.trim().ifEmpty { null }

    fun isCheck(a: ChatItem.Approval): Boolean = a.name == CHECK_TOOL
}
