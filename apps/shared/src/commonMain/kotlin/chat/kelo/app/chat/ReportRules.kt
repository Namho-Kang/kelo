package chat.kelo.app.chat

import chat.kelo.app.res.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

/** Why a reply is reported; [wire] is what the server takes. */
enum class ReportReason(val wire: String, val label: StringResource) {
    HARMFUL("harmful", Res.string.report_reason_harmful),
    INACCURATE("inaccurate", Res.string.report_reason_inaccurate),
    PRIVACY("privacy", Res.string.report_reason_privacy),
    OTHER("other", Res.string.report_reason_other);

    companion object {
        fun of(wire: String): ReportReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** What the sheet holds; kept when sending fails or the sheet is closed, so the report can be sent again later. */
data class ReportDraft(val reason: ReportReason? = null, val comment: String = "", val includeAsked: Boolean = false)

/** The report's texts and request body, apart from the UI so they can be tested. Nothing but these fields leaves the phone. */
object ReportRules {
    const val MAX_REPLY = 8000
    const val MAX_ASKED = 4000
    const val MAX_COMMENT = 1000

    /** The whole reply of [key], without Markdown marks, as the user saw it. */
    fun replyText(items: List<ChatItem>, key: String): String? {
        val m = items.firstOrNull { it.key == key } as? ChatItem.Msg ?: return null
        if (m.mine) return null
        return Markdown.splitBubbles(m.text).joinToString("\n\n") { Markdown.plain(it) }.trim().take(MAX_REPLY).ifBlank { null }
    }

    /** The user's own message right before the reply [key] (the one it answered), or null. */
    fun askedBefore(items: List<ChatItem>, key: String): String? {
        val at = items.indexOfFirst { it.key == key }
        if (at <= 0) return null
        val asked = items.subList(0, at).lastOrNull { it is ChatItem.Msg && it.mine } as? ChatItem.Msg ?: return null
        return asked.text.trim().take(MAX_ASKED).ifBlank { null }
    }

    fun canSend(d: ReportDraft): Boolean = d.reason != null

    /** POST /api/reports: the reason, the comment if any, the reply, the user's message only when ticked, and the app version. */
    fun body(d: ReportDraft, reply: String, asked: String?, appVersion: String): JsonObject = buildJsonObject {
        put("reason", (d.reason ?: ReportReason.OTHER).wire)
        put("reply", reply.take(MAX_REPLY))
        d.comment.trim().take(MAX_COMMENT).takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        if (d.includeAsked && !asked.isNullOrBlank()) put("asked", asked.take(MAX_ASKED))
        put("app_version", appVersion)
    }
}

