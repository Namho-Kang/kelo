package chat.kelo.app.wallet

/**
 * When the card pages are open. They open only after the owner's check (fingerprint, face or screen lock) and close again when the
 * app goes to the background, or after [timeoutMs] without a touch. Leaving the pages closes them too (the holder is dropped).
 * Times are milliseconds from any monotonic clock.
 */
class WalletLock(private val timeoutMs: Long = TIMEOUT_MS) {
    private var lastActive: Long? = null

    fun unlock(now: Long) { lastActive = now }

    /** The user did something on an open page: the two minutes start again. */
    fun touch(now: Long) { if (isOpen(now)) lastActive = now }

    fun lock() { lastActive = null }

    /** The app went to the background (or the screen turned off). */
    fun background() = lock()

    fun isOpen(now: Long): Boolean {
        val at = lastActive ?: return false
        if (now - at >= timeoutMs || now < at) { lastActive = null; return false }
        return true
    }

    /** How long until it closes by itself (0 when closed). */
    fun remaining(now: Long): Long {
        val at = lastActive ?: return 0
        return (timeoutMs - (now - at)).coerceAtLeast(0)
    }

    companion object {
        const val TIMEOUT_MS = 2 * 60 * 1000L
    }
}
