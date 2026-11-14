package chat.kelo.app.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.chat.SecretHandoff
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.YearMonth

/** One card's payments, newest first, older ones a page at a time. [items] is null until the first page is here. */
data class CardHistoryState(
    val items: List<WalletPayment>? = null,
    val next: Long? = null,
    val loading: Boolean = false,
    val msg: String = "",
)

data class CardUiState(val msg: String = "", val history: CardHistoryState = CardHistoryState())

/**
 * One card's page: the list of its payments and the changes the owner makes (default card, auto-pay off, rename, delete). A change
 * that worked tells the wallet list to load again ([WalletEvent.Changed]).
 */
class CardViewModel(private val sessions: Sessions, private val consent: WalletConsent, private val cardId: String) : ViewModel() {
    private val _state = MutableStateFlow(CardUiState())
    val state: StateFlow<CardUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { loadHistory(null) }

    private fun act(after: WalletEvent? = null, block: suspend WalletCalls.() -> Unit) {
        _state.update { it.copy(msg = "") }
        viewModelScope.launch {
            try {
                WalletCalls(sessions.requireApi()).block()
                _events.trySend(WalletEvent.Confirm)
                _events.trySend(WalletEvent.Changed)
                after?.let { _events.trySend(it) }
            } catch (e: ApiError) {
                _state.update { it.copy(msg = e.detail) }
                _events.trySend(WalletEvent.Reject)
            }
        }
    }

    fun makeDefault() = act { makeDefault(cardId) }
    fun turnAutoPayOff() = act { patch(cardId, buildJsonObject { put("auto_pay", false) }) }
    fun rename(name: String) = act { patch(cardId, buildJsonObject { put("label", name.trim()) }) }

    /** Deletes the card; [newDefault] takes its place when the default card goes with two or more others left. */
    fun delete(newDefault: String? = null) = act(after = WalletEvent.Deleted) { delete(cardId, newDefault); consent.forget(cardId) }

    fun loadMore() = loadHistory(_state.value.history.next)

    private fun loadHistory(before: Long?) {
        _state.update { it.copy(history = it.history.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val page = WalletCalls(sessions.requireApi()).cardPayments(cardId, before)
                _state.update { s ->
                    s.copy(history = CardHistoryState(
                        items = CardHistoryRules.merge(if (before == null) emptyList() else s.history.items.orEmpty(), page),
                        next = page.nextBeforeId,
                    ))
                }
            } catch (e: ApiError) {
                _state.update { s -> s.copy(history = s.history.copy(msg = e.detail, items = s.history.items ?: emptyList())) }
            } finally {
                _state.update { it.copy(history = it.history.copy(loading = false)) }
            }
        }
    }
}

/** The dialogs of turning auto-pay on for a card. */
data class AutoPayUiState(
    /** The notice to tick, before anything is opened. */
    val asking: WalletCard? = null,
    /** This device holds no copy of the card (added elsewhere): typing the number is the last resort. */
    val notHere: WalletCard? = null,
    val failed: Boolean = false,
    /** Auto-pay needs Keyguard: its dialog comes first, and turning Keyguard on goes on from there. */
    val needKeyguard: WalletCard? = null,
)

/**
 * Turning auto-pay on for a card: the notice (ticked), then this device's own end-to-end copy opened with the owner's check and
 * sent once to the auto-pay endpoint (the number is never typed again, never shown or logged).
 */
class AutoPayViewModel(private val sessions: Sessions, private val cardKeys: CardKeys) : ViewModel() {
    private val _state = MutableStateFlow(AutoPayUiState())
    val state: StateFlow<AutoPayUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** "Turn it on for this card": the notice if Keyguard is on, else the Keyguard dialog first. */
    fun request(card: WalletCard, keyguardOn: Boolean?) {
        _state.update { if (AutoPayGate.mayTurnOn(keyguardOn)) it.copy(asking = card) else it.copy(needKeyguard = card) }
    }

    fun dismissAsking() = _state.update { it.copy(asking = null) }
    fun dismissNotHere() = _state.update { it.copy(notHere = null) }
    fun dismissFailed() = _state.update { it.copy(failed = false) }
    fun dismissKeyguard() = _state.update { it.copy(needKeyguard = null) }

    /** Keyguard was turned on from its dialog: on to the notice. */
    fun keyguardEnabled() = _state.update { s -> s.copy(needKeyguard = null, asking = s.needKeyguard) }

    /** The notice was confirmed; [subtitle] is the check's line with the card's name in it. */
    fun confirm(title: String, subtitle: (WalletCard) -> String) {
        val card = _state.value.asking ?: return
        _state.update { it.copy(asking = null) }
        viewModelScope.launch {
            val r = runCatching {
                turnOnAutoPay(WalletCalls(sessions.requireApi()), cardKeys, card, title, subtitle(card))
            }.getOrElse { CardSendError.OTHER }
            when (r) {
                null -> { _events.trySend(WalletEvent.Confirm); _events.trySend(WalletEvent.Changed) }
                CardSendError.NOT_ON_PHONE -> _state.update { it.copy(notHere = card) }
                CardSendError.KEYGUARD_REQUIRED -> _state.update { it.copy(needKeyguard = card) }   // the server says so: the same dialog
                CardSendError.CANCELLED -> {}
                else -> { _state.update { it.copy(failed = true) }; _events.trySend(WalletEvent.Reject) }
            }
        }
    }
}

/** The words the save's check and failures use, resolved in composition (the save runs in a coroutine). */
class SaveTexts(val title: String, val subtitle: String, val failedSend: String, val words: SaveWords)

data class AddCardUiState(val draft: AddCardDraft = AddCardDraft(), val busy: Boolean = false, val problem: String = "")

/**
 * 카드 등록, the work behind its steps: the draft (number and CVC in memory only, kept through the lock by [WalletPlaces] unless
 * [inline]), the owner's check, sealing with this device's key and the upload. [inline]: a card entry in the chat; nothing is kept
 * through the wallet's lock or taken from a held-back message.
 */
class AddCardViewModel(
    private val sessions: Sessions, private val cardKeys: CardKeys, private val places: WalletPlaces,
    private val consent: WalletConsent, private val handoff: SecretHandoff, private val inline: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(
        AddCardUiState(
            if (inline) AddCardDraft()
            // a card number from a message the chat held back (SecretGuard): already filled in, taken out of the hand-off at once
            else places.add() ?: handoff.peek()?.let { v -> handoff.clear(); AddCardDraft(number = CardNumber.digits(v).take(19)) } ?: AddCardDraft(),
        ),
    )
    val state: StateFlow<AddCardUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _saved = Channel<WalletCard>(Channel.BUFFERED)
    /** The new card (its nickname and last four digits; never the number). */
    val saved = _saved.receiveAsFlow()

    /** Every change of the draft goes through here. */
    fun set(d: AddCardDraft) {
        _state.update { it.copy(draft = d) }
        if (!inline) places.saveAdd(d)
    }

    fun edit(change: (AddCardDraft) -> AddCardDraft) = set(change(_state.value.draft))

    /** Goes back one step (true), or says there is none (false). */
    fun stepBack(): Boolean {
        val s = _state.value
        val prev = AddCardSteps.prev(s.draft.step)
        if (prev == null || s.busy) return false
        set(s.draft.copy(step = prev))
        return true
    }

    fun save(texts: SaveTexts) {
        val draft = _state.value.draft
        val now = YearMonth.now()
        AddCardSteps.firstBlocking(draft.number, draft.expiry, draft.cvc, now)?.let { set(draft.copy(step = it)); return }
        val ok = Expiry.check(draft.expiry, now) as? ExpiryCheck.Ok ?: return
        val secret = CardSecret.of(draft.number, draft.cvc) ?: return
        _state.update { it.copy(busy = true, problem = "") }
        viewModelScope.launch {
            try {
                val a = cardKeys.awaitSaving(texts.title, texts.subtitle)
                if (a !is CardAuth.Ok) {
                    _state.update { it.copy(problem = texts.words.of(a)) }
                    return@launch
                }
                val blob = CardBlob.seal(secret, a.cipher, a.keyId)
                val card = WalletCalls(sessions.requireApi()).add(
                    NewCard(draft.holder, draft.postal, ok.month, ok.year, CardNumber.brand(draft.number), label = draft.nickname.trim()), blob, secret,
                )
                consent.cardSaved(card.id)
                if (!inline) {
                    places.clear()
                    handoff.kept()   // the chat puts "(saved to Keychain: …)" in place of the number, if it came from there
                }
                _state.update { it.copy(draft = AddCardDraft()) }
                _events.trySend(WalletEvent.Confirm)
                _saved.trySend(card)
            } catch (e: ApiError) {
                _state.update { it.copy(problem = if (e.isNetwork) texts.failedSend else e.detail) }
                _events.trySend(WalletEvent.Reject)
            } finally {
                secret.wipe()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** The page's own copy goes with the page; the kept place is the wallet's to forget. */
    override fun onCleared() { _state.update { it.copy(draft = AddCardDraft()) } }
}

data class ReenterUiState(val draft: ReenterDraft, val busy: Boolean = false, val problem: String = "", val needKeyguard: Boolean = false)

/**
 * The number and CVC of a saved card typed again: to make a new copy on this device (a card it cannot open), or to turn auto-pay on
 * (the server keeps its own sealed copy then). Kept through the lock like 카드 등록's fields ([WalletPlaces]: memory only).
 */
class ReenterViewModel(
    private val sessions: Sessions, private val cardKeys: CardKeys, private val places: WalletPlaces,
    private val consent: WalletConsent, private val card: WalletCard, private val autoPay: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ReenterUiState(places.reenter(card.id, autoPay) ?: ReenterDraft(card.id, autoPay)))
    val state: StateFlow<ReenterUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun set(d: ReenterDraft) {
        _state.update { it.copy(draft = d) }
        places.saveReenter(d)
    }

    fun edit(change: (ReenterDraft) -> ReenterDraft) = set(change(_state.value.draft))
    fun dismissKeyguard() = _state.update { it.copy(needKeyguard = false) }

    fun save(texts: SaveTexts) {
        val d = _state.value.draft
        val secret = CardSecret.of(d.number, d.cvc) ?: return
        _state.update { it.copy(busy = true, problem = "") }
        viewModelScope.launch {
            try {
                val a = cardKeys.awaitSaving(texts.title, texts.subtitle)
                if (a !is CardAuth.Ok) {
                    _state.update { it.copy(problem = texts.words.of(a)) }
                    return@launch
                }
                WalletCalls(sessions.requireApi()).replace(card.id, CardBlob.seal(secret, a.cipher, a.keyId), secret, autoPay)
                if (autoPay) consent.autoPayAccepted(card.id)
                _state.update { it.copy(draft = ReenterDraft(card.id, autoPay)) }
                _events.trySend(WalletEvent.Confirm)
                _events.trySend(WalletEvent.Saved)
            } catch (e: ApiError) {
                // auto-pay needs Keyguard: the dialog, then again
                if (e.code == AutoPayGate.CODE_KEYGUARD_REQUIRED) _state.update { it.copy(needKeyguard = true) }
                else _state.update { it.copy(problem = if (e.isNetwork) texts.failedSend else e.detail) }
                _events.trySend(WalletEvent.Reject)
            } finally {
                secret.wipe()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() { _state.update { it.copy(draft = ReenterDraft(card.id, autoPay)) } }
}
