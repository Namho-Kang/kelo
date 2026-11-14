package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.whileObserved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the server's update looks like now. [status] null = show nothing (not the owner, an older server, or not asked yet);
 * [waiting] / [gaveUp] describe the following of a running update (the restarting server may not answer for a while).
 * [gone]: the answer to what the owner did on the page was "nothing to show" (this sign-in may no longer see it): the page closes.
 */
data class ServerUpdateUiState(
    val status: ServerUpdateStatus? = null,
    val waiting: Boolean = false,
    val gaveUp: Boolean = false,
    val busy: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null,
    val gone: Boolean = false,
)

/**
 * 마이 → 서버 업데이트: the one place the status of the server's update lives, so the card on My home and the page agree. Following a
 * running update (poll until it ends, through the server's restart) starts by itself whenever a status says it runs.
 */
class ServerUpdateViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(ServerUpdateUiState())
    val state: StateFlow<ServerUpdateUiState> = _state.asStateFlow()
    private var following: Job? = null

    /** From My home's refresh: a running update is already being followed, so its status is not replaced by a one-off answer. */
    suspend fun loadForHome() {
        val api = runCatching { sessions.requireApi() }.getOrNull() ?: return
        val s = ServerUpdateRules.load(api)
        if (!ServerUpdateRules.running(_state.value.status)) set(s)
    }

    /** The page opened: fresh status (the card may have been drawn from an older answer), unless an update is being followed. */
    fun refreshOnVisit() {
        if (ServerUpdateRules.running(_state.value.status)) return
        viewModelScope.launch {
            runCatching { ServerUpdateRules.load(sessions.requireApi()) }.onSuccess { answered(it) }
        }
    }

    /** The page closed itself because of [ServerUpdateUiState.gone]. */
    fun goneHandled() = _state.update { it.copy(gone = false) }

    fun update() = act { ServerUpdateRules.start(sessions.requireApi(), rollback = false) }
    fun goBack() = act { ServerUpdateRules.start(sessions.requireApi(), rollback = true) }
    fun setAuto(on: Boolean) = act { ServerUpdateRules.setAuto(sessions.requireApi(), on) }

    fun checkNow() {
        _state.update { it.copy(checking = true) }
        act { try { ServerUpdateRules.check(sessions.requireApi()) } finally { _state.update { it.copy(checking = false) } } }
    }

    private fun act(block: suspend () -> ServerUpdateStatus?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                answered(block())
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.detail) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun answered(s: ServerUpdateStatus?) {
        set(s)
        _state.update { it.copy(gone = s == null) }
    }

    private fun set(s: ServerUpdateStatus?) {
        _state.update { it.copy(status = s, waiting = false, gaveUp = false) }
        if (ServerUpdateRules.running(s)) follow(s!!) else following?.cancel()
    }

    private fun follow(start: ServerUpdateStatus) {
        if (following?.isActive == true) return
        // followed only while the status is collected (the app in front); coming back follows on from the latest status
        following = _state.whileObserved(viewModelScope) {
            val api = runCatching { sessions.requireApi() }.getOrNull() ?: return@whileObserved
            ServerUpdateRules.follow(api, _state.value.status ?: start) { f -> _state.update { it.copy(status = f.status, waiting = f.waiting, gaveUp = f.gaveUp) } }
            following?.cancel()   // done: the next update starts a new follow
        }
    }
}
