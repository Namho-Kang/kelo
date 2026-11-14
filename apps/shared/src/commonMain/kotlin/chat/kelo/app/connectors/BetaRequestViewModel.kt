package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [beta]: where the request stands on the server (null until read, or when it cannot be read). [sending]/[error]: the request form. */
data class BetaRequestState(val beta: GoogleBetaState? = null, val sending: Boolean = false, val error: String = "")

/**
 * Asking to be let in early to Gmail / Google Calendar's Google sign-in ([BetaRules]). The request (the Google address the person will
 * connect) goes to this server, which forwards it to the Kelo developer and keeps its state, so "Requested" survives reopening.
 */
class BetaRequestViewModel(private val sessions: Sessions, private val appVersion: String) : ViewModel() {
    private val _state = MutableStateFlow(BetaRequestState())
    val state: StateFlow<BetaRequestState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val b = runCatching { ConnectorCalls.googleBeta(sessions.requireApi()) }.getOrNull()
            _state.update { it.copy(beta = b) }
        }
    }

    fun clearError() = _state.update { it.copy(error = "") }

    /** Sends the request for [product]; [onSent] once the server has it. */
    fun send(email: String, product: String, onSent: () -> Unit) {
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, error = "") }
        viewModelScope.launch {
            try {
                val b = ConnectorCalls.requestGoogleBeta(sessions.requireApi(), email, product, appVersion)
                _state.update { it.copy(beta = b) }
                onSent()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.detail) }
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }
}
