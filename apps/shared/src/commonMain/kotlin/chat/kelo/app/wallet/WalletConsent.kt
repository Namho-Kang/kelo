package chat.kelo.app.wallet

/**
 * The risk notices the owner accepts: before a card is saved (shown with its checkbox every time) and before auto-pay is turned on
 * for a card. What was accepted is kept on this device only, as the notice's version and the time: the server's wallet API has no
 * field for it (POST/PATCH /api/wallet/cards ignore unknown fields).
 */
interface WalletConsent {
    /** The card notice was accepted for a card that was then saved. */
    fun cardSaved(cardId: String, at: Long = System.currentTimeMillis())

    /** The auto-pay notice was accepted for [cardId]. */
    fun autoPayAccepted(cardId: String, at: Long = System.currentTimeMillis())

    fun forget(cardId: String)

    companion object {
        /** Raise when the wording of a notice changes in meaning. */
        const val CARD_NOTICE_VERSION = 1
        const val AUTOPAY_NOTICE_VERSION = 1
    }
}

object ConsentRules {
    fun cardKey(cardId: String) = "card_notice:$cardId"
    fun autoPayKey(cardId: String) = "autopay_notice:$cardId"

    /** "v1@1762500000000" */
    fun record(version: Int, at: Long) = "v$version@$at"

    /** (version, time) of a stored record, or null. */
    fun parse(text: String?): Pair<Int, Long>? {
        val m = Regex("v(\\d+)@(\\d+)").matchEntire(text ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toLong()
    }

    /** Saving is possible only with the notice's box ticked and the card details complete. */
    fun canSave(detailsReady: Boolean, acknowledged: Boolean): Boolean = detailsReady && acknowledged
}
