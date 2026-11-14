package chat.kelo.app.wallet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A card as every wallet answer shows it (server.py WALLET API "Card"): never a number or CVC. */
@Serializable
data class WalletCard(
    val id: String,
    val label: String = "",
    val brand: String = "other",
    val last4: String = "",
    @SerialName("exp_month") val expMonth: Int = 0,
    @SerialName("exp_year") val expYear: Int = 0,
    val holder: String = "",
    @SerialName("postal_code") val postalCode: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("auto_pay") val autoPay: Boolean = false,
    /** The server can read it (only with auto-pay) */
    @SerialName("server_copy") val serverCopy: Boolean = false,
    /** The phone's ciphertext is stored */
    val e2e: Boolean = false,
    @SerialName("e2e_alg") val e2eAlg: String = "",
    @SerialName("e2e_key_id") val e2eKeyId: String = "",
    val usable: Boolean = false,
    val expired: Boolean = false,
    @SerialName("last_used") val lastUsed: Double? = null,
    /** Pays without asking right now (auto-pay on, a server copy, not paused, vault open); null from an older server */
    @SerialName("pays_automatically") val paysAutomatically: Boolean? = null,
    /** Why it asks each time: not_auto | no_server_copy | paused | vault_locked ("" when it pays on its own) */
    @SerialName("ask_reason") val askReason: String = "",
    /** The card the agent pays with unless the owner or the task names another */
    @SerialName("default") val isDefault: Boolean = false,
) {
    /** Pays on its own now (an older server without [paysAutomatically]: its auto-pay switch) */
    val autoNow: Boolean get() = (paysAutomatically ?: autoPay) && usable && !expired

    /** The card-wide reason it asks, also for an older server that does not send one */
    val reason: String get() = askReason.ifEmpty { if (!autoPay) REASON_NOT_AUTO else "" }

    /** Can pay but was saved to ask each time: its row offers turning auto-pay on (paused or locked is changed elsewhere) */
    val offersTurnOn: Boolean get() = !expired && usable && !autoPay

    companion object {
        const val REASON_NOT_AUTO = "not_auto"
        const val REASON_NO_SERVER_COPY = "no_server_copy"
        const val REASON_PAUSED = "paused"
        const val REASON_VAULT_LOCKED = "vault_locked"
        /** Auto-pay is paused because the server has no Keyguard (the server's word for it, when it sends one) */
        const val REASON_KEYGUARD = "keyguard_off"
    }
}

/** GET /api/wallet */
@Serializable
data class WalletInfo(
    val paused: Boolean = false,
    val cards: List<WalletCard> = emptyList(),
    val timezone: String = "",
    val month: String = "",
    /** Cards that pay on its own right now (null from an older server) */
    @SerialName("auto_cards") val autoCards: Int? = null,
    /** Schedules allowed (once, at setup) to buy on their own */
    @SerialName("schedule_grants") val scheduleGrants: List<chat.kelo.app.agent.ScheduleGrant> = emptyList(),
    /** The shipping addresses, default first (null from a server without them) */
    val shipping: List<ShippingProfile>? = null,
) {
    val autoCount: Int get() = autoCards ?: if (paused) 0 else cards.count { it.autoNow }
}

/** One row of GET /api/wallet/limits. Defaults are null for a currency the user added. */
@Serializable
data class WalletLimit(
    val currency: String,
    @SerialName("per_payment") val perPayment: Double = 0.0,
    val monthly: Double = 0.0,
    @SerialName("default_per_payment") val defaultPerPayment: Double? = null,
    @SerialName("default_monthly") val defaultMonthly: Double? = null,
    val custom: Boolean = false,
    @SerialName("spent_this_month") val spentThisMonth: Double = 0.0,
) {
    /** A currency the user added (no server default): resetting removes it */
    val added: Boolean get() = defaultPerPayment == null
}

@Serializable
data class WalletLimits(val month: String = "", val timezone: String = "", val limits: List<WalletLimit> = emptyList())

/** A payment that went ahead (server.py WALLET API "Payment"). kind: auto | approved. */
@Serializable
data class WalletPayment(
    val id: Long = 0,
    val at: Double = 0.0,
    val kind: String = "",
    val site: String = "",
    val host: String = "",
    val summary: String = "",
    val amount: Double = 0.0,
    val currency: String = "KRW",
    @SerialName("amount_text") val amountText: String = "",
    @SerialName("card_id") val cardId: String = "",
    @SerialName("card_label") val cardLabel: String = "",
    val month: String = "",
    val seen: Boolean = true,
    /** The activity task the payment was made in (null when not known) */
    @SerialName("task_id") val taskId: Long? = null,
    /** The kind of turn it was made in: "chat", "api", "schedule:<id>", "task:<hex>", ... ("" from an older server) */
    val source: String = "",
) {
    val auto: Boolean get() = kind == KIND_AUTO

    companion object {
        const val KIND_AUTO = "auto"
    }
}

@Serializable
data class WalletPayments(
    val items: List<WalletPayment> = emptyList(),
    /** GET /api/wallet/cards/{id}/payments: the before_id of the next older page (null: no more) */
    @SerialName("next_before_id") val nextBeforeId: Long? = null,
)

@Serializable
data class WalletNotifications(val items: List<WalletPayment> = emptyList(), val unseen: Int = 0)

/** GET /api/wallet/cards/{id}/blob: the phone's own ciphertext. */
@Serializable
data class E2eBlob(val blob: String, val alg: String = "", @SerialName("key_id") val keyId: String = "")

@Serializable
data class PauseState(val paused: Boolean = false)

/**
 * Auto-pay needs Keyguard (owner's decision, 2026-11-10): the server keeps a copy of an auto-pay card, and only with Keyguard on does
 * that copy's lock open with the owner's phone alone. Turning auto-pay on with Keyguard off (or unknown) asks to turn Keyguard on
 * first; the server refuses it too (409 {"code": "keyguard_required"}); an auto-pay card on a server without Keyguard is paused
 * (each payment asks on the phone) until it is turned on. [keyguardOn]: GET /api/keyguard "mode" is "phone"; null = not known.
 */
object AutoPayGate {
    const val CODE_KEYGUARD_REQUIRED = "keyguard_required"

    /** Auto-pay may be turned on now; otherwise the "Auto-pay needs Keyguard" dialog shows. */
    fun mayTurnOn(keyguardOn: Boolean?): Boolean = keyguardOn == true

    /** The card's auto-pay waits for Keyguard (the card page says so, with "Turn on Keyguard"). */
    fun pausedForKeyguard(card: WalletCard, keyguardOn: Boolean?): Boolean =
        card.autoPay && !card.expired && (card.askReason == WalletCard.REASON_KEYGUARD || keyguardOn == false)
}
