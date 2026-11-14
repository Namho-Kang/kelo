package chat.kelo.app.agent

import chat.kelo.app.chat.TurnEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/** An approval waiting right now (GET /api/approvals/pending): the chat's approval event, with what the list adds. */
data class PendingApproval(
    val event: TurnEvent.ApprovalRequest,
    val site: String,
    val canRemember: Boolean,
    val askedAt: Double,
    val unattended: Boolean,
    val taskId: Long?,
    /** "keep allowing this in that scheduled job" may be offered */
    val canRememberSchedule: Boolean = false,
    /** What asked: the run's task source (chat, worker, schedule:<id>, background…); "" from an older server */
    val source: String = "",
    /** The background job the asking run is a step of */
    val workerId: String? = null,
    /** That run's title (a job's own title) */
    val title: String = "",
) {
    val id: String get() = event.approvalId
    val isPayment: Boolean get() = event.payment != null
    /** A site's check whether a person is there, handed to the user: "solve it" opens [tab] live with control, "skip" answers no */
    val isHumanCheck: Boolean get() = event.kind == HUMAN_CHECK
    /** A human check that is a sign-in's second step (approve on the phone, or type the code), not a person check */
    val isVerification: Boolean get() = isHumanCheck && event.check?.isVerification == true
    /** The browser tab a human check is on */
    val tab: String get() = event.tab.ifBlank { "owner" }

    companion object {
        fun parse(o: JsonObject): PendingApproval? {
            val ev = TurnEvent.parse(o) as? TurnEvent.ApprovalRequest ?: return null
            if (ev.approvalId.isEmpty()) return null
            fun p(k: String) = o[k] as? JsonPrimitive
            return PendingApproval(
                ev, p("site")?.takeIf { it.isString }?.content.orEmpty(), p("can_remember")?.booleanOrNull == true,
                p("asked_at")?.doubleOrNull ?: 0.0, p("unattended")?.booleanOrNull == true, p("task_id")?.longOrNull,
                ev.payment == null && p("can_remember_schedule")?.booleanOrNull == true,
                p("source")?.takeIf { it.isString }?.content.orEmpty(),
                p("worker_id")?.takeIf { it.isString }?.content?.ifBlank { null },
                p("title")?.takeIf { it.isString }?.content.orEmpty(),
            )
        }

        fun parseList(e: JsonElement): List<PendingApproval> =
            ((e as? JsonObject)?.get("items")?.jsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }

        /** Hand-overs that ended lately (the list's "resolved"): approval id -> solved | skipped | expired | cancelled. */
        fun parseResolved(e: JsonElement): Map<String, String> =
            ((e as? JsonObject)?.get("resolved") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = (o["approval_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val out = (o["outcome"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                if (id.isEmpty() || out.isEmpty()) null else id to out
            }.toMap()

        const val HUMAN_CHECK = "human_check"
    }
}
