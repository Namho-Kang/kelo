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

/** What a connect form says when something did not work: the server's own words, or one of the app's. */
sealed interface ConnectError {
    data class Said(val text: String) : ConnectError

    /** No browser opened the service's own page. */
    data object NoBrowser : ConnectError

    /** The chosen key file could not be read, or is too large to be a key file. */
    data object FileUnreadable : ConnectError
}

/** The server's words for [this]. */
internal fun ApiError.said(): ConnectError = ConnectError.Said(detail)

/** Removes the stored connection [item]; a failure is ignored (the reload that follows shows what is still connected). */
internal suspend fun Sessions.disconnectQuietly(item: String) {
    runCatching { ConnectorCalls.disconnect(requireApi(), item) }
}

/** [looked]: the connector found by its id, for a panel that was not handed one (the chat's connect card). */
data class ConnectPanelState(val looked: Connector? = null)

/** Looks a connector up by id when the panel is shown without one: GET /api/connectors, again whenever something changed. */
class ConnectPanelViewModel(private val sessions: Sessions, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(ConnectPanelState())
    val state: StateFlow<ConnectPanelState> = _state.asStateFlow()

    fun lookUp() {
        viewModelScope.launch {
            runCatching { ConnectorCalls.list(sessions.requireApi()) }.onSuccess { l -> _state.update { it.copy(looked = l.find { c -> c.id == id }) } }
        }
    }
}
