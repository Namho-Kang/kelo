package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.chat.vault.VaultCalls
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.ExternalBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the vault keeps a login for the site. */
sealed interface LoginKept {
    data object Loading : LoginKept
    data object None : LoginKept

    /** [usernameHint]: the user name the vault shows for it, null when it shows none. */
    data class Some(val usernameHint: String?) : LoginKept
}

/** [asking]: the vault item the secure card is filling in (the login being typed). */
data class BrowserConnectState(val kept: LoginKept = LoginKept.Loading, val asking: String? = null, val error: ConnectError? = null)

/** A site the agent uses with its own browser. A login, if the site needs one, is kept in the vault and typed by the server. */
class BrowserConnectViewModel(
    private val sessions: Sessions,
    private val browser: ExternalBrowser,
    private val site: String,
    private val name: String,
) : ViewModel() {
    private val host = ConnectorRules.host(site)
    private val _state = MutableStateFlow(BrowserConnectState())
    val state: StateFlow<BrowserConnectState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val kept = try {
                val item = VaultCalls.info(sessions.requireApi()).items.find { i -> i.kind == "login" && host in i.hosts }
                if (item != null) LoginKept.Some(item.usernameHint) else LoginKept.None
            } catch (_: ApiError) {
                LoginKept.None
            }
            _state.update { it.copy(kept = kept) }
        }
    }

    fun openSite() {
        if (!browser.open(site)) _state.update { it.copy(error = ConnectError.NoBrowser) }
    }

    /** Asks the vault for a login for the site; the secure card then takes the typing. */
    fun askForLogin() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                val item = ConnectorCalls.addLogin(sessions.requireApi(), site, name)
                _state.update { it.copy(asking = item) }
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.said()) }
            }
        }
    }
}
