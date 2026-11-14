package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.chat.AttachmentTooLarge
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.FilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** What the agent page tells the owner for a moment (a toast), or that something worked (a tick of feedback). */
sealed interface AgentNotice {
    data object Done : AgentNotice
    data object AvatarFailed : AgentNotice
    data object AvatarTooLarge : AgentNotice
    data object ToneFailed : AgentNotice
    /** The server's own words for a picture that was refused. */
    data class Server(val text: String) : AgentNotice
}

data class AgentPageUiState(val working: Boolean = false, val savingTone: String? = null)

/** 마이 → 에이전트: the tone (read again on opening, saved on a pick) and the agent's picture (pick, upload, go back to the character). */
class AgentPageViewModel(
    private val sessions: Sessions,
    private val avatar: AgentAvatar,
    private val files: FilePicker,
) : ViewModel() {
    private val _state = MutableStateFlow(AgentPageUiState())
    val state: StateFlow<AgentPageUiState> = _state.asStateFlow()
    private val _notices = Channel<AgentNotice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    /** The agent may have changed the tone in the chat ("반말로 해 줘"): the server's profile as it is now. */
    fun reload(onProfile: (Profile) -> Unit) {
        // only the newest load counts: an older answer that arrives late is dropped, and so is one that started before the owner's
        // own change (a pick below ends any load under way)
        loading?.cancel()
        loading = viewModelScope.launch {
            val profile = runCatching { ToneRules.reload(sessions.requireApi()) }.getOrNull() ?: return@launch
            ensureActive()   // cancelled while the answer was on its way: it is not the newest any more
            onProfile(profile)
        }
    }

    private var loading: kotlinx.coroutines.Job? = null

    fun pickTone(profile: Profile, tone: String, onProfile: (Profile) -> Unit) {
        if (_state.value.savingTone != null || tone == ToneRules.selected(profile)) return
        _state.update { it.copy(savingTone = tone) }
        loading?.cancel()   // a load that started before this pick must not undo it
        viewModelScope.launch {
            try {
                onProfile(ToneRules.save(sessions.requireApi(), tone))
                _notices.trySend(AgentNotice.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.trySend(AgentNotice.ToneFailed)
            } finally {
                _state.update { it.copy(savingTone = null) }
            }
        }
    }

    /** Asks for a picture, scales it and uploads it as the agent's. */
    fun pickPicture() {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                val picked = files.pickImage(AgentAvatar.MAX_BYTES) ?: return@launch
                avatar.upload(sessions.requireApi(), picked.bytes, picked.type)
                _notices.trySend(AgentNotice.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentTooLarge) {
                _notices.trySend(AgentNotice.AvatarTooLarge)
            } catch (e: ApiError) {
                _notices.trySend(if (e.status == 413) AgentNotice.AvatarTooLarge else e.detail.takeIf { !e.isNetwork }?.let { AgentNotice.Server(it) } ?: AgentNotice.AvatarFailed)
            } catch (e: Exception) {
                _notices.trySend(AgentNotice.AvatarFailed)
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun removePicture() {
        viewModelScope.launch {
            try {
                avatar.remove(sessions.requireApi())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.trySend(AgentNotice.AvatarFailed)
            }
        }
    }
}
