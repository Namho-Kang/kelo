package chat.kelo.app.keyguard

/** A cipher the device's key gave out for one use (enrol: encrypt, unlock: decrypt), its AAD already set. */
interface KeyguardCipher {
    val iv: ByteArray
    fun doFinal(input: ByteArray): ByteArray
}

/** What asking for the Keyguard key came to. */
sealed interface KeyguardAuth {
    class Ok(val cipher: KeyguardCipher) : KeyguardAuth
    /** The owner backed out of the check */
    data object Cancelled : KeyguardAuth
    /** The device has no screen lock, so no key can ask for it */
    data object NoLock : KeyguardAuth
    /** This device has no usable key for this server (removed screen lock, another phone's blob) */
    data object KeyLost : KeyguardAuth
    data class Failed(val message: String) : KeyguardAuth
}

/**
 * This device's copy of the Keyguard key for the signed-in server, and the owner's check that releases it (phone: the hardware key
 * store). [None]: the device never holds a copy (the desktop, spec 2.4); its page shows the state, the phones and the recovery code.
 */
interface KeyguardKeys {
    /** This device can hold a copy at all. */
    val available: Boolean

    /** Every use asks for the owner's check (false: Android 8-10, where one check opens a 30-second window; the page says so). */
    val checkEveryUse: Boolean

    /** The owner's check, then a cipher that wraps the key with a NEW device key. */
    fun forWrap(title: String, subtitle: String, onDone: (KeyguardAuth) -> Unit)

    /** The owner's check, then a cipher that opens this device's blob whose IV is [iv]. */
    fun forUnwrap(iv: ByteArray, title: String, subtitle: String, onDone: (KeyguardAuth) -> Unit)

    /** Deletes this device's key for the signed-in server (its copy was removed, or Keyguard was turned off). */
    fun forget()

    companion object {
        val None: KeyguardKeys = object : KeyguardKeys {
            override val available = false
            override val checkEveryUse = true
            override fun forWrap(title: String, subtitle: String, onDone: (KeyguardAuth) -> Unit) = onDone(KeyguardAuth.KeyLost)
            override fun forUnwrap(iv: ByteArray, title: String, subtitle: String, onDone: (KeyguardAuth) -> Unit) = onDone(KeyguardAuth.KeyLost)
            override fun forget() {}
        }
    }
}
