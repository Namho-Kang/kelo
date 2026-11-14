package chat.kelo.app.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** What asking for the card key came to. */
sealed interface CardAuth {
    data class Ok(val cipher: CardCipher, val keyId: String) : CardAuth
    /** The owner backed out of the check */
    data object Cancelled : CardAuth
    /** The device has no screen lock, so no card key can exist */
    data object NoLock : CardAuth
    /** This device has no key that opens this card (made elsewhere, or the key was invalidated) */
    data object KeyLost : CardAuth
    data class Failed(val message: String) : CardAuth
}

/**
 * The device's own card key and the owner's check that comes with each use. The phone implements it with its hardware key store;
 * [None] is "this device never holds a card" (the desktop): the shared wallet pages read [available] and show "add a card on the
 * phone" instead of the add form.
 */
interface CardKeys {
    /** This device can hold a card key at all. */
    val available: Boolean

    /** The key's id in the blobs this device makes, or null when it has no usable key. */
    fun keyId(): String?

    /** Asks for the owner's check and hands back a cipher to encrypt a card (or a re-entered one) with. */
    fun forSaving(title: String, subtitle: String, onDone: (CardAuth) -> Unit)

    /** Asks for the owner's check and hands back a cipher to decrypt a blob whose IV is [iv]. */
    fun forPaying(iv: ByteArray, title: String, subtitle: String, onDone: (CardAuth) -> Unit)

    /** An owner's check is on screen right now (the card pages must not lock because of what it does to the app). */
    fun promptActive(): Boolean

    /** The screen-lock check is a screen of its own over the app (Android 9 and older), so the app leaving the front during it says nothing. */
    val credentialLeavesApp: Boolean

    /** The device's screen lock gives the owner a way to prove it is them; false = [forSaving] would answer [CardAuth.NoLock]. */
    fun deviceSecure(): Boolean

    companion object {
        val None: CardKeys = object : CardKeys {
            override val available = false
            override val credentialLeavesApp = false
            override fun keyId(): String? = null
            override fun forSaving(title: String, subtitle: String, onDone: (CardAuth) -> Unit) = onDone(CardAuth.KeyLost)
            override fun forPaying(iv: ByteArray, title: String, subtitle: String, onDone: (CardAuth) -> Unit) = onDone(CardAuth.KeyLost)
            override fun promptActive() = false
            override fun deviceSecure() = false
        }
    }
}

/**
 * Reads a card with the camera (Android: CameraX, ML Kit and Google Pay's recognition). Null in AppServices = no scan button.
 * [Button] draws the scan button and handles the whole scan; it calls [onResult] with a reading and nothing else keeps it.
 */
interface CardScanner {
    /** A scan screen is up over the add form (Google Pay's, which sends the app to the background): the card pages must not lock because of it. */
    fun active(): Boolean


    @Composable fun Button(onResult: (CardScanResult) -> Unit, modifier: androidx.compose.ui.Modifier)
}
