package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.whileObserved
import chat.kelo.app.platform.ExternalBrowser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [provider]: the service (null until read). [flow]: the code attempt in progress or just ended. [secondsLeft]: until the code stops
 * working. [scopeId]: the permission chosen (the connection's own when connected, else the first).
 */
data class DeviceConnectState(
    val provider: OAuthProvider? = null,
    val flow: OAuthFlow? = null,
    val secondsLeft: Long = 0,
    val scopeId: String = "",
    val busy: Boolean = false,
    val error: ConnectError? = null,
    val copied: Boolean = false,
)

/**
 * Connects a service (GitHub, Microsoft) by a short code typed on its own page: the app shows the code, the user types it there, and
 * the token goes straight into the vault. When the server offers a web sign-in (GitHub with an OAuth app) that opens in the browser
 * instead. The attempt is followed with short requests, only while the state is collected (the page in view); it goes on at the server
 * even if the page is left.
 */
class DeviceConnectViewModel(
    private val sessions: Sessions,
    private val browser: ExternalBrowser,
    private val id: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1000,
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceConnectState())
    val state: StateFlow<DeviceConnectState> = _state.asStateFlow()
    private var deadline = 0L
    private var polling: Job? = null
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the code attempt ended with the service connected (the list shows it). */
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val x = ConnectorCalls.oauthProviders(sessions.requireApi()).find { it.id == id }
            _state.update { s ->
                // a connection shows the choice it was made with; otherwise the first (the default) until the user picks one
                val pick = if (s.scopeId.isEmpty() || (x?.connected == true && x.grantedScope.isNotEmpty() && s.provider?.grantedScope != x.grantedScope)) {
                    x?.grantedScope?.takeIf { it.isNotEmpty() } ?: x?.scopes?.firstOrNull()?.id.orEmpty()
                } else s.scopeId
                s.copy(provider = x, scopeId = pick)
            }
        } catch (_: ApiError) {
        }
    }

    fun selectScope(scopeId: String) = _state.update { it.copy(scopeId = scopeId) }

    fun copied() = _state.update { it.copy(copied = true) }

    private fun act(block: suspend () -> Unit) {
        _state.update { it.copy(error = null, busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.said()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** GitHub's web sign-in: the service's page in the browser. */
    fun signInOnWeb() = act {
        val url = ConnectorCalls.githubWebStart(sessions.requireApi(), _state.value.scopeId)
        if (!browser.open(url)) _state.update { it.copy(error = ConnectError.NoBrowser) }
    }

    /** Asks the server for a code and starts following the attempt. */
    fun startCode() = act {
        val started = ConnectorCalls.startFlow(sessions.requireApi(), id, _state.value.scopeId)
        deadline = now() + started.expiresIn * 1000L
        _state.update { it.copy(flow = started, secondsLeft = ConnectorRules.secondsLeft(deadline, now()), copied = false) }
        follow(started.id)
    }

    /** The page where the code is typed, in the browser. */
    fun openVerification() {
        val uri = _state.value.flow?.verificationUri ?: return
        if (!browser.open(uri)) _state.update { it.copy(error = ConnectError.NoBrowser) }
    }

    /** The service's own settings page for the connection. */
    fun openSettings() {
        val url = _state.value.provider?.settingsUrl.orEmpty()
        if (url.isNotBlank()) browser.open(url)
    }

    fun cancelCode() {
        val f = _state.value.flow ?: return
        polling?.cancel()
        viewModelScope.launch {
            runCatching { ConnectorCalls.cancelFlow(sessions.requireApi(), f.id) }
            _state.update { it.copy(flow = null) }
        }
    }

    /** Saves the OAuth app's client id the service asked to register; [onSaved] once the server has it. */
    fun saveClientId(clientId: String, onSaved: () -> Unit) = act {
        val p = ConnectorCalls.saveClientId(sessions.requireApi(), id, clientId.trim())
        _state.update { it.copy(provider = p) }
        onSaved()
    }

    fun disconnect(onChanged: () -> Unit) {
        val item = _state.value.provider?.item ?: return
        viewModelScope.launch {
            sessions.disconnectQuietly(item)
            polling?.cancel()
            _state.update { it.copy(flow = null) }
            reload()
            onChanged()
        }
    }

    /** The clock ticks every [tickMs], the server is asked every third tick, until the attempt ends. Only while somebody looks. */
    private fun follow(flowId: String) {
        polling?.cancel()
        polling = _state.whileObserved(viewModelScope) {
            if (_state.value.flow?.pending != true) return@whileObserved   // it ended while nobody looked
            var ticks = 0
            while (true) {
                delay(tickMs)
                _state.update { it.copy(secondsLeft = ConnectorRules.secondsLeft(deadline, now())) }
                if (++ticks % 3 != 0) continue
                try {
                    val f = ConnectorCalls.flow(sessions.requireApi(), flowId)
                    _state.update { it.copy(flow = f) }
                    if (!f.pending) {
                        reload()
                        if (f.status == "connected") _connected.tryEmit(Unit)
                        break
                    }
                } catch (e: ApiError) {
                    if (e.status == 404) { _state.update { it.copy(flow = null) }; break }
                }
            }
        }
    }
}
