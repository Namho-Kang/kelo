package chat.kelo.app.wallet

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * A payment PIN (4-8 digits) in memory, as chars so it can be wiped once used. [toString] never shows it.
 * Typed by the user in the PIN form, or opened from this phone's ciphertext for one payment the user approved.
 */
class PinSecret private constructor(private var digits: CharArray) {
    val length: Int get() = digits.size

    /** {"p":"<digits>"} as bytes: the payload that is encrypted. The caller wipes it. */
    fun payload(): ByteArray = bytes("{\"p\":\"", "\"}")

    /** `,"pin":"…"` for a request body built as bytes ([PinJson]). */
    internal fun field(): ByteArray = bytes(",\"pin\":\"", "\"")

    private fun bytes(head: String, tail: String): ByteArray {
        val out = ByteArray(head.length + digits.size + tail.length)
        var at = 0
        for (ch in head) out[at++] = ch.code.toByte()
        for (ch in digits) out[at++] = ch.code.toByte()
        for (ch in tail) out[at++] = ch.code.toByte()
        return out
    }

    fun wipe() {
        digits.fill('0')
        digits = CharArray(0)
    }

    override fun toString(): String = "PinSecret(${digits.size} digits)"

    companion object {
        const val MIN = 4
        const val MAX = 8

        /** Only 4-8 digits get in. */
        fun of(text: CharSequence): PinSecret? {
            if (text.length !in MIN..MAX || text.any { it !in '0'..'9' }) return null
            return PinSecret(CharArray(text.length) { text[it] })
        }

        /** Reads a decrypted payload; the bytes are wiped here whatever happens. */
        fun parse(payload: ByteArray): PinSecret? {
            try {
                val chars = CharArray(payload.size) { (payload[it].toInt() and 0xFF).toChar() }
                try {
                    val key = "\"p\":\""
                    var at = -1
                    outer@ for (i in 0..chars.size - key.length) {
                        for (j in key.indices) if (chars[i + j] != key[j]) continue@outer
                        at = i + key.length
                        break
                    }
                    if (at < 0) return null
                    var end = at
                    while (end < chars.size && chars[end] in '0'..'9') end++
                    if (end >= chars.size || chars[end] != '"' || end - at !in MIN..MAX) return null
                    return PinSecret(chars.copyOfRange(at, end))
                } finally {
                    chars.fill('\u0000')
                }
            } finally {
                payload.fill(0)
            }
        }
    }
}

/** The end-to-end copy of a payment PIN: the same scheme and phone key as a card's [CardBlob] (version | IV | AES-GCM). */
object PinBlob {
    const val ALG = "A256GCM-AKS-PIN-1"
    private const val VERSION: Byte = 1

    fun seal(secret: PinSecret, cipher: CardCipher, keyId: String): E2eBlob {
        val plain = secret.payload()
        try {
            val iv = cipher.iv
            require(iv.size == CardBlob.IV_BYTES) { "unexpected IV size" }
            val sealed = cipher.doFinal(plain)
            val out = ByteArray(1 + iv.size + sealed.size)
            out[0] = VERSION
            iv.copyInto(out, 1)
            sealed.copyInto(out, 1 + iv.size)
            return E2eBlob(Base64.getEncoder().encodeToString(out), ALG, keyId)
        } finally {
            plain.fill(0)
        }
    }

    fun ivOf(blob: E2eBlob): ByteArray? {
        if (blob.alg != ALG) return null
        val raw = runCatching { Base64.getDecoder().decode(blob.blob) }.getOrNull() ?: return null
        if (raw.size <= 1 + CardBlob.IV_BYTES || raw[0] != VERSION) return null
        return raw.copyOfRange(1, 1 + CardBlob.IV_BYTES)
    }

    fun open(blob: E2eBlob, cipher: CardCipher): PinSecret? {
        if (ivOf(blob) == null) return null
        val raw = Base64.getDecoder().decode(blob.blob)
        val plain = runCatching { cipher.doFinal(raw.copyOfRange(1 + CardBlob.IV_BYTES, raw.size)) }.getOrNull() ?: return null
        return PinSecret.parse(plain)
    }
}

/** GET /api/payment-pins: a saved payment PIN as the server knows it (never the PIN). */
@Serializable
data class PaymentPin(
    val id: String,
    val site: String = "",
    val label: String = "",
    val digits: Int = 0,
    @SerialName("e2e_alg") val e2eAlg: String = "",
    @SerialName("e2e_key_id") val e2eKeyId: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
    @SerialName("last_used") val lastUsed: Double? = null,
    val uses: Int = 0,
) {
    /** This phone can open it (made here with this phone's key). */
    fun ours(keyId: String?): Boolean = keyId != null && e2eAlg == PinBlob.ALG && e2eKeyId == keyId
}

@Serializable
data class PaymentPinList(val items: List<PaymentPin> = emptyList())

@Serializable
data class PinSaved(val pin: PaymentPin? = null, val replaced: Boolean = false, @SerialName("turn_id") val turnId: String? = null)

/** What the PIN form checks before saving. */
object PinRules {
    /** The PIN as typed: digits only, at most [PinSecret.MAX]. */
    fun clean(text: String): String = text.filter { it in '0'..'9' }.take(PinSecret.MAX)
    fun ready(pin: String, again: String, site: String): Boolean =
        pin.length in PinSecret.MIN..PinSecret.MAX && pin == again && siteOk(site)
    fun mismatch(pin: String, again: String): Boolean = again.length >= pin.length && again.isNotEmpty() && pin != again
    /** A site's address: a host with a dot, or a URL. */
    fun siteOk(site: String): Boolean {
        val h = site.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
        return h.contains('.') && h.all { it.isLetterOrDigit() || it == '.' || it == '-' } && !h.startsWith('.') && !h.endsWith('.')
    }
}

/** The payment PIN endpoints. The PIN itself only ever leaves the phone as a body built from bytes ([PinJson]), once, for a payment. */
class PinCalls(private val api: KeloApi) {
    suspend fun list(): List<PaymentPin> = api.get<PaymentPinList>("/api/payment-pins").items
    suspend fun blob(id: String): E2eBlob = api.get("/api/payment-pins/${KeloApi.segment(id)}/blob")
    suspend fun delete(id: String) { api.delete<JsonElement>("/api/payment-pins/${KeloApi.segment(id)}") }
    suspend fun rename(id: String, label: String): PaymentPin =
        api.patch("/api/payment-pins/${KeloApi.segment(id)}", buildJsonObject { put("label", label.trim()) })

    /** POST /api/payment-pins: the ciphertext only. [requestId]: the chat's fill-in card it answers. */
    suspend fun save(site: String, label: String, digits: Int, e2e: E2eBlob, requestId: String? = null): PinSaved =
        api.post("/api/payment-pins", buildJsonObject {
            put("site", site.trim()); put("label", label.trim()); put("digits", digits)
            putJsonObject("e2e") { put("blob", e2e.blob); put("alg", e2e.alg); put("key_id", e2e.keyId) }
            if (requestId != null) put("request_id", requestId)
        })

    /** POST /api/payment-pins/deliveries/{slot}: the opened PIN, once, for the payment the user approved. */
    suspend fun deliver(slotId: String, pinId: String, secret: PinSecret) {
        val body = PinJson.body(buildJsonObject { put("pin_id", pinId) }, secret)
        try {
            api.requestBytes("POST", "/api/payment-pins/deliveries/${KeloApi.segment(slotId)}", body)
        } finally {
            body.fill(0)
        }
    }
}

/** A JSON body with the PIN appended as bytes (it never becomes a String). */
object PinJson {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    fun body(fields: JsonObject, secret: PinSecret): ByteArray {
        val head = json.encodeToString(JsonObject.serializer(), fields).toByteArray(Charsets.UTF_8)
        val extra = secret.field()
        try {
            val open = head.copyOf(head.size - 1)
            val skip = if (head.size == 2) 1 else 0
            val out = ByteArray(open.size + extra.size - skip + 1)
            open.copyInto(out)
            extra.copyInto(out, open.size, skip)
            out[out.size - 1] = '}'.code.toByte()
            return out
        } finally {
            extra.fill(0)
        }
    }
}

/**
 * Releases a payment PIN for one approved payment: its blob, the owner's check (fingerprint, face or screen lock, which unlocks this
 * phone's key), open, send to the payment's slot, wipe. Null when it got there. The steps are passed in so the JVM tests can run it.
 */
object PinRelease {
    suspend fun run(
        pin: PaymentPin, keyId: String?, blob: suspend () -> E2eBlob, unlock: suspend (ByteArray) -> CardAuth,
        send: suspend (PinSecret) -> Unit,
    ): CardSendError? {
        if (!pin.ours(keyId)) return CardSendError.NOT_ON_PHONE
        val b = try { blob() } catch (e: ApiError) {
            return if (e.status == 404) CardSendError.NOT_ON_PHONE else DeliveryRules.fromStatus(e.status)
        }
        if (b.keyId != keyId) return CardSendError.NOT_ON_PHONE
        val iv = PinBlob.ivOf(b) ?: return CardSendError.NOT_ON_PHONE
        val a = unlock(iv)
        DeliveryRules.fromAuth(a)?.let { return it }
        val secret = PinBlob.open(b, (a as CardAuth.Ok).cipher) ?: return CardSendError.NOT_ON_PHONE
        return try {
            send(secret)
            null
        } catch (e: ApiError) {
            DeliveryRules.fromStatus(e.status) ?: CardSendError.OTHER
        } finally {
            secret.wipe()
        }
    }
}

/** [PinRelease] with this phone's Keystore key and the server. [pinId] is looked up so a PIN saved on another phone says so. */
suspend fun releasePin(api: KeloApi, auth: CardKeys, slotId: String, pinId: String, title: String, subtitle: String): CardSendError? {
    val calls = PinCalls(api)
    val pin = try { calls.list().firstOrNull { it.id == pinId } } catch (e: ApiError) { return DeliveryRules.fromStatus(e.status) ?: CardSendError.OTHER }
        ?: return CardSendError.NOT_WAITING
    return PinRelease.run(pin, auth.keyId(), { calls.blob(pin.id) }, { iv -> auth.awaitPaying(iv, title, subtitle) }) {
        calls.deliver(slotId, pin.id, it)
    }
}

/** Seals a typed PIN with this phone's key (after the owner's check) and saves it for [site]. */
suspend fun savePin(
    api: KeloApi, auth: CardKeys, site: String, label: String, secret: PinSecret, title: String, subtitle: String,
    requestId: String? = null,
): Pair<PinSaved?, CardSendError?> {
    try {
        val a = auth.awaitSaving(title, subtitle)
        DeliveryRules.fromAuth(a)?.let { return null to it }
        val ok = a as CardAuth.Ok
        val blob = PinBlob.seal(secret, ok.cipher, ok.keyId)
        return try {
            PinCalls(api).save(site, label, secret.length, blob, requestId) to null
        } catch (e: ApiError) {
            null to (DeliveryRules.fromStatus(e.status) ?: CardSendError.OTHER)
        }
    } finally {
        secret.wipe()
    }
}
