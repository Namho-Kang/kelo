package chat.kelo.app.chat

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val phase: Phase? = null,
    val input: String = "",
    val providers: List<ProviderInfo> = emptyList(),
    /** More stored messages before the first one shown */
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** The last refresh did not reach the server: what shows may be old (kept from last time) */
    val offline: Boolean = false,
    /** Photos and files picked with "+", going with the next message */
    val attachments: List<Attachment> = emptyList(),
    /** The follow-up each agent message carries (GET /api/followups, by its message_id): its buttons go under that message */
    val followups: Map<Long, chat.kelo.app.followup.Followup> = emptyMap(),
    /** Follow-ups with a button being done right now (by follow-up id) */
    val acting: Set<String> = emptySet(),
    /** What a button pressed on this phone did (by follow-up id), shown once its item is no longer open */
    val outcomes: Map<String, ActionOutcome> = emptyMap(),
    /** Questions waiting on the user that no followed reply shows: a background job's approval, a scheduled run's ([ChatAsks]) */
    val asks: List<ChatAsk> = emptyList(),
    /** A message the server held back because it holds a key or password ([SecretGuard]): the sheet asks what to do with it */
    val secretAsk: SecretAsk? = null,
    /** A message held back until someone agrees that chats go to the AI service ([AiConsent]): the question is on screen */
    val consentAsk: ConsentAsk? = null,
    /**
     * Secure entries the agent asked for (GET /api/secret-requests, open ones; plus the ones answered on this phone, folded until
     * the next load). The chat shows each under the agent message it belongs to, or at the end ([ChatReducer.withPending]).
     */
    val secrets: List<chat.kelo.app.chat.vault.SecretRequest> = emptyList(),
    /** Payment steps that need the user (GET /api/payment-steps and the payment_step events): shown at the bottom, newest last */
    val paymentSteps: List<PaymentStep> = emptyList(),
    /** Payment PINs waiting for this phone to release them (pin_needed events) */
    val pinNeeds: List<PinNeed> = emptyList(),
) {
    /** An attachment is still on its way up: sending waits for it */
    val uploading: Boolean get() = attachments.any { it.state == AttachState.UPLOADING }
    /** What the next message would take along */
    val ready: List<Attachment> get() = attachments.filter { it.state == AttachState.DONE }
}

/** One-off things the screen reacts to (a haptic tick), not kept in the state. */
enum class ChatEvent { SENT, REPLY_DONE, ERROR, PAYMENT_AUTO }
