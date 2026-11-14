package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.ExternalBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [data]: the mail services and the mailboxes connected here (null until the first answer). [busy]: checking a mailbox, or the Google page is open. */
data class MailConnectState(val data: MailProviders? = null, val busy: Boolean = false, val error: ConnectError? = null)

/**
 * A mailbox with an app password (or Google sign-in for Gmail). The server signs in once to check it; only a mailbox that lets it in
 * is saved.
 */
class MailConnectViewModel(private val sessions: Sessions, private val browser: ExternalBrowser) : ViewModel() {
    private val _state = MutableStateFlow(MailConnectState())
    val state: StateFlow<MailConnectState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        runCatching { ConnectorCalls.mailProviders(sessions.requireApi()) }.onSuccess { d -> _state.update { it.copy(data = d) } }
    }

    /** Back from the browser (or the screen shown again): the mailboxes are read again and the button is ready. */
    fun returned() {
        _state.update { it.copy(busy = false) }
        load()
    }

    /** Gmail's Google sign-in on Google's own page. */
    fun signInWithGoogle() {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val url = ConnectorCalls.googleWebStart(sessions.requireApi())
                if (!browser.open(url)) _state.update { it.copy(busy = false, error = ConnectError.NoBrowser) }
            } catch (e: ApiError) {
                _state.update { it.copy(busy = false, error = e.said()) }
            }
        }
    }

    /** Checks and saves a mailbox; [onAdded] once the server has it (the form then empties). */
    fun add(provider: String, address: String, password: String, onAdded: () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                ConnectorCalls.addMail(sessions.requireApi(), provider, address.trim(), password)
                onAdded()
                reload()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.said()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun remove(account: MailAccount, onChanged: () -> Unit) {
        viewModelScope.launch {
            sessions.disconnectQuietly(account.name)
            reload()
            onChanged()
        }
    }
}
