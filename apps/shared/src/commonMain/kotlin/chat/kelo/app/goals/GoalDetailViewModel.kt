package chat.kelo.app.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Goal
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.whileObserved
import chat.kelo.app.res.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

data class GoalDetailState(
    val workspace: GoalWorkspace? = null,
    val failed: Boolean = false,
    /** Steps being ticked right now (shown ticked at once) */
    val ticking: Map<Long, String> = emptyMap(),
    /** "Kelo에게 이어서 맡기기": null, or the line shown after it was pressed */
    val resumed: StringResource? = null,
    val resuming: Boolean = false,
)

/**
 * GET /api/goals/{id}, PATCH /api/goals/{id}/steps/{step}, POST /api/goals/{id}/work. While the state is collected (the page in view)
 * the workspace is fetched again at the pace [paceMs] says, from what the page knows of the goal ([goal], then the workspace's own).
 */
class GoalDetailViewModel(
    private val sessions: Sessions,
    private val goal: Goal,
    private val paceMs: (List<Goal>) -> Long? = GoalsRules::refreshMs,
) : ViewModel() {
    private val goalId = goal.id
    private val _state = MutableStateFlow(GoalDetailState())
    val state: StateFlow<GoalDetailState> = _state.asStateFlow()
    private val pace = MutableStateFlow(paceMs(listOf(goal)))

    init {
        _state.whileObserved(viewModelScope) {
            pace.collectLatest { ms -> if (ms != null) while (true) { delay(ms); load() } }
        }
    }

    fun load() {
        viewModelScope.launch {
            try {
                val w = sessions.requireApi().get<GoalWorkspace>("/api/goals/$goalId")
                _state.update { it.copy(workspace = w, failed = false, ticking = emptyMap()) }
                pace.value = paceMs(listOf(w.goal))
            } catch (e: ApiError) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    fun tick(step: GoalStep) {
        if (!GoalDetailRules.canTick(step)) return
        val next = GoalDetailRules.toggled(step)
        _state.update { it.copy(ticking = it.ticking + (step.id to next)) }
        viewModelScope.launch {
            try {
                sessions.requireApi().patch<JsonElement>("/api/goals/$goalId/steps/${step.id}", buildJsonObject { put("status", next) })
            } catch (e: ApiError) {
                _state.update { it.copy(failed = true) }
            }
            load()
        }
    }

    /** Has Kelo pick the goal up again in the chat; [onStarted] once the server has started it. */
    fun resume(onStarted: () -> Unit) {
        if (_state.value.resuming) return
        _state.update { it.copy(resuming = true) }
        viewModelScope.launch {
            try {
                sessions.requireApi().post<JsonElement>("/api/goals/$goalId/work")
                _state.update { it.copy(resumed = Res.string.goal_resume_started) }
                onStarted()
            } catch (e: ApiError) {
                _state.update { it.copy(resumed = if (e.status == 409) Res.string.goal_resume_no_model else Res.string.connect_failed) }
            } finally {
                _state.update { it.copy(resuming = false) }
            }
        }
    }
}
