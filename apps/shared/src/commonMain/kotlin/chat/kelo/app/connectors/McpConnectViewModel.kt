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

/** [busy]: the sign-in page is being opened or is open in the browser (the list reload shows whether it worked). */
data class McpConnectState(val busy: Boolean = false, val error: ConnectError? = null)

/** A hosted MCP service: sign in on its own page in the browser; the service sends the user back to the server. */
class McpConnectViewModel(private val sessions: Sessions, private val browser: ExternalBrowser, private val connectorId: String) : ViewModel() {
    private val _state = MutableStateFlow(McpConnectState())
    val state: StateFlow<McpConnectState> = _state.asStateFlow()

    fun signIn() {
        _state.value = McpConnectState(busy = true)
        viewModelScope.launch {
            try {
                val url = ConnectorCalls.mcpStart(sessions.requireApi(), connectorId)
                if (!browser.open(url)) _state.value = McpConnectState(error = ConnectError.NoBrowser)
            } catch (e: ApiError) {
                _state.value = McpConnectState(error = e.said())
            }
        }
    }

    /** Back from the browser: the button is ready again. */
    fun returned() = _state.update { it.copy(busy = false) }

    fun disconnect(item: String, onChanged: () -> Unit) {
        viewModelScope.launch {
            sessions.disconnectQuietly(item)
            onChanged()
        }
    }
}
