package chat.kelo.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.Caches
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.DraftStore
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.Shared
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * History, sending and following a reply, approvals, the effort level and documents. The unsent text is kept in
 * [drafts] (encrypted on the phone), so it is still there after the app was closed or killed; sending or signing out clears it.
 *
 * The one entry the screen talks to; the work is done by helpers sharing a [ChatHost]: [ChatSync] (lists the chat does not follow),
 * [ChatHistory], [ChatTurns], [ChatUploads], [ChatCardActions], [ChatSecrets], [ChatConsent] and [ChatDraft].
 */
class ChatViewModel(
    private val sessions: Sessions,
    drafts: DraftStore? = null,
    /** The line in the header's name pill: what the reply being followed is doing right now */
    activity: AgentActivity? = null,
    /** "reply done" / "failed" notifications while the app is away */
    watch: chat.kelo.app.chat.TurnWatch? = null,
    /** What only the app can do (a steady clock, starting the phone's own work after an approval, the log) */
    platform: ChatPlatform = ChatPlatform.Default,
) : ViewModel() {
    /** The newest messages kept from last time: shown at once (no placeholder), then refreshed in place by [reload] */
    private val kept: List<StoredMessage>? = runCatching { sessions.cached(Caches.MESSAGES) }.getOrNull()

    private val _state = MutableStateFlow(
        ChatState(
            input = drafts?.let { d -> runCatching { d.loadDraft() }.getOrNull() }.orEmpty(),
            items = kept.orEmpty().map(::toChatItem),
            loaded = kept != null,
            hasOlder = (kept?.size ?: 0) >= PAGE,
            providers = runCatching { sessions.cached(Caches.PROVIDERS) }.getOrNull().orEmpty(),
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val host = ChatHost(viewModelScope, sessions, _state, _events, drafts, activity, watch, platform)
    private val draft = ChatDraft(host)
    private val sync = ChatSync(host)
    private val history = ChatHistory(host, sync, kept)
    private val turns = ChatTurns(host, sync, history, draft)
    private val uploads = ChatUploads(host)
    private val cardActions = ChatCardActions(host, sync)
    private val secrets = ChatSecrets(host, turns)
    private val consent = ChatConsent(host, turns)

    init {
        // 마이 → 대화 기록 삭제: the rows on screen are gone on the server; ask again (the server now has none)
        viewModelScope.launch {
            sessions.historyCleared.drop(1).collect {
                history.lastId = 0
                _state.update { it.copy(items = emptyList(), hasOlder = false) }
                reload()
            }
        }
        sync.watchStatus()
    }

    // ---- questions from work the chat is not following (a background job, a scheduled run)
    /** GET /api/approvals/pending joined into the chat's waiting cards; nothing is asked when the poll says nothing waits. */
    fun refreshAsks(force: Boolean = false) = sync.refreshAsks(force)

    /**
     * Answers a waiting card the same way the 작업 page does ([rememberSite]: allow this kind of step on that site from now on;
     * [rememberSchedule]: in that scheduled job). [sendCard] runs first on Allow for a payment with an end-to-end card; when it
     * fails the question stays open and says why.
     */
    fun answerAsk(
        id: String, approve: Boolean, rememberSite: Boolean = false, rememberSchedule: Boolean = false,
        sendCard: (suspend () -> chat.kelo.app.wallet.CardSendError?)? = null,
    ) = cardActions.answerAsk(id, approve, rememberSite, rememberSchedule, sendCard)

    /** True once for a row made in this session: the screen lets it rise in, rows from history just appear. */
    fun takeFresh(key: String): Boolean = host.fresh.remove(key)

    fun setInput(v: String) {
        _state.update { it.copy(input = v) }
        draft.saveDraftSoon()
        host.stir()   // the owner is typing: the character wakes up
    }

    fun saveDraftNow() = draft.saveDraftNow()

    override fun onCleared() {
        saveDraftNow()
    }

    fun loadProviders() {
        viewModelScope.launch {
            runCatching {
                val api = host.api()
                api.get<List<ProviderInfo>>("/api/providers").also { sessions.keep(api, Caches.PROVIDERS, it) }
            }.onSuccess { p -> _state.update { it.copy(providers = p) } }
        }
    }

    // ---- history
    fun reload() = history.reload()

    /** The follow-ups and the messages they belong to (also after a reply, and when a follow-up notification is opened). */
    fun loadFollowups() = sync.loadFollowups()

    /** Older messages when the list is scrolled to its top (before_id). */
    fun loadOlder() = history.loadOlder()

    /** Another phone may have talked to the same assistant: while idle and in view, reload when the newest message changed. */
    suspend fun checkForNews() = history.checkForNews()

    // ---- a reply
    /**
     * Sends [given] (a card's "에이전트에게 알리기", or 다시 시도) or the typed text; the typed text takes the uploaded attachments
     * along ([words] names them in the message), and they leave the tray. [answers]: the quick-reply fields [given] answers.
     */
    fun send(given: String? = null, words: AttachmentWords? = null, answers: List<ReplyAnswer> = emptyList()) = turns.send(given, words, answers)

    /** The Stop button: cancelling the reply tells the server to stop (DELETE /api/turns/{id}). */
    fun stop() = turns.stop()

    /** 다시 시도 on a reply or an error: the user's message before it goes again, the same way the composer sends it. */
    fun retry(key: String) {
        if (_state.value.busy) return
        val text = MessageActions.retryText(_state.value.items, key) ?: return
        send(text)
    }

    /**
     * Follows a reply the server started by itself for this phone (a secure entry saved after the reply that asked had ended): the
     * same status line, events and end as a reply sent from here, without a bubble of the user's own.
     */
    fun followTurn(turnId: String) = turns.followTurn(turnId)

    // ---- the location card (location_request): the screen asks the phone (permission, place) and hands the answer here
    /** The card shows "getting your location" while the screen reads the place. */
    fun locationReading(approvalId: String) = cardActions.locationReading(approvalId)

    /**
     * POST /api/location-requests/{id}: [place] (lat, lon, name?, country_code?, accuracy?, sublocality?) when shared, null for "no"
     * (the system prompt refused, Not now, or no place could be read). The reply that waits on it goes on by itself.
     */
    fun answerLocation(approvalId: String, place: kotlinx.serialization.json.JsonObject?) = cardActions.answerLocation(approvalId, place)

    // ---- the briefing cards on screen (server briefing.py): asked for again when GET /api/agent/status "briefing_rev" changes
    fun refreshBriefings() = sync.refreshBriefings()

    // ---- secure entries the agent asked for (a key, a login, a card; server secret_requests.py)
    fun refreshSecrets() = sync.refreshSecrets()

    // ---- payment steps (server payment_steps.py): approve in another app, a one-time code, the page's PIN keypad in the card
    fun refreshPaymentSteps() = sync.refreshPaymentSteps()

    /** [answer]: approved | cancel | new_code | code | done. A step that is gone is folded by the next load. */
    fun answerPaymentStep(stepId: String, answer: String, code: String? = null) = cardActions.answerPaymentStep(stepId, answer, code)

    fun pinAllowed(approvalId: String) = cardActions.pinAllowed(approvalId)

    /** True once for a slot this phone allowed: the screen then starts the release by itself. */
    fun takePinOnAllow(slotId: String): Boolean = cardActions.takePinOnAllow(slotId)

    /** "결제 비밀번호 보내기": the owner's check, then the PIN goes once to the payment's slot. */
    fun releasePin(slotId: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?) = cardActions.releasePin(slotId, send)

    /**
     * The entry [req] was saved here (the value went straight to the vault or the wallet). [answer]'s turn is a reply the server
     * started to carry on, followed here like this phone's own; its end clears the entry's line. Without a reply on this phone
     * (none follows, or the reply already ended or failed before this answer came) the line goes at once ([SecretRules.followsHere]).
     */
    fun secretSaved(req: chat.kelo.app.chat.vault.SecretRequest, answer: chat.kelo.app.chat.vault.SaveAnswer, result: chat.kelo.app.chat.vault.SecretResult?) =
        secrets.secretSaved(req, answer, result)

    /** [Cancel] on an entry: the agent is told (it does not ask again); the card folds at once (or goes, when no reply runs here). */
    fun secretCancelled(req: chat.kelo.app.chat.vault.SecretRequest) = secrets.secretCancelled(req)

    /** The entry is no longer open on the server (answered on another phone, or it ran out). */
    fun secretGone(req: chat.kelo.app.chat.vault.SecretRequest) = secrets.secretGone(req)

    // ---- the secret sheet (a message held back for a key or password; SecretGuard)
    /**
     * The save step kept [value] in the Keychain (straight from the app to the vault): in the composer, every span holding that value
     * becomes [reference] ("(saved to Keychain: GitHub)"), so the rest of the message can still be sent; the sheet closes.
     */
    fun secretSaved(value: String, reference: String) = secrets.secretSaved(value, reference)

    /** A card number goes on to the wallet's card form, filled in there from [handoff] (memory only); the sheet closes. */
    fun secretToCard(handoff: SecretHandoff) = secrets.secretToCard(handoff)

    /** The card form kept the card: the composer gets [reference] in place of the number. */
    fun secretCardSaved(reference: String) = secrets.secretCardSaved(reference)

    /** "Send as is": the very same text again, with `"secrets": "allow"` (only ever after this tap). */
    fun secretSendAsIs() = secrets.secretSendAsIs()

    /** "Edit" (or the sheet dismissed): back to the composer with the text intact; a button's text is put there to be changed. */
    fun secretEdit() = secrets.secretEdit()

    // ---- asking once before chats go to the AI service ([AiConsent])
    /** The consent call is on its way (the Agree button waits) */
    val consentBusy: StateFlow<Boolean> = consent.consentBusy
    val consentError: StateFlow<String?> = consent.consentError

    /** "Agree": the server keeps it (once, for every device), then the very same message goes. */
    fun consentAgree() = consent.consentAgree()

    /** "Not now" (or the question dismissed): nothing is sent; the text stays in the composer (a button's text is put there). */
    fun consentNotNow() = consent.consentNotNow()

    /** Something the screen itself could not do (the camera), shown as an error in the chat. */
    fun showError(e: ChatError) = host.addError(e)

    /** Answers an approval; [rememberSchedule]: keep allowing this step in the scheduled job it came from. */
    fun approve(id: String, ok: Boolean, rememberSite: Boolean = false, rememberSchedule: Boolean = false) =
        cardActions.approve(id, ok, rememberSite = rememberSite, rememberSchedule = rememberSchedule)

    /**
     * Allow on a payment with an end-to-end card: [send] asks for the owner's check, decrypts the card and sends it to the
     * approval's slot; only when it got there is the payment allowed. A failure leaves the question open (the user can try again
     * or say no) and says why; a slot that is gone means the question is gone too.
     */
    fun approveWithCard(id: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?) = cardActions.approveWithCard(id, send)

    /** "카드 보내기" on a card_needed row (the payment was allowed already). */
    fun sendCard(approvalId: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?) = cardActions.sendCard(approvalId, send)

    // ---- the chips under the list
    fun pickEffort(value: String, onProfile: (Profile) -> Unit) {
        viewModelScope.launch {
            try {
                onProfile(host.api().put<Profile>("/api/profile", buildJsonObject { put("effort", value) }))
            } catch (e: ApiError) {
                host.addError(ChatError.Message(e.detail))
            }
        }
    }

    /** The user finished or closed a secure / connect card in the chat: it folds into one line. */
    fun settleCard(key: String, state: CardState) = cardActions.settleCard(key, state)

    /** The in-chat address form [formId] was answered, cancelled or found gone here: its card folds into one line. */
    fun shippingAnswered(formId: String, outcome: ShippingOutcome) = cardActions.shippingAnswered(formId, outcome)

    // ---- something shared from another app
    /** Text goes into the draft (after what is already there); a document goes into the tray like one picked with "+". */
    fun receive(shared: Shared) {
        when (shared) {
            is Shared.Text -> setInput(DraftText.append(_state.value.input, shared.text))
            is Shared.File -> attach(image = false, label = shared.name) {
                val f = java.io.File(shared.path)
                Prepared(shared.name, null, shared.mime, length = f.length(), open = { f.inputStream() }, done = { f.delete() })
            }
            is Shared.Unsupported -> host.addError(ChatError.Unsupported(shared.name))
            is Shared.Failed -> host.addError(ChatError.ShareFailed)
        }
    }

    // ---- attachments ("+": a photo, the camera, a file)
    /**
     * Puts a chip in the tray at once and uploads in the background (PUT /api/uploads/{name}): [prepare] reads the pick (and makes
     * a photo smaller) off the main thread. A failure stays on the chip with the reason (the server's own words when it said no).
     */
    fun attach(image: Boolean, label: String, prepare: () -> Prepared) = uploads.attach(image, label, prepare)

    /**
     * The chip's ×: an upload still running is cancelled; one already up is taken off the server again (DELETE /api/uploads/{name}),
     * since nothing was sent with it.
     */
    fun removeAttachment(id: String) = uploads.removeAttachment(id)

    // ---- follow-up buttons under the agent's messages
    /**
     * Does one button of the follow-up [item] ([step] from [chat.kelo.app.followup.FollowupRules.step]). Done or already settled
     * elsewhere: the buttons give way to one line and the list loads again. Failed: they stay, and the reason shows in the chat.
     */
    fun runAction(item: chat.kelo.app.followup.Followup, step: chat.kelo.app.followup.FollowupStep) = cardActions.runAction(item, step)

    /** The sign-in sheet opened from a button was filled in: its buttons give way to one line. */
    fun actionSignedIn(item: chat.kelo.app.followup.Followup) = cardActions.actionSignedIn(item)

    companion object {
        const val PAGE = 100
        const val DRAFT_DELAY_MS = 400L
    }
}
