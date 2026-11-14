package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What saving the OAuth app answered. */
sealed interface OperatorMessage {
    data object Saved : OperatorMessage
    data class Said(val text: String) : OperatorMessage
}

/**
 * [owner]: null = not known yet; false = this app is signed in as a paired phone, which cannot change operator settings (403): nothing
 * is shown. [app]: the OAuth app as the server has it. [switchError]: why the "open to everyone" switch did not change.
 */
data class OperatorSettingsState(
    val owner: Boolean? = null,
    val app: OAuthApp? = null,
    val message: OperatorMessage? = null,
    val switchBusy: Boolean = false,
    val switchError: String = "",
)

/**
 * For the person who runs Kelo for others, the OAuth app (GitHub or Google) everyone signs in with. The secret can be replaced, never
 * read back.
 */
class OperatorSettingsViewModel(private val sessions: Sessions, private val provider: String) : ViewModel() {
    private val _state = MutableStateFlow(OperatorSettingsState())
    val state: StateFlow<OperatorSettingsState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val a = ConnectorCalls.oauthApp(sessions.requireApi(), provider)
                _state.update { it.copy(app = a, owner = true) }
            } catch (e: ApiError) {
                _state.update { it.copy(owner = if (e.status == 403) false else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Saves the client id and, when typed, the secret; [onSaved] once the server has them (the secret field then empties). */
    fun save(clientId: String, secret: String, onSaved: () -> Unit) {
        _state.update { it.copy(message = null) }
        viewModelScope.launch {
            try {
                val saved = ConnectorCalls.saveOauthApp(sessions.requireApi(), provider, clientId.trim(), secret.trim())
                _state.update { it.copy(app = saved, message = OperatorMessage.Saved) }
                onSaved()
            } catch (e: ApiError) {
                _state.update { it.copy(message = OperatorMessage.Said(e.detail)) }
            }
        }
    }

    /** Google: "open Google sign-in to everyone" (after Google's verification). */
    fun setOpenToAll(on: Boolean) {
        _state.update { it.copy(switchBusy = true, switchError = "") }
        viewModelScope.launch {
            try {
                val app = ConnectorCalls.setGoogleOpen(sessions.requireApi(), on)
                _state.update { it.copy(app = app) }
            } catch (e: ApiError) {
                _state.update { it.copy(switchError = e.detail) }
            } finally {
                _state.update { it.copy(switchBusy = false) }
            }
        }
    }
}
