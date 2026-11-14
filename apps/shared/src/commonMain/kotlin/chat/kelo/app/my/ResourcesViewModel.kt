package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CancellationException
import chat.kelo.app.core.whileObserved
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 마이 → Kelo 서버 자원: what this assistant may use on its computer. Reloaded every 10 seconds, only while the page is collected (in view and the app in front). */
class ResourcesViewModel(private val sessions: Sessions, private val pollMs: Long = 10_000) : ViewModel() {
    private val _state = MutableStateFlow(ResourcesLoad())
    val state: StateFlow<ResourcesLoad> = _state.asStateFlow()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    init {
        _state.whileObserved(viewModelScope) {
            while (true) {
                loadOnce()
                if (withTimeoutOrNull(pollMs) { kick.receive() } != null) _state.update { it.retrying() }
            }
        }
    }

    /** 다시 시도 */
    fun retry() {
        kick.trySend(Unit)
    }

    private suspend fun loadOnce() {
        try {
            val r = sessions.requireApi().get<Resources>("/api/resources")
            _state.update { it.loaded(r) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {   // offline, an error answer, or numbers this app cannot read
            _state.update { it.readFailed() }
        }
    }
}
