package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [hours]: the learned message hours waiting for an answer; [done]: the choice just made (accepted or kept), shown for a moment. */
data class HoursUiState(val hours: HoursSuggestion? = null, val done: Boolean? = null)

/** The learned message hours the owner is asked about in 마이 → 기록 (GET /api/proactive "hours_suggestion"): look, accept, keep. */
class HoursSuggestionViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(HoursUiState())
    val state: StateFlow<HoursUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val open = ContactHoursRules.open(runCatching { sessions.requireApi().get<ContactHours>("/api/proactive") }.getOrNull())
            _state.update { it.copy(hours = open) }
        }
    }

    /** [onFailed] hears of a refusal that is not "already answered elsewhere" (404: the next visit shows what is open now). */
    fun answer(s: HoursSuggestion, accept: Boolean, onFailed: (ApiError) -> Unit) {
        _state.update { it.copy(done = accept) }
        viewModelScope.launch {
            val api = runCatching { sessions.requireApi() }.getOrNull() ?: return@launch
            try {
                api.post<ContactHours>(if (accept) ContactHoursRules.acceptPath(s.id) else ContactHoursRules.keepPath(s.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status != 404) { _state.update { it.copy(done = null) }; onFailed(e) }
            }
            if (_state.value.done != null) {
                delay(ContactHoursRules.DONE_MILLIS)
                _state.update { HoursUiState() }
            }
        }
    }
}
