package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.followup.FollowupCalls
import chat.kelo.app.followup.FollowupSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What 마이 → 알림 shows: each block is null while loading and has a "missing" flag for a server that does not have it (404). */
data class NotifyUiState(
    val proactive: ProactiveSettings? = null,
    val proactiveMissing: Boolean = false,
    val checklist: ChecklistSettings? = null,
    val checklistMissing: Boolean = false,
    val followups: FollowupSettings? = null,
    val followupsMissing: Boolean = false,
    val error: String = "",
)

/** The heads-up, hours, checklist and follow-up settings of the notification page (the briefing has its own: [BriefingSettingsViewModel]). */
class NotifySettingsViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(NotifyUiState())
    val state: StateFlow<NotifyUiState> = _state.asStateFlow()
    private val _notices = Channel<String>(Channel.BUFFERED)
    /** error lines to show as a toast */
    val notices: Flow<String> = _notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            val api = runCatching { sessions.requireApi() }.getOrNull() ?: return@launch
            try { _state.update { it.copy(proactive = api.get("/api/proactive")) } } catch (e: ApiError) {
                if (e.status == 404) _state.update { it.copy(proactiveMissing = true) } else _state.update { it.copy(error = e.detail) }
            }
            try { _state.update { it.copy(checklist = api.get("/api/checklist")) } } catch (e: ApiError) {
                if (e.status == 404) _state.update { it.copy(checklistMissing = true) } else _state.update { it.copy(error = e.detail) }
            }
            try {
                val f = FollowupCalls(api).settings()
                _state.update { it.copy(followups = f, followupsMissing = f == null) }
            } catch (e: ApiError) { _state.update { it.copy(error = e.detail) } }
        }
    }

    private fun failed(e: ApiError) { _notices.trySend(e.detail) }

    fun putFollowups(body: JsonObject) {
        viewModelScope.launch {
            try { _state.update { it.copy(followups = FollowupCalls(sessions.requireApi()).putSettings(body)) } } catch (e: ApiError) { failed(e) }
        }
    }

    fun putProactive(body: JsonObject) {
        viewModelScope.launch {
            try { _state.update { it.copy(proactive = sessions.requireApi().put("/api/proactive", body)) } } catch (e: ApiError) { failed(e) }
        }
    }

    fun putChecklist(body: JsonObject) {
        viewModelScope.launch {
            try { _state.update { it.copy(checklist = sessions.requireApi().put("/api/checklist", body)) } } catch (e: ApiError) { failed(e) }
        }
    }

    fun postHours(path: String) {
        viewModelScope.launch {
            try { _state.update { it.copy(proactive = sessions.requireApi().post(path)) } } catch (e: ApiError) { failed(e) }
        }
    }
}
