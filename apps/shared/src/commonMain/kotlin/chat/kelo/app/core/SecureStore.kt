package chat.kelo.app.core

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where values are kept (SharedPreferences on the phone, a map in tests). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Encrypts small values. The output carries everything needed to decrypt it except the key. */
interface ValueCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** AES-256-GCM, output = 12-byte IV + ciphertext with its 128-bit tag. The key comes from [key] (Android Keystore on the phone). */
open class AesGcmCipher(private val key: () -> SecretKey) : ValueCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())   // the provider picks a fresh random IV
        val iv = c.iv
        require(iv.size == IV_BYTES) { "unexpected IV size ${iv.size}" }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_BYTES) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        return c.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Where the chat's unsent text is kept between launches (the process may be killed while the user is away). */
interface DraftStore {
    fun loadDraft(): String
    /** An empty text removes it */
    fun saveDraft(text: String)
}

/**
 * The server this device is signed in to, and its own device token. [pin]: the server's KE2E key this phone pinned (null: a
 * server that never offered KE2E; calls then go as they are).
 */
data class Session(val serverUrl: String, val token: String, val pin: chat.kelo.app.core.e2e.KeyPin? = null) {
    override fun toString(): String = "Session($serverUrl, pin=${pin?.key?.kid})"   // never the token
}

/**
 * What the app keeps on the phone. The server address and the device token are stored only encrypted ([ValueCipher], an
 * Android Keystore AES-GCM key on the phone): nothing secret is written in plain text. The language choice is not secret.
 */
class SecureStore(private val kv: KeyValueStore, private val cipher: ValueCipher) : DraftStore {

    var language: String?
        get() = kv.getString(KEY_LANGUAGE)
        set(value) = if (value == null) kv.remove(KEY_LANGUAGE) else kv.putString(KEY_LANGUAGE, value)

    /** The language pick has been handed to the system's per-app language setting (Android 13+, see LocaleSupport). */
    var languageSynced: Boolean
        get() = kv.getString(KEY_LANGUAGE_SYNCED) == "1"
        set(value) = if (value) kv.putString(KEY_LANGUAGE_SYNCED, "1") else kv.remove(KEY_LANGUAGE_SYNCED)

    /** The saved session, or null when signed out (or when the stored values can no longer be decrypted). */
    fun session(): Session? {
        val url = getSecret(KEY_SERVER)
        val token = getSecret(KEY_TOKEN)   // both read, so both are dropped when the key is gone
        return if (url != null && token != null) Session(url, token, pinFor(url)) else null
    }

    fun saveSession(session: Session) {
        putSecret(KEY_SERVER, session.serverUrl)
        putSecret(KEY_TOKEN, session.token)
        session.pin?.let { savePin(session.serverUrl, it) }
    }

    /**
     * The KE2E key pinned for [serverUrl] (kept per server, and kept on sign-out: once this phone has seen KE2E on a server it never
     * talks to it in the clear again, spec 5.5). Encrypted like the session so it cannot be swapped in a backup.
     */
    fun pinFor(serverUrl: String): chat.kelo.app.core.e2e.KeyPin? =
        getSecret(pinKey(serverUrl))?.let { chat.kelo.app.core.e2e.KeyPin.decode(it) }

    fun savePin(serverUrl: String, pin: chat.kelo.app.core.e2e.KeyPin) = putSecret(pinKey(serverUrl), pin.encode())

    /** Pair again with another key (the old one is replaced by the scanned link's). */
    fun forgetPin(serverUrl: String) = kv.remove(pinKey(serverUrl))

    private fun pinKey(serverUrl: String): String = KEY_PIN_PREFIX + java.security.MessageDigest.getInstance("SHA-256")
        .digest(serverUrl.trimEnd('/').lowercase().toByteArray()).take(12).joinToString("") { "%02x".format(it) } + ".enc"

    /** Forget the token and the unsent chat text. The server address stays (encrypted) so the connect screen can offer it again. */
    fun clearSession() {
        kv.remove(KEY_TOKEN)
        kv.remove(KEY_DRAFT)
    }

    /** The chat's unsent text, kept encrypted like the session (it may hold anything the user typed). */
    override fun loadDraft(): String = getSecret(KEY_DRAFT).orEmpty()

    override fun saveDraft(text: String) {
        if (text.isEmpty()) kv.remove(KEY_DRAFT) else putSecret(KEY_DRAFT, text)
    }

    /** The last server this device used, for pre-filling the address field. */
    fun lastServerUrl(): String? = getSecret(KEY_SERVER)

    private fun putSecret(key: String, value: String) {
        kv.putString(key, B64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8))))
    }

    private fun getSecret(key: String): String? {
        val stored = kv.getString(key) ?: return null
        return try {
            cipher.decrypt(B64D.decode(stored)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            kv.remove(key)   // the key is gone (app data restored elsewhere, keystore reset): the value is useless
            null
        }
    }

    companion object {
        const val KEY_SERVER = "server_url.enc"
        const val KEY_TOKEN = "device_token.enc"
        const val KEY_LANGUAGE = "language"
        const val KEY_LANGUAGE_SYNCED = "language_synced"
        const val KEY_DRAFT = "chat_draft.enc"
        const val KEY_PIN_PREFIX = "server_pin."
        private val B64 = Base64.getEncoder()
        private val B64D = Base64.getDecoder()
    }
}
