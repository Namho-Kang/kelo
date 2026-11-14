package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsageByModelState(val days: Int = UsageLogic.DEFAULT_DAYS, val summary: UsageSummary? = null, val failed: Boolean = false)

/** The usage card's period and GET /api/usage/summary?days= for it; picking another period loads it (the last answer wins). */
class UsageByModelViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(UsageByModelState())
    val state: StateFlow<UsageByModelState> = _state.asStateFlow()
    private var loading: Job? = null

    init { load() }

    fun pick(days: Int) {
        if (days == _state.value.days || days !in UsageLogic.PERIODS) return
        _state.update { it.copy(days = days, summary = null, failed = false) }
        load()
    }

    fun load() {
        val days = _state.value.days
        loading?.cancel()
        loading = viewModelScope.launch {
            runCatching { sessions.requireApi().get<UsageSummary>("/api/usage/summary?days=$days") }
                .onSuccess { s -> _state.update { if (it.days == days) it.copy(summary = s, failed = false) else it } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { if (it.days == days) it.copy(failed = true) else it }
                }
        }
    }
}
