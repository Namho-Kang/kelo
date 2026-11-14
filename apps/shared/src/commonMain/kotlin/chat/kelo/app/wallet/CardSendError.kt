package chat.kelo.app.wallet

/** Why a card could not be sent for a payment the user allowed. */
enum class CardSendError {
    /** 404: nothing waits for it any more (used, denied, timed out) */
    NOT_WAITING,
    /** 422: the server turned the card down (another card's approval, or the number does not match) */
    MISMATCH,
    /** This phone cannot open the card's blob (made on another phone, or the key was lost) */
    NOT_ON_PHONE,
    /** The phone has no screen lock */
    NO_LOCK,
    /** The user backed out of the fingerprint / screen lock check */
    CANCELLED,
    /** The server refuses auto-pay without Keyguard (409 keyguard_required) */
    KEYGUARD_REQUIRED,
    NETWORK,
    OTHER,
}
