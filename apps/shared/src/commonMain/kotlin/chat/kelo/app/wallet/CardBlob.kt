package chat.kelo.app.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * A card number and CVC in memory, as bytes so they can be wiped once used (a String cannot). [toString] shows only the last 4.
 * Made from what the user typed (add form) or from a decrypted blob (a payment the user allowed).
 */
class CardSecret private constructor(private var number: CharArray, private var cvc: CharArray) {
    val last4: String get() = String(number.copyOfRange(maxOf(0, number.size - 4), number.size))
    val empty: Boolean get() = number.isEmpty()

    /** The plain payload that is encrypted: {"n":"<digits>","c":"<digits>"} as UTF-8 bytes. The caller wipes it. */
    fun payload(): ByteArray = bytesOf("{\"n\":\"", number, "\",\"c\":\"", cvc, "\"}")

    /** `,"number":"…","cvc":"…"` for [SecretJson]. */
    internal fun fields(): ByteArray = bytesOf(",\"number\":\"", number, "\",\"cvc\":\"", cvc, "\"")

    fun wipe() {
        number.fill('0'); cvc.fill('0')
        number = CharArray(0); cvc = CharArray(0)
    }

    override fun toString(): String = "CardSecret(${CardMask.DOTS}$last4)"

    companion object {
        /** Only digits get in, so the payload never needs escaping. Null when either is not all digits. */
        fun of(number: CharSequence, cvc: CharSequence): CardSecret? {
            if (number.isEmpty() || cvc.isEmpty()) return null
            if (number.any { it !in '0'..'9' } || cvc.any { it !in '0'..'9' }) return null
            return CardSecret(CharArray(number.length) { number[it] }, CharArray(cvc.length) { cvc[it] })
        }

        /** Reads a decrypted [payload]; the bytes are wiped here whatever happens. */
        fun parse(payload: ByteArray): CardSecret? {
            try {
                val chars = CharArray(payload.size) { (payload[it].toInt() and 0xFF).toChar() }
                try {
                    val n = field(chars, "\"n\":\"") ?: return null
                    val c = field(chars, "\"c\":\"") ?: return null
                    return CardSecret(n, c).takeIf { n.isNotEmpty() && c.isNotEmpty() }
                } finally {
                    chars.fill('\u0000')
                }
            } finally {
                payload.fill(0)
            }
        }

        private fun field(chars: CharArray, key: String): CharArray? {
            val at = indexOf(chars, key)
            if (at < 0) return null
            val from = at + key.length
            var end = from
            while (end < chars.size && chars[end] in '0'..'9') end++
            if (end >= chars.size || chars[end] != '"') return null
            return chars.copyOfRange(from, end)
        }

        private fun indexOf(chars: CharArray, key: String): Int {
            outer@ for (i in 0..chars.size - key.length) {
                for (j in key.indices) if (chars[i + j] != key[j]) continue@outer
                return i
            }
            return -1
        }

        private fun bytesOf(vararg parts: Any): ByteArray {
            val size = parts.sumOf { if (it is CharArray) it.size else (it as String).length }
            val out = ByteArray(size)
            var at = 0
            for (p in parts) {
                if (p is CharArray) for (ch in p) out[at++] = ch.code.toByte()
                else for (ch in p as String) out[at++] = ch.code.toByte()
            }
            return out
        }
    }
}

/** One step of an initialised AES-GCM cipher: the Keystore one once the user passed the check, a fake on the JVM. */
interface CardCipher {
    /** The IV the cipher picked (encrypt) or was given (decrypt) */
    val iv: ByteArray
    fun doFinal(input: ByteArray): ByteArray
}

/**
 * The end-to-end copy of a card: {number, cvc} encrypted on the phone with a key only this phone holds (Android Keystore, usable
 * only right after the owner's fingerprint, face or screen lock). The server stores it and cannot read it.
 *
 * blob = base64( version byte 1 | 12-byte IV | AES-GCM ciphertext and tag ); alg names the scheme, key_id the phone's key, so a
 * phone can tell a blob it cannot open (made on another phone, or before its key was lost).
 */
object CardBlob {
    const val ALG = "A256GCM-AKS-1"
    private const val VERSION: Byte = 1
    const val IV_BYTES = 12

    fun seal(secret: CardSecret, cipher: CardCipher, keyId: String): E2eBlob {
        val plain = secret.payload()
        try {
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "unexpected IV size" }
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

    /** The IV a decrypting cipher must be set up with (before the user's check, which unlocks that cipher). Null: not our format. */
    fun ivOf(blob: E2eBlob): ByteArray? {
        if (blob.alg != ALG) return null
        val raw = runCatching { Base64.getDecoder().decode(blob.blob) }.getOrNull() ?: return null
        if (raw.size <= 1 + IV_BYTES || raw[0] != VERSION) return null
        return raw.copyOfRange(1, 1 + IV_BYTES)
    }

    /** Decrypts with a cipher set up with [ivOf]; null when the blob is not ours or does not open. */
    fun open(blob: E2eBlob, cipher: CardCipher): CardSecret? {
        if (ivOf(blob) == null) return null
        val raw = Base64.getDecoder().decode(blob.blob)
        val plain = runCatching { cipher.doFinal(raw.copyOfRange(1 + IV_BYTES, raw.size)) }.getOrNull() ?: return null
        return CardSecret.parse(plain)
    }

    /** Whether a card's stored blob was made with this phone's key ([keyId] null: this phone has no wallet key). */
    fun ours(card: WalletCard, keyId: String?): Boolean = card.e2e && keyId != null && card.e2eAlg == ALG && card.e2eKeyId == keyId
}

/**
 * A JSON request body that carries a card number and CVC: the ordinary fields are written as usual and the secret ones are
 * appended as bytes, so they never pass through a String or a JsonElement; the caller wipes the bytes once sent.
 */
object SecretJson {
    private val json = Json { encodeDefaults = true }

    fun body(fields: JsonObject, secret: CardSecret): ByteArray {
        val head = json.encodeToString(JsonObject.serializer(), fields).toByteArray(Charsets.UTF_8)
        val extra = secret.fields()
        try {
            // "{...}" -> "{..." + extra + "}"; an empty object "{}" takes the fields without the leading comma
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
