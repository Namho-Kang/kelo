package chat.kelo.app.wallet

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

/** The new card the add form collected; the number and CVC are in [secret] only. */
data class NewCard(
    val holder: String,
    val postalCode: String,
    val expMonth: Int,
    val expYear: Int,
    val brand: CardBrand,
    val autoPay: Boolean = false,
    val label: String = "",
)

object DeliveryRules {
    /** What an HTTP status of POST /api/wallet/deliveries means; null = delivered (409: it was already, which is fine too). */
    fun fromStatus(status: Int): CardSendError? = when (status) {
        in 200..299, 409 -> null
        404 -> CardSendError.NOT_WAITING
        422 -> CardSendError.MISMATCH
        ApiError.NO_RESPONSE -> CardSendError.NETWORK
        else -> CardSendError.OTHER
    }

    fun fromAuth(a: CardAuth): CardSendError? = when (a) {
        is CardAuth.Ok -> null
        CardAuth.Cancelled -> CardSendError.CANCELLED
        CardAuth.NoLock -> CardSendError.NO_LOCK
        CardAuth.KeyLost -> CardSendError.NOT_ON_PHONE
        is CardAuth.Failed -> CardSendError.OTHER
    }
}

/** The wallet endpoints (server.py WALLET API). Every request that carries a card number is sent as bytes and wiped. */
class WalletCalls(private val api: KeloApi) {
    suspend fun info(): WalletInfo = api.get("/api/wallet")
    suspend fun limits(): WalletLimits = api.get("/api/wallet/limits")
    suspend fun payments(limit: Int = 100): WalletPayments = api.get("/api/wallet/payments?limit=$limit")
    suspend fun notifications(): WalletNotifications = api.get("/api/wallet/notifications")
    suspend fun markSeen(upTo: Long) { api.post<JsonElement>("/api/wallet/notifications/seen", buildJsonObject { put("up_to", upTo) }) }
    suspend fun pause(paused: Boolean): PauseState = api.put("/api/wallet/pause", buildJsonObject { put("paused", paused) })
    suspend fun setLimit(currency: String, perPayment: Double, monthly: Double): WalletLimit =
        api.put("/api/wallet/limits/${KeloApi.segment(currency)}", buildJsonObject { put("per_payment", perPayment); put("monthly", monthly) })
    suspend fun resetLimit(currency: String) { api.delete<JsonElement>("/api/wallet/limits/${KeloApi.segment(currency)}") }
    /** [newDefault]: the card that becomes the default when the default card is deleted with two or more others left. */
    suspend fun delete(id: String, newDefault: String? = null) {
        val q = newDefault?.let { "?new_default=${KeloApi.segment(it)}" }.orEmpty()
        api.delete<JsonElement>("/api/wallet/cards/${KeloApi.segment(id)}$q")
    }
    suspend fun makeDefault(id: String): WalletCard = patch(id, buildJsonObject { put("default", true) })

    /** The payments made with one card, newest first; [before] is the last page's next_before_id. */
    suspend fun cardPayments(id: String, before: Long? = null, limit: Int = 20): WalletPayments =
        api.get("/api/wallet/cards/${KeloApi.segment(id)}/payments?limit=$limit" + (before?.let { "&before_id=$it" } ?: ""))

    /** Turns auto-pay on with the number and CVC this phone opened from its own copy (sent once as bytes, then wiped). */
    suspend fun turnOnAuto(id: String, secret: CardSecret): WalletCard =
        sendSecret("PATCH", "/api/wallet/cards/${KeloApi.segment(id)}", buildJsonObject { put("auto_pay", true) }, secret)
    suspend fun blob(id: String): E2eBlob = api.get("/api/wallet/cards/${KeloApi.segment(id)}/blob")

    /** PATCH of fields that carry no card details (label, auto_pay false, ...). */
    suspend fun patch(id: String, fields: JsonObject): WalletCard = api.patch("/api/wallet/cards/${KeloApi.segment(id)}", fields)

    /**
     * POST /api/wallet/cards. The phone's blob always goes along (also for auto-pay, so turning auto-pay off later keeps the card
     * usable); with auto-pay the number and CVC go too, for the server's own sealed copy.
     */
    suspend fun add(card: NewCard, e2e: E2eBlob, secret: CardSecret): WalletCard {
        val fields = buildJsonObject {
            if (card.label.isNotBlank()) put("label", card.label.trim())
            put("exp_month", card.expMonth); put("exp_year", card.expYear)
            put("holder", card.holder.trim()); put("postal_code", card.postalCode.trim())
            put("auto_pay", card.autoPay)
            put("brand", card.brand.wire)
            if (!card.autoPay) put("last4", secret.last4)
            putE2e(e2e)
        }
        return if (card.autoPay) sendSecret("POST", "/api/wallet/cards", fields, secret)
        else KeloApi.decode(api.request("POST", "/api/wallet/cards", fields))
    }

    /** PATCH with a re-entered card: a new blob, and with [autoPay] the number and CVC for the server copy. */
    suspend fun replace(id: String, e2e: E2eBlob, secret: CardSecret, autoPay: Boolean): WalletCard {
        val fields = buildJsonObject {
            putE2e(e2e)
            if (autoPay) put("auto_pay", true)
        }
        val path = "/api/wallet/cards/${KeloApi.segment(id)}"
        return if (autoPay) sendSecret("PATCH", path, fields, secret) else KeloApi.decode(api.request("PATCH", path, fields))
    }

    /** POST /api/wallet/deliveries/{approval_id}: the decrypted card, once, for the payment the user allowed. */
    suspend fun deliver(approvalId: String, cardId: String, secret: CardSecret) {
        sendSecret<JsonElement>("POST", "/api/wallet/deliveries/${KeloApi.segment(approvalId)}", buildJsonObject { put("card_id", cardId) }, secret)
    }

    private suspend inline fun <reified T> sendSecret(method: String, path: String, fields: JsonObject, secret: CardSecret): T {
        val body = SecretJson.body(fields, secret)
        try {
            return KeloApi.decode(api.requestBytes(method, path, body))
        } finally {
            body.fill(0)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putE2e(e2e: E2eBlob) {
        putJsonObject("e2e") { put("blob", e2e.blob); put("alg", e2e.alg); put("key_id", e2e.keyId) }
    }
}

suspend fun CardKeys.awaitSaving(title: String, subtitle: String): CardAuth =
    suspendCancellableCoroutine { cont -> forSaving(title, subtitle) { if (cont.isActive) cont.resume(it) } }

suspend fun CardKeys.awaitPaying(iv: ByteArray, title: String, subtitle: String): CardAuth =
    suspendCancellableCoroutine { cont -> forPaying(iv, title, subtitle) { if (cont.isActive) cont.resume(it) } }

/**
 * Sends an end-to-end card for one approved payment: its blob from the server, the owner's check (which unlocks this phone's key),
 * decrypt, POST to the approval's slot, wipe. Returns null when the card got there.
 */
suspend fun deliverCard(
    calls: WalletCalls, auth: CardKeys, approvalId: String, cardId: String, title: String, subtitle: String,
): CardSendError? {
    val blob = try {
        calls.blob(cardId)
    } catch (e: ApiError) {
        return if (e.status == 404) CardSendError.NOT_ON_PHONE else DeliveryRules.fromStatus(e.status)
    }
    if (blob.keyId != auth.keyId()) return CardSendError.NOT_ON_PHONE
    val iv = CardBlob.ivOf(blob) ?: return CardSendError.NOT_ON_PHONE
    val a = auth.awaitPaying(iv, title, subtitle)
    DeliveryRules.fromAuth(a)?.let { return it }
    val secret = CardBlob.open(blob, (a as CardAuth.Ok).cipher) ?: return CardSendError.NOT_ON_PHONE
    return try {
        calls.deliver(approvalId, cardId, secret)
        null
    } catch (e: ApiError) {
        DeliveryRules.fromStatus(e.status)
    } finally {
        secret.wipe()
    }
}

/**
 * Turning auto-pay on without typing the card again: this phone's own end-to-end copy (its blob from the server) is opened with the
 * owner's check (fingerprint, face or screen lock, which unlocks the Keystore key) and the number and CVC go once over TLS to the
 * auto-pay endpoint, which seals them. Nothing is logged or shown; the secret is wiped whatever happens.
 * Returns null when auto-pay is on; [CardSendError.NOT_ON_PHONE] when this phone holds no copy it can open (added on another device),
 * so the owner turns it on there or types the number.
 */
object AutoPayOn {
    suspend fun run(
        card: WalletCard, keyId: String?, blob: suspend () -> E2eBlob, unlock: suspend (ByteArray) -> CardAuth,
        send: suspend (CardSecret) -> Unit,
    ): CardSendError? {
        if (!CardBlob.ours(card, keyId)) return CardSendError.NOT_ON_PHONE
        val b = try {
            blob()
        } catch (e: ApiError) {
            return if (e.status == 404) CardSendError.NOT_ON_PHONE else DeliveryRules.fromStatus(e.status)
        }
        if (b.keyId != keyId) return CardSendError.NOT_ON_PHONE
        val iv = CardBlob.ivOf(b) ?: return CardSendError.NOT_ON_PHONE
        val a = unlock(iv)
        DeliveryRules.fromAuth(a)?.let { return it }
        val secret = CardBlob.open(b, (a as CardAuth.Ok).cipher) ?: return CardSendError.NOT_ON_PHONE
        return try {
            send(secret)
            null
        } catch (e: ApiError) {
            when {
                e.code == AutoPayGate.CODE_KEYGUARD_REQUIRED -> CardSendError.KEYGUARD_REQUIRED
                e.status == 422 -> CardSendError.MISMATCH
                else -> DeliveryRules.fromStatus(e.status) ?: CardSendError.OTHER
            }
        } finally {
            secret.wipe()
        }
    }
}

/** [AutoPayOn] with this phone's Keystore key and the server. */
suspend fun turnOnAutoPay(calls: WalletCalls, auth: CardKeys, card: WalletCard, title: String, subtitle: String): CardSendError? =
    AutoPayOn.run(card, auth.keyId(), { calls.blob(card.id) }, { iv -> auth.awaitPaying(iv, title, subtitle) }) { calls.turnOnAuto(card.id, it) }

/** A string field of a JSON answer (for small reads). */
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
