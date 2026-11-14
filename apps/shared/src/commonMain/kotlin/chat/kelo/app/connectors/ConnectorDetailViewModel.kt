package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [granted]: the permission a connected code-connected service was given; null when that choice is not known. */
data class ConnectorDetailState(
    val granted: OAuthScope? = null,
    val permissions: ConnectorPermissions? = null,
    val permsMissing: Boolean = false,
    val permsBusy: Boolean = false,
)

/** What one connector's own page reads and does besides its connect panel. */
class ConnectorDetailViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(ConnectorDetailState())
    val state: StateFlow<ConnectorDetailState> = _state.asStateFlow()

    /** GET /api/oauth/providers: the provider's `granted_scope` among its `scopes`. */
    fun loadGranted(provider: String) {
        viewModelScope.launch {
            runCatching { ConnectorCalls.oauthProviders(sessions.requireApi()) }
                .onSuccess { l -> _state.update { it.copy(granted = ConnectorRules.grantedScope(l.firstOrNull { p -> p.id == provider })) } }
        }
    }

    /** 연결 끊기 for a connection that needs doing again; [onChanged] afterwards (the list is read again). */
    fun disconnect(item: String, onChanged: () -> Unit) {
        viewModelScope.launch {
            sessions.disconnectQuietly(item)
            onChanged()
        }
    }

    /** GET the connector's permissions; 404 = it has no settings (the section stays away), other errors show nothing. */
    fun loadPermissions(id: String) {
        viewModelScope.launch {
            runCatching { ConnectorCalls.permissions(sessions.requireApi(), id) }
                .onSuccess { p -> _state.update { it.copy(permissions = p, permsMissing = false) } }
                .onFailure { e -> if (e is ApiError && e.status == 404) _state.update { it.copy(permissions = null, permsMissing = true) } }
        }
    }

    fun setKind(id: String, kind: String, mode: String) = put(id, PermissionRules.kindBody(kind, mode))

    fun setAction(id: String, action: PermissionAction, mode: String) = put(id, PermissionRules.actionBody(action, mode))

    fun resetPermissions(id: String) = run(id) { ConnectorCalls.resetPermissions(sessions.requireApi(), id) }

    private fun put(id: String, body: JsonObject) = run(id) { ConnectorCalls.putPermissions(sessions.requireApi(), id, body) }

    private fun run(id: String, call: suspend () -> ConnectorPermissions) {
        _state.update { it.copy(permsBusy = true) }
        viewModelScope.launch {
            runCatching { call() }
                .onSuccess { p -> _state.update { it.copy(permissions = p, permsBusy = false) } }
                .onFailure { _state.update { it.copy(permsBusy = false) } }
        }
    }
}
