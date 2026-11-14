package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.ExternalBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [google]: the Google Calendar accounts and whether sign-in is open (null until read); [failed]: it could not be read. */
data class GoogleConnectState(
    val google: GoogleCalendarState? = null,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val error: ConnectError? = null,
)

/** Google Calendar's Google sign-in on Google's own page, and each connected account with its disconnect. */
class GoogleConnectViewModel(private val sessions: Sessions, private val browser: ExternalBrowser) : ViewModel() {
    private val _state = MutableStateFlow(GoogleConnectState())
    val state: StateFlow<GoogleConnectState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val g = ConnectorCalls.googleCalendar(sessions.requireApi())
            _state.update { it.copy(google = g, failed = false) }
        } catch (e: CancellationException) {
            throw e   // left the screen: not a failure
        } catch (_: Exception) {
            _state.update { it.copy(failed = true) }
        }
    }

    /** Back from Google's page: the button is ready again and the accounts are read again. */
    fun returned() {
        _state.update { it.copy(busy = false) }
        load()
    }

    fun signIn() {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val url = ConnectorCalls.googleWebStart(sessions.requireApi(), "calendar")
                if (!browser.open(url)) _state.update { it.copy(busy = false, error = ConnectError.NoBrowser) }
            } catch (e: ApiError) {
                _state.update { it.copy(busy = false, error = e.said()) }
            }
        }
    }

    fun remove(account: GoogleCalendarAccount, onChanged: () -> Unit) {
        viewModelScope.launch {
            sessions.disconnectQuietly(account.name)
            reload()
            onChanged()
        }
    }
}
