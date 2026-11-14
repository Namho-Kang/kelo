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

/** [accounts]: the calendars added here (null until the first answer). */
data class CalDavConnectState(val accounts: List<CalDavAccount>? = null, val busy: Boolean = false, val error: ConnectError? = null)

/**
 * A calendar from any provider that offers CalDAV (Fastmail, Zoho, Nextcloud ...): the provider's CalDAV address, user name and an app
 * password. Read only: the morning briefing and heads-ups read it; Kelo never changes it.
 */
class CalDavConnectViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(CalDavConnectState())
    val state: StateFlow<CalDavConnectState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        runCatching { CalDavCalls.accounts(sessions.requireApi()) }.onSuccess { a -> _state.update { it.copy(accounts = a) } }
    }

    /** Back to the screen: the accounts are read again. */
    fun returned() {
        _state.update { it.copy(busy = false) }
        load()
    }

    /** The server checks the address, user name and app password; [onAdded] once it saved them (the form then empties). */
    fun add(url: String, user: String, password: String, onAdded: () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                CalDavCalls.add(sessions.requireApi(), url.trim(), user.trim(), password)
                onAdded()
                reload()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.said()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun remove(account: CalDavAccount, onChanged: () -> Unit) {
        viewModelScope.launch {
            sessions.disconnectQuietly(account.name)
            reload()
            onChanged()
        }
    }
}
