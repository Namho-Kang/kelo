package chat.kelo.app.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The plain-Kotlin part of the task setup card (TaskGrantCard.kt draws it). Kept free of Android and Compose so the desktop app
// (it uses the apps/shared/ module) compiles the same model the chat reducer and turn events use.

/**
 * The setup card of a "when X happens, do Y" task (server schedule_grants.py, task_allow): the one approval asked while the task is
 * set up, whatever runs it (a schedule that polls, a watcher, a background job). It names the trigger in plain words, exactly what will
 * be done, where / to whom, and the limits; for a purchase also the payment card with its own auto-pay limit next to the amount asked
 * for. The approval_request carries it as "grant" (the contract next to SCHEDULES in server.py).
 */
@Serializable
data class TaskGrantAsk(
    val scope: String = "",
    @SerialName("task_name") val taskName: String = "",
    @SerialName("task_when") val taskWhen: String = "",
    val trigger: String = "",
    val action: String = "",
    val once: Boolean = false,
    val urgent: Boolean = false,
    val missed: String = "",
    val login: Login? = null,
    val purchase: Purchase? = null,
    val send: Send? = null,
    val change: Change? = null,
    val apply: Apply? = null,
) {
    @Serializable
    data class Login(val label: String = "", val site: String = "")

    @Serializable
    data class Purchase(
        val site: String = "",
        val what: String = "",
        @SerialName("max_amount") val maxAmount: Double = 0.0,
        val currency: String = "",
        @SerialName("max_amount_text") val maxAmountText: String = "",
        @SerialName("max_count") val maxCount: Int = 0,
        @SerialName("monthly_cap_text") val monthlyCapText: String = "",
        val card: GrantCard? = null,
        val shipping: String = "",
    )

    @Serializable
    data class GrantCard(
        val label: String = "",
        val last4: String = "",
        @SerialName("auto_pay") val autoPay: Boolean = false,
        @SerialName("auto_limit") val autoLimit: Double? = null,
        @SerialName("auto_limit_text") val autoLimitText: String = "",
        @SerialName("phone_only") val phoneOnly: Boolean = false,
    )

    @Serializable
    data class Send(
        val to: List<String> = emptyList(),
        val site: String = "",
        val template: String = "",
        val varies: String = "",
        @SerialName("max_count") val maxCount: Int = 0,
    )

    @Serializable
    data class Change(
        val what: String = "",
        val where: List<String> = emptyList(),
        @SerialName("allow_delete") val allowDelete: Boolean = false,
        @SerialName("max_count") val maxCount: Int = 0,
    )

    /** Applications on one site: what for, at most [maxCount] per run and [total] over all runs (0 = no total limit). */
    @Serializable
    data class Apply(
        val site: String = "",
        val what: String = "",
        @SerialName("max_count") val maxCount: Int = 1,
        val total: Int = 0,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        fun parse(e: JsonElement?): TaskGrantAsk? {
            val o = e as? JsonObject ?: return null
            return runCatching { json.decodeFromJsonElement(serializer(), o) }.getOrNull()?.takeIf { it.trigger.isNotBlank() || it.action.isNotBlank() }
        }
    }
}

/** What the setup card says about the payment card, decided without Compose (unit-tested). */
enum class CardLimitLine {
    /** The card pays on its own up to its limit: "이 카드의 자동결제 한도는 3만원이에요. 이번 일은 최대 5만원까지 허락할까요?" */
    AUTO_LIMIT,
    /** The card does not pay on its own (auto-pay off): the task's limit is the only one */
    NOT_AUTO,
    /** The card is kept on the phone only: each payment still asks */
    PHONE_ONLY,
    /** No card is named (the site's own balance or way to pay) */
    NONE,
}

object TaskGrantRules {
    fun cardLine(p: TaskGrantAsk.Purchase): CardLimitLine {
        val card = p.card ?: return CardLimitLine.NONE
        return when {
            card.phoneOnly -> CardLimitLine.PHONE_ONLY
            card.autoPay && card.autoLimitText.isNotBlank() -> CardLimitLine.AUTO_LIMIT
            else -> CardLimitLine.NOT_AUTO
        }
    }

    /** The task is allowed more than the card would pay on its own: the card says so plainly (the grant stands in for that limit). */
    fun aboveCardLimit(p: TaskGrantAsk.Purchase): Boolean {
        val limit = p.card?.autoLimit ?: return false
        return p.card.autoPay && p.maxAmount > limit
    }

    /** "받는 사람" for a message permission: the recipients, then the site it posts on. */
    fun recipients(s: TaskGrantAsk.Send): String = (s.to + listOfNotNull(s.site.takeIf { it.isNotBlank() })).joinToString(", ")

    /** Where a change permission applies, without the server's own prefixes ("connector:notion" → "notion", "site:x" → "x"). */
    fun places(c: TaskGrantAsk.Change): String = c.where.joinToString(", ") { it.removePrefix("connector:").removePrefix("site:") }
}
