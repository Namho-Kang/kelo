package chat.kelo.app.agent

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The plain-Kotlin part of a task's grants (ScheduleGrants.kt draws them). Kept free of Android and Compose so the desktop app
// (it uses the apps/shared/ module) uses the same model and calls.

/**
 * What a "when X happens, do Y" task may do without asking, allowed once when it was set up (server schedule_grants.py; SCHEDULES in
 * server.py), whatever runs it ([scope]: a schedule, a watcher or a background job): a saved login on one site, buying one kind of
 * thing on one site up to a cap per time, a count and per month, sending to named recipients, or changing what was named.
 */
@Serializable
data class ScheduleGrant(
    val id: Long,
    @SerialName("schedule_id") val scheduleId: Long = 0,
    @SerialName("schedule_name") val scheduleName: String = "",
    val kind: String = "",
    val site: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    val uses: Int = 0,
    @SerialName("last_used") val lastUsed: Double? = null,
    val login: String = "",
    @SerialName("login_label") val loginLabel: String = "",
    val what: String = "",
    @SerialName("max_amount") val maxAmount: Double = 0.0,
    val currency: String = "",
    @SerialName("max_amount_text") val maxAmountText: String = "",
    @SerialName("monthly_cap_text") val monthlyCapText: String = "",
    @SerialName("spent_this_month") val spentThisMonth: Double = 0.0,
    /** schedule | watcher | job */
    val scope: String = "schedule",
    @SerialName("scope_ref") val scopeRef: String = "",
    @SerialName("task_name") val taskName: String = "",
    val trigger: String = "",
    val action: String = "",
    val once: Boolean = false,
    val urgent: Boolean = false,
    /** 0 = no count limit */
    @SerialName("max_count") val maxCount: Int = 0,
    val used: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("card_label") val cardLabel: String? = null,
    @SerialName("card_last4") val cardLast4: String? = null,
    val targets: List<String> = emptyList(),
    val template: String = "",
    @SerialName("allow_delete") val allowDelete: Boolean = false,
) {
    val isLogin: Boolean get() = kind == KIND_LOGIN
    val isPurchase: Boolean get() = kind == KIND_PURCHASE
    val isSend: Boolean get() = kind == KIND_SEND
    val isChange: Boolean get() = kind == KIND_CHANGE
    val isApply: Boolean get() = kind == KIND_APPLY
    /** The task's name for the wallet and login pages (a watcher's or job's grant has no schedule name) */
    val ownerName: String get() = taskName.ifBlank { scheduleName }
    /** Where a send / change grant applies, without the server's prefixes ("connector:notion" → "notion") */
    val placesText: String get() = targets.joinToString(", ") { it.removePrefix("connector:").removePrefix("site:") }
    /** How many of the allowed times are left ("" without a count); an apply permission counts over all runs ([totalCount]) */
    val left: Int? get() = when {
        isApply -> if (totalCount > 0) (totalCount - used).coerceAtLeast(0) else null
        maxCount > 0 -> (maxCount - used).coerceAtLeast(0)
        else -> null
    }

    companion object {
        const val KIND_LOGIN = "login"
        const val KIND_SEND = "send"
        const val KIND_CHANGE = "change"
        const val KIND_PURCHASE = "purchase"
        const val KIND_APPLY = "apply"
    }
}

@Serializable
data class ScheduleGrantList(val items: List<ScheduleGrant> = emptyList())

object ScheduleGrantCalls {
    suspend fun list(api: KeloApi, scheduleId: Long): List<ScheduleGrant> = api.get<ScheduleGrantList>("/api/schedules/$scheduleId/grants").items

    /** A watcher's (or a background job's) grants, on its own page */
    suspend fun forWatcher(api: KeloApi, watcherId: Long): List<ScheduleGrant> = api.get<ScheduleGrantList>("/api/watchers/$watcherId/grants").items
    suspend fun forJob(api: KeloApi, jobId: String): List<ScheduleGrant> = api.get<ScheduleGrantList>("/api/workers/$jobId/grants").items

    suspend fun revoke(api: KeloApi, g: ScheduleGrant) {
        api.delete<JsonElement>(revokePath(g))
    }

    /** Where a grant is revoked: under its own task (any task: /api/task-grants/{id}) */
    fun revokePath(g: ScheduleGrant): String = when (g.scope) {
        "watcher" -> "/api/watchers/${g.scopeRef}/grants/${g.id}"
        "job" -> "/api/workers/${g.scopeRef}/grants/${g.id}"
        "schedule" -> "/api/schedules/${g.scheduleId.takeIf { it > 0 } ?: g.scopeRef}/grants/${g.id}"
        else -> "/api/task-grants/${g.id}"
    }
}
