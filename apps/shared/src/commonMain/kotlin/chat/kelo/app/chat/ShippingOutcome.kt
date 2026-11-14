package chat.kelo.app.chat

/** What happened to an in-chat address form when it was answered here; the chat folds the card to match. */
sealed interface ShippingOutcome {
    /** Sent: [label] (the nickname when it was saved) and the address on one line; [payment] the way to pay that was chosen */
    data class Sent(val label: String, val line: String, val payment: String?) : ShippingOutcome
    data object Cancelled : ShippingOutcome
    /** 404: the form is gone (timed out, or the reply was stopped) */
    data object Gone : ShippingOutcome
}
