package chat.kelo.app.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LimitsUiState(
    val data: WalletLimits? = null,
    val paused: Boolean = false,
    /** Currencies the owner added on this page that the server does not know yet. */
    val added: Set<String> = emptySet(),
    val editing: String? = null,
    val msg: String = "",
)

/**
 * 자동결제 한도: the global pause, and per currency the limit for one payment and for a month with what was paid automatically this
 * month. Each can go back to the default; another currency can be added.
 */
class LimitsViewModel(private val sessions: Sessions, paused: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(LimitsUiState(paused = paused))
    val state: StateFlow<LimitsUiState> = _state.asStateFlow()
    private val _events = Channel<WalletEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        try {
            val d = WalletCalls(sessions.requireApi()).limits()
            _state.update { it.copy(data = d) }
        } catch (e: ApiError) {
            _state.update { it.copy(msg = e.detail, data = it.data ?: WalletLimits()) }
        }
    }

    private fun ok() = _events.trySend(WalletEvent.Confirm)
    private fun bad(e: ApiError, haptic: Boolean = true) {
        _state.update { it.copy(msg = e.detail) }
        if (haptic) _events.trySend(WalletEvent.Reject)
    }

    fun setPaused(on: Boolean) {
        _state.update { it.copy(msg = "") }
        viewModelScope.launch {
            try {
                val p = WalletCalls(sessions.requireApi()).pause(on).paused
                _state.update { it.copy(paused = p) }
                ok(); _events.trySend(WalletEvent.Changed)
            } catch (e: ApiError) { bad(e) }
        }
    }

    fun toggleEdit(currency: String) = _state.update { it.copy(editing = if (it.editing == currency) null else currency) }
    fun stopEditing() = _state.update { it.copy(editing = null) }

    /** A currency the owner is adding: its card opens in edit mode. */
    fun addCurrency(code: String) = _state.update { it.copy(added = it.added + code, editing = code) }

    /** Saves the limits of [currency]; [newToServer]: the server did not know the currency yet (it counts as added from now on). */
    fun save(currency: String, perPayment: Double, monthly: Double, newToServer: Boolean = false) {
        _state.update { it.copy(msg = "") }
        viewModelScope.launch {
            try {
                WalletCalls(sessions.requireApi()).setLimit(currency, perPayment, monthly)
                _state.update { it.copy(editing = null, added = if (newToServer) it.added + currency else it.added) }
                ok(); load()
            } catch (e: ApiError) { bad(e) }
        }
    }

    fun reset(currency: String) {
        _state.update { it.copy(msg = "") }
        viewModelScope.launch {
            try {
                WalletCalls(sessions.requireApi()).resetLimit(currency)
                _state.update { it.copy(editing = null, added = it.added - currency) }
                ok(); load()
            } catch (e: ApiError) { bad(e, haptic = false) }
        }
    }
}

/** 결제 내역: every payment that went ahead, newest first, automatic or allowed on the phone. [items] is null while loading. */
data class PaymentsUiState(val items: List<WalletPayment>? = null, val msg: String = "")

class PaymentsViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(PaymentsUiState())
    val state: StateFlow<PaymentsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _state.value = PaymentsUiState(WalletCalls(sessions.requireApi()).payments().items)
            } catch (e: ApiError) {
                _state.value = PaymentsUiState(emptyList(), e.detail)
            }
        }
    }
}
