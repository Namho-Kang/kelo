package chat.kelo.app.offers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [problem]: the offer whose start did not happen and why (BUSY or FAILED), shown on that card. */
data class OffersState(val items: List<Offer> = emptyList(), val starting: String? = null, val problem: Pair<String, OfferStart>? = null)

/** The offer cards: loaded on open (and by [load]), started, dismissed (the card goes at once). */
class OffersViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(OffersState())
    val state: StateFlow<OffersState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            runCatching { OfferCalls.list(sessions.requireApi()) }.onSuccess { list -> _state.update { it.copy(items = list) } }
        }
    }

    /** Starts [o]; [onStarted] gets the new turn's id (the caller opens the chat and follows it). */
    fun start(o: Offer, onStarted: (String) -> Unit) {
        if (_state.value.starting != null) return
        _state.update { it.copy(starting = o.id, problem = null) }
        viewModelScope.launch {
            val r = runCatching { OfferCalls.start(sessions.requireApi(), o.id) }.getOrDefault(OfferStart.Failed)
            if (r is OfferStart.Started) {
                _state.update { s -> s.copy(items = s.items.filter { it.id != o.id }, starting = null) }
                onStarted(r.turnId)
            } else {
                _state.update { it.copy(starting = null, problem = o.id to r) }
            }
        }
    }

    fun dismiss(o: Offer, reason: OfferReason) {
        _state.update { s -> s.copy(items = s.items.filter { it.id != o.id }, problem = null) }
        viewModelScope.launch { runCatching { OfferCalls.dismiss(sessions.requireApi(), o.id, reason) } }
    }
}
