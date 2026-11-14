package chat.kelo.app.followup

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Follow-ups (server: followups.py): when something waits on the user (a login, a stopped job, an approval, an approval that ran
 * out, an answer), the agent writes a chat message about it, at most twice, then the item is on hold.
 *
 * GET  /api/followups?closed=true   {"items": [Followup]} open ones and the last closed ones; each has the chat `message_id` of its
 *                                   latest follow-up and the `actions` the app shows as buttons under that message
 * POST /api/followups/{id}/snooze {"minutes": 60}   POST /api/followups/{id}/drop   POST /api/followups/{id}/retry (approval_expired)
 * resume is the job's own call: POST /api/workers/{worker_id}/resume
 * GET/PUT /api/followups/settings {enabled, first_delay_min, second_delay_min}
 * A server without follow-ups answers 404: the app then shows none of this.
 */

/** One button of a follow-up, as the server sends it. Ids are kept as text, whatever JSON type they came in. */
data class FollowupAction(
    val kind: String,
    val name: String = "",
    val approvalId: String = "",
    val workerId: String = "",
    val followupId: String = "",
    val minutes: Int? = null,
) {
    companion object {
        const val OPEN_LOGIN = "open_login"
        const val APPROVE = "approve"
        const val DENY = "deny"
        const val RESUME = "resume"
        const val DROP = "drop"
        const val SNOOZE = "snooze"
        /** Start again a run whose approval ran out (kind approval_expired) */
        const val RETRY = "retry"

        fun parse(e: JsonElement?): FollowupAction? {
            val o = e as? JsonObject ?: return null
            val kind = o.text("kind").ifEmpty { return null }
            return FollowupAction(kind, o.text("name"), o.text("approval_id"), o.text("worker_id"), o.text("followup_id"), (o["minutes"] as? JsonPrimitive)?.intOrNull)
        }

        fun parseList(e: JsonElement?): List<FollowupAction> = (e as? JsonArray).orEmpty().mapNotNull(::parse)
    }
}

/** A thing the agent is asking about again. */
data class Followup(
    val id: String,
    val kind: String,
    val title: String,
    val stage: String,
    val createdAt: Double,
    val actions: List<FollowupAction>,
    /** worker | schedule | background | "" */
    val origin: String = "",
    val scheduleId: Long? = null,
    /** The chat message of its latest follow-up: its buttons go under that message */
    val messageId: Long? = null,
    /** open | resolved | dropped */
    val status: String = OPEN,
) {
    val onHold: Boolean get() = stage == ON_HOLD
    val open: Boolean get() = status == OPEN

    companion object {
        const val ON_HOLD = "on_hold"
        const val APPROVAL_EXPIRED = "approval_expired"
        const val OPEN = "open"
        const val RESOLVED = "resolved"
        const val DROPPED = "dropped"

        fun parse(e: JsonElement?): Followup? {
            val o = e as? JsonObject ?: return null
            val id = o.text("id").ifEmpty { return null }
            return Followup(
                id, o.text("kind"), o.text("title"), o.text("stage"),
                ((o["began_at"] ?: o["created_at"]) as? JsonPrimitive)?.doubleOrNull ?: 0.0, FollowupAction.parseList(o["actions"]),
                o.text("origin"), o.text("schedule_id").toLongOrNull(), o.text("message_id").toLongOrNull(),
                o.text("status").ifEmpty { OPEN },
            )
        }

        /** {"items": [...]} or a bare list. */
        fun parseList(e: JsonElement?): List<Followup> {
            val arr = (e as? JsonArray) ?: ((e as? JsonObject)?.get("items") as? JsonArray)
            return arr.orEmpty().mapNotNull(::parse)
        }
    }
}

/** GET /api/followups: [available] is false on a server without follow-ups (404). */
data class FollowupList(val items: List<Followup> = emptyList(), val available: Boolean = true)

/** GET/PUT /api/followups/settings */
@Serializable
data class FollowupSettings(
    val enabled: Boolean = true,
    @SerialName("first_delay_min") val firstMinutes: Int = 30,
    @SerialName("second_delay_min") val secondMinutes: Int = 180,
)

/** What pressing a follow-up button does. */
sealed interface FollowupStep {
    /** Opens the sign-in sheet for the Kelo keychain item [name] (done on the phone, nothing is sent yet). */
    data class OpenLogin(val name: String) : FollowupStep
    data class Answer(val approvalId: String, val approve: Boolean) : FollowupStep
    /** POST /api/workers/{id}/resume: the job goes on */
    data class ResumeWorker(val workerId: String) : FollowupStep
    data class Drop(val followupId: String) : FollowupStep
    data class Snooze(val followupId: String, val minutes: Int) : FollowupStep
    /** Starts the run again whose approval ran out; a fresh approval card follows the usual way. */
    data class Retry(val followupId: String) : FollowupStep
}

/** How a step went. */
enum class StepResult { DONE, GONE, FAILED }

/** The follow-ups' small rules, apart from the UI. */
object FollowupRules {
    /** Snooze when the server names no time */
    const val DEFAULT_SNOOZE_MINUTES = 60
    /** The most buttons under one message or card */
    const val MAX_BUTTONS = 4
    val FIRST_CHOICES = listOf(5, 15, 30, 60, 120, 240)
    val SECOND_CHOICES = listOf(30, 60, 120, 180, 360, 720, 1440)

    /**
     * What an action does, or null when it lacks what it needs (an approve without an approval, a login without a name) or is a
     * kind this app does not know. A follow-up's own id stands in when the action names none ([ownId]).
     */
    fun step(a: FollowupAction, ownId: String = ""): FollowupStep? {
        val fid = a.followupId.ifEmpty { ownId }
        return when (a.kind) {
            FollowupAction.OPEN_LOGIN -> a.name.takeIf { it.isNotBlank() }?.let { FollowupStep.OpenLogin(it) }
            FollowupAction.APPROVE -> a.approvalId.takeIf { it.isNotBlank() }?.let { FollowupStep.Answer(it, true) }
            FollowupAction.DENY -> a.approvalId.takeIf { it.isNotBlank() }?.let { FollowupStep.Answer(it, false) }
            FollowupAction.RESUME -> a.workerId.takeIf { it.isNotBlank() }?.let { FollowupStep.ResumeWorker(it) }
            FollowupAction.DROP -> fid.takeIf { it.isNotBlank() }?.let { FollowupStep.Drop(it) }
            FollowupAction.RETRY -> fid.takeIf { it.isNotBlank() }?.let { FollowupStep.Retry(it) }
            FollowupAction.SNOOZE -> fid.takeIf { it.isNotBlank() }?.let { FollowupStep.Snooze(it, (a.minutes ?: DEFAULT_SNOOZE_MINUTES).coerceAtLeast(1)) }
            else -> null
        }
    }

    /** The buttons to show: the usable actions, each step once, at most [MAX_BUTTONS]. */
    fun buttons(actions: List<FollowupAction>, ownId: String = ""): List<Pair<FollowupAction, FollowupStep>> =
        actions.mapNotNull { a -> step(a, ownId)?.let { a to it } }.distinctBy { it.second }.take(MAX_BUTTONS)

    /** The buttons of a card in 답이 필요해요: exactly the actions the server sent (resume / retry / snooze / drop …). */
    fun holdButtons(f: Followup): List<Pair<FollowupAction, FollowupStep>> = buttons(f.actions, f.id)

    /**
     * Which follow-up each chat message carries: by `message_id`, an open item before a closed one (an item's latest follow-up
     * message is the one with its buttons).
     */
    fun byMessage(items: List<Followup>): Map<Long, Followup> {
        val out = HashMap<Long, Followup>()
        items.sortedByDescending { it.open }.forEach { f -> f.messageId?.let { id -> out.putIfAbsent(id, f) } }
        return out
    }

    /** Follow-ups put on hold, oldest first (they go in 답이 필요해요). */
    fun onHold(items: List<Followup>): List<Followup> = items.filter(::needsYou).sortedBy { it.createdAt }

    /** The second ask comes after the first: a second delay shorter than the first is raised to it. */
    fun delays(first: Int, second: Int): Pair<Int, Int> {
        val f = first.coerceAtLeast(1)
        return f to second.coerceAtLeast(f)
    }

    /** The choices of a delay picker, with the current value in them even when it is not one of the usual ones. */
    fun choices(usual: List<Int>, current: Int): List<Int> = (usual + current).filter { it > 0 }.distinct().sorted()

    /** The HTTP call of a step that talks to the server (null for one done on the phone). */
    fun call(s: FollowupStep): Triple<String, String, JsonObject?>? = when (s) {
        is FollowupStep.OpenLogin -> null
        is FollowupStep.Answer -> Triple("POST", "/api/approvals/${KeloApi.segment(s.approvalId)}", buildJsonObject { put("approve", s.approve) })
        is FollowupStep.ResumeWorker -> Triple("POST", "/api/workers/${KeloApi.segment(s.workerId)}/resume", null)
        is FollowupStep.Drop -> Triple("POST", "/api/followups/${KeloApi.segment(s.followupId)}/drop", null)
        is FollowupStep.Snooze -> Triple("POST", "/api/followups/${KeloApi.segment(s.followupId)}/snooze", buildJsonObject { put("minutes", s.minutes) })
        is FollowupStep.Retry -> Triple("POST", "/api/followups/${KeloApi.segment(s.followupId)}/retry", null)
    }

    /** Follow-ups that go in 답이 필요해요: those on hold, and runs whose approval ran out (they wait for 다시 시도). */
    fun needsYou(f: Followup): Boolean = f.open && (f.onHold || f.kind == Followup.APPROVAL_EXPIRED)

    /** 404 / 409 / 410: the thing is already settled (answered elsewhere, expired, dropped). */
    fun gone(status: Int): Boolean = status == 404 || status == 409 || status == 410
}

/** Runs follow-up steps against the server; shared by the chat and the 작업 tab. */
class FollowupCalls(private val api: KeloApi) {
    suspend fun list(): FollowupList = try {
        FollowupList(Followup.parseList(api.request("GET", "/api/followups?closed=true")))
    } catch (e: ApiError) {
        if (e.status == 404) FollowupList(available = false) else throw e
    }

    /** Null on a server without follow-ups. */
    suspend fun settings(): FollowupSettings? = try {
        api.get<FollowupSettings>("/api/followups/settings")
    } catch (e: ApiError) {
        if (e.status == 404) null else throw e
    }

    suspend fun putSettings(body: JsonObject): FollowupSettings = api.put("/api/followups/settings", body)

    /** Does [s]: done, already settled elsewhere (gone), or failed with the server's reason. */
    suspend fun run(s: FollowupStep): Pair<StepResult, String> {
        val (method, path, body) = FollowupRules.call(s) ?: return StepResult.DONE to ""
        return try {
            api.request(method, path, body)
            StepResult.DONE to ""
        } catch (e: ApiError) {
            when {
                // a run that cannot start again says why; the item stays open
                s is FollowupStep.Retry && e.status == 409 -> StepResult.FAILED to e.detail
                FollowupRules.gone(e.status) -> StepResult.GONE to ""
                else -> StepResult.FAILED to (if (e.isNetwork) "" else e.detail)
            }
        }
    }
}

private fun JsonObject.text(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
