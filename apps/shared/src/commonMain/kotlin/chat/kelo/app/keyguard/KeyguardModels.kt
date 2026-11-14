package chat.kelo.app.keyguard

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/*
 * Keyguard (docs/keyguard.md; spec docs/security/e2e-and-phone-key.md 2, 5.9, 5.14, 5.15): the key of the owner's passwords, keys and
 * cards is held by the owner's phones instead of a file on the server. Plain models and rules here (tested, shareable); the phone's
 * Keystore key and the prompts are in KeyguardKey.kt / KeyguardUi.kt.
 */

/** GET /api/keyguard (works while locked). [mode] "server" (off) or "phone" (on). */
@Serializable
data class KeyguardView(
    val mode: String = MODE_SERVER,
    val locked: Boolean = false,
    val recovery: Boolean = false,
    val phones: List<KeyguardPhone> = emptyList(),
) {
    val on: Boolean get() = mode == MODE_PHONE

    /** This phone holds a copy (its device id from GET /api/devices "current"). */
    fun holds(deviceId: Long?): Boolean = deviceId != null && phones.any { it.deviceId == deviceId }

    companion object {
        const val MODE_SERVER = "server"
        const val MODE_PHONE = "phone"
    }
}

@Serializable
data class KeyguardPhone(@SerialName("device_id") val deviceId: Long = 0, val name: String = "", val alg: Int = 0)

/** POST /api/keyguard/enroll: the key to wrap ([key], base64url text), and the recovery code when this turns it on (shown ONCE). */
@Serializable
data class EnrollAnswer(
    @SerialName("enrollment_id") val enrollmentId: String = "",
    val key: String = "",
    val enabling: Boolean = false,
    @SerialName("recovery_code") val recoveryCode: String? = null,
) {
    override fun toString(): String = "EnrollAnswer($enrollmentId, enabling=$enabling)"   // never the key or the code
}

/** GET /api/keyguard/blob: this phone's wrapped copy. */
@Serializable
data class BlobAnswer(val alg: Int = 0, val blob: String = "")

/**
 * This phone's wrapped copy of the key (5.14, 2.4 Android): `0x01` ‖ 12-byte IV ‖ AES-256-GCM ciphertext with its tag, the AAD being
 * the ASCII string "kelo-keyguard v1"; sent as standard base64. The server never opens it.
 */
object KeyguardBlob {
    const val ALG_ANDROID: Int = 1
    const val IV_BYTES = 12
    val AAD: ByteArray = "kelo-keyguard v1".toByteArray(Charsets.US_ASCII)

    fun encode(iv: ByteArray, sealed: ByteArray): String {
        require(iv.size == IV_BYTES) { "IV must be 12 bytes" }
        return Base64.getEncoder().encodeToString(byteArrayOf(ALG_ANDROID.toByte()) + iv + sealed)
    }

    /** (IV, ciphertext) of an Android blob; null when it is not one (another phone kind, damaged). */
    fun decode(blob: String): Pair<ByteArray, ByteArray>? {
        val raw = runCatching { Base64.getDecoder().decode(blob.trim()) }.getOrNull() ?: return null
        if (raw.size <= 1 + IV_BYTES + 16 || raw[0].toInt() != ALG_ANDROID) return null
        return raw.copyOfRange(1, 1 + IV_BYTES) to raw.copyOfRange(1 + IV_BYTES, raw.size)
    }
}

/** The pure rules of the Keyguard screens. */
object KeyguardRules {
    const val CODE_LOCKED = ApiError.CODE_LOCKED
    const val CODE_ENV_KEY = "env_key"
    const val CODE_WRONG_KEY = "wrong_key"
    /** The server's name for "this is the last phone holding the key" (DELETE /api/devices: last_keyguard_phone) */
    val LAST_PHONE_CODES = setOf("last_phone", "last_keyguard_phone")

    /** A recovery code as people type it: letters and digits only, upper case (the server does the same). */
    fun normalizeCode(text: String): String = text.uppercase().filter { it.isLetterOrDigit() }

    /** The recovery code shown in groups of four ("ABCD-EFGH-…"), easier to write down and read back. */
    fun groupCode(code: String): String = normalizeCode(code).chunked(4).joinToString("-")

    /** The code on screen: three groups a line, so a narrow phone never breaks a group in two ("ABCD-EFGH-JKLM\nNPQR-…"). */
    fun codeLines(code: String): String = groupCode(code).split("-").chunked(3).joinToString("\n") { it.joinToString("-") }

    /** Typed enough to send (the server's codes have 24 characters). */
    fun codeReady(text: String): Boolean = normalizeCode(text).length >= 20

    /** Whether a failed call means "Kelo is locked" (423 locked). */
    fun isLocked(e: ApiError): Boolean = e.status == 423 || e.code == CODE_LOCKED
}

/** The Keyguard API (owner only; key material only inside KE2E from an owner phone). */
object KeyguardCalls {
    suspend fun view(api: KeloApi): KeyguardView = api.get("/api/keyguard")
    suspend fun enroll(api: KeloApi): EnrollAnswer = api.post("/api/keyguard/enroll")

    suspend fun finish(api: KeloApi, enrollmentId: String, blob: String): KeyguardView =
        api.post("/api/keyguard/enroll/finish", buildJsonObject {
            put("enrollment_id", enrollmentId); put("alg", KeyguardBlob.ALG_ANDROID); put("blob", blob)
        })

    suspend fun blob(api: KeloApi): BlobAnswer = api.get("/api/keyguard/blob")

    /** The unwrapped key as bytes (never a String the app keeps), sent once and wiped by the caller. */
    suspend fun unlock(api: KeloApi, key: ByteArray) {
        val head = "{\"key\":\"".toByteArray()
        val tail = "\"}".toByteArray()
        val body = ByteArray(head.size + key.size + tail.size)
        head.copyInto(body); key.copyInto(body, head.size); tail.copyInto(body, head.size + key.size)
        try { api.requestBytes("POST", "/api/keyguard/unlock", body) } finally { body.fill(0) }
    }

    suspend fun recover(api: KeloApi, code: String): JsonElement =
        api.post("/api/keyguard/recover", buildJsonObject { put("code", KeyguardRules.normalizeCode(code)) })

    suspend fun removePhone(api: KeloApi, deviceId: Long): KeyguardView = api.delete("/api/keyguard/phones/$deviceId")
    suspend fun disable(api: KeloApi): KeyguardView = api.post("/api/keyguard/disable")
}
