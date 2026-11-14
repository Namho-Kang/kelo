package chat.kelo.app.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the card pages show. Behind the lock only [phase], [kept] and nothing else exist: the cards, notices and key id are loaded when
 * the pages open and dropped when they lock again ("the data lives only as long as the pages are open").
 */
data class WalletUiState(
    val phase: LockPhase = LockPhase.LOCKED,
    /** Something typed is waiting behind the lock (the locked page says so). */
    val kept: Boolean = false,
    val info: WalletInfo? = null,
    /** This device's card key id (null: none, or the device holds no cards). */
    val keyId: String? = null,
    /** Automatic payments the owner has not looked at yet, shown at the top of the list once. */
    val notices: List<WalletPayment> = emptyList(),
    val failed: String = "",
    val view: WalletView = WalletView.List,
) {
    val open: Boolean get() = phase == LockPhase.OPEN
}

/**
 * 마이 → 결제 카드: the lock (owner's check, two minutes idle, locks in the background), the page that was open and what was typed
 * ([WalletPlaces]), the wallet list's data and the step between the pages. Each page's own work lives in its own view model.
 * [isResumed] tells whether the window is in front (the check comes up by itself only then).
 */
class WalletViewModel(
    private val sessions: Sessions, private val cardKeys: CardKeys, private val cardScanner: CardScanner?,
    private val places: WalletPlaces, private val isResumed: () -> Boolean,
) : ViewModel() {
    private val lock = WalletLock()
    private val flow = UnlockFlow(cardKeys.credentialLeavesApp)
    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var idle: Job? = null
    private var loading: Job? = null

    private fun now() = places.nowMs()

    private fun publish() {
        val phase = flow.phase
        val wasOpen = _state.value.open
        _state.update { it.copy(phase = phase, kept = places.hasTyping()) }
        if (phase == LockPhase.OPEN && !wasOpen) opened()
        if (phase != LockPhase.OPEN && wasOpen) closed()
    }

    private fun askIfDue() {
        if (isResumed() && flow.resumed()) { publish(); _events.trySend(WalletEvent.AskCheck) }
    }

    /** The window came to the front. */
    fun onResume() = askIfDue()

    /** The window left the screen. */
    fun onStop() {
        // Android 9 and older: a card check's screen-lock page is what sent the app to the background, not the owner leaving
        if (cardKeys.promptActive() && cardKeys.credentialLeavesApp && flow.isOpen) return
        // Google Pay's card scan screen is up over the add form; the two-minute limit still applies
        if (cardScanner?.active() == true && flow.isOpen) return
        lock.background(); flow.backgrounded(); publish()
    }

    /** The unlock button. */
    fun tap() { if (flow.tap()) { publish(); _events.trySend(WalletEvent.AskCheck) } }

    /** The owner's check passed (or the device has no lock to ask: it never opens then, see [noLock]). */
    fun checkPassed() { lock.unlock(now()); flow.opened(); publish() }
    fun noLock() { flow.noLock(); publish() }
    fun checkNotPassed() { flow.notOpened(); publish() }

    /** Any touch on an open page starts the two minutes (and the ten minutes of a kept place) again. */
    fun touch() {
        if (lock.isOpen(now())) { lock.touch(now()); places.touch() }
    }

    /** The pages are left. */
    fun leave() { places.clear() }

    private fun opened() {
        _state.update { it.copy(view = places.view()) }
        idle?.cancel()
        idle = viewModelScope.launch {
            while (flow.isOpen) {
                delay(1000)
                if (!lock.isOpen(now())) { flow.timedOut(); publish(); askIfDue() }
            }
        }
        load(first = true)
    }

    private fun closed() {
        idle?.cancel()
        loading?.cancel()
        _state.update { it.copy(info = null, keyId = null, notices = emptyList(), failed = "") }
    }

    /** Loads the list's data again (after a change); [first]: also the automatic payments not seen yet. */
    fun load(first: Boolean = false) {
        loading = viewModelScope.launch {
            try {
                val calls = WalletCalls(sessions.requireApi())
                // an older server's wallet has no addresses in it: they are asked for apart (none at all from a server without them)
                val info = calls.info().let { w ->
                    if (w.shipping != null) w else w.copy(shipping = runCatching { ShippingCalls(sessions.requireApi()).list() }.getOrNull())
                }
                _state.update { it.copy(info = info, keyId = cardKeys.keyId(), failed = "") }
            } catch (e: ApiError) {
                _state.update { it.copy(failed = e.detail) }
            }
            if (first) showNewPayments()
        }
    }

    /** Automatic payments the owner has not looked at: shown at the top of the list once, then marked as seen. */
    private suspend fun showNewPayments() {
        val calls = runCatching { WalletCalls(sessions.requireApi()) }.getOrNull() ?: return
        runCatching { calls.notifications() }.onSuccess { n ->
            _state.update { it.copy(notices = n.items.filter { p -> !p.seen }) }
            val top = n.items.maxOfOrNull { it.id }
            if (n.unseen > 0 && top != null) runCatching { calls.markSeen(top) }.onSuccess { _events.trySend(WalletEvent.Seen) }
        }
    }

    /** Takes back a schedule's permission to buy on its own. */
    fun revokeGrant(g: chat.kelo.app.agent.ScheduleGrant) {
        viewModelScope.launch {
            runCatching { chat.kelo.app.agent.ScheduleGrantCalls.revoke(sessions.requireApi(), g) }.onSuccess { load() }
        }
    }

    /** Opens another page (what the owner did is kept as the place for the lock). */
    fun go(v: WalletView) {
        _state.update { it.copy(view = v) }
        places.go(v)
    }

    /** One step back: a re-enter page goes to its card, everything else to the list. */
    fun back() = go(backOf(_state.value.view) ?: WalletView.List)

    override fun onCleared() {
        idle?.cancel()
        loading?.cancel()
    }

    companion object {
        /** The page one step back from [v], or null at the list. */
        fun backOf(v: WalletView): WalletView? = when (v) {
            WalletView.List -> null
            is WalletView.Reenter -> WalletView.Card(v.id)
            else -> WalletView.List
        }
    }
}
