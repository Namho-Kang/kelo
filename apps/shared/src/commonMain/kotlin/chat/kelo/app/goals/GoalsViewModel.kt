package chat.kelo.app.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.Caches
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Goal
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.whileObserved
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [removing]: goals swiped away whose deletion can still be undone; they are hidden here and deleted on the server only when the
 * undo offer is gone.
 */
data class GoalsState(
    val goals: List<Goal> = emptyList(),
    val loaded: Boolean = false,
    val failed: Boolean = false,
    /** The last refresh did not reach the server: the goals shown are the ones kept from before */
    val offline: Boolean = false,
    val removing: Set<Long> = emptySet(),
    /** Of [removing]: the ones whose linked schedules the user chose to turn off with them */
    val pauseSchedules: Set<Long> = emptySet(),
    /** Goals Kelo found in the conversations, offered at the top of the list */
    val suggestions: List<GoalSuggestion> = emptyList(),
    /** Suggestions being added or waved off right now (hidden at once) */
    val settling: Set<Long> = emptySet(),
) {
    val shownSuggestions: List<GoalSuggestion> get() = suggestions.filter { it.id !in settling }
    /** In progress, the nearest due date first */
    val tracking: List<Goal> get() = GoalsRules.ordered(goals.filter { !it.isDone && it.id !in removing })
    val done: List<Goal> get() = goals.filter { it.isDone && it.id !in removing }
}

/**
 * GET/POST /api/goals, PATCH/DELETE /api/goals/{id}. While the state is collected (the screen in view) the goals are fetched again at
 * the pace [paceMs] says (soon while Kelo works on one, so "working" and "waiting for you" follow the server).
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class GoalsViewModel(
    private val sessions: Sessions,
    private val paceMs: (List<Goal>) -> Long? = GoalsRules::refreshMs,
) : ViewModel() {
    // the goals kept from last time show at once and are refreshed in place
    private val _state = MutableStateFlow(
        runCatching { sessions.cached(Caches.GOALS) }.getOrNull().let { kept -> GoalsState(goals = kept.orEmpty(), loaded = kept != null) },
    )
    val state: StateFlow<GoalsState> = _state.asStateFlow()
    private val pace = MutableStateFlow(paceMs(_state.value.goals))

    init {
        _state.whileObserved(viewModelScope) {
            pace.collectLatest { ms -> if (ms != null) while (true) { delay(ms); load() } }
        }
    }

    fun load() {
        viewModelScope.launch {
            try {
                val api = sessions.requireApi()
                val goals = api.get<List<Goal>>("/api/goals")
                sessions.keep(api, Caches.GOALS, goals)
                _state.update { it.copy(goals = goals, loaded = true, failed = false, offline = false) }
                pace.value = paceMs(goals)
            } catch (e: ApiError) {
                // with goals on screen (kept from before) a failed refresh is only a hint, not an error
                _state.update { if (it.loaded && e.isNetwork) it.copy(offline = true) else it.copy(failed = true) }
            }
            // an older server has no suggestions: nothing shows
            runCatching { sessions.requireApi().get<GoalSuggestions>("/api/goals/suggestions") }.getOrNull()?.let { s ->
                _state.update { it.copy(suggestions = s.items) }
            }
        }
    }

    /** 추가 on a suggestion: it becomes a goal and Kelo starts on it in the chat; [onAdded] once the server has it. */
    fun acceptSuggestion(s: GoalSuggestion, onAdded: () -> Unit) {
        _state.update { it.copy(settling = it.settling + s.id) }
        viewModelScope.launch {
            try {
                sessions.requireApi().post<JsonElement>("/api/goals/suggestions/${s.id}/accept")
                onAdded()
            } catch (e: ApiError) {
                _state.update { it.copy(failed = true) }
            } finally {
                _state.update { it.copy(settling = it.settling - s.id) }
            }
            load()
        }
    }

    /** 아니에요: the suggestion goes and is never offered again. */
    fun dismissSuggestion(s: GoalSuggestion) {
        _state.update { it.copy(settling = it.settling + s.id, suggestions = it.suggestions.filter { x -> x.id != s.id }) }
        viewModelScope.launch {
            runCatching { sessions.requireApi().post<JsonElement>("/api/goals/suggestions/${s.id}/dismiss") }
            _state.update { it.copy(settling = it.settling - s.id) }
        }
    }

    /** Adds a goal; [onAdded] runs once the server has it (the form then empties and closes). */
    fun add(title: String, note: String, category: String, onAdded: () -> Unit) {
        if (title.isBlank()) return
        launchCall {
            sessions.requireApi().post<JsonElement>("/api/goals", buildJsonObject {
                put("title", title)
                put("note", note)
                put("category", category)
            })
            onAdded()
            load()
        }
    }

    /** Changes a goal's title, note (with its due date and progress) and tag; [onSaved] once the server has it. */
    fun update(goal: Goal, title: String, note: String, category: String, onSaved: () -> Unit) {
        if (title.isBlank()) return
        launchCall {
            sessions.requireApi().patch<JsonElement>("/api/goals/${goal.id}", buildJsonObject {
                put("title", title)
                put("note", note)
                put("category", category)
            })
            onSaved()
            load()
        }
    }

    fun toggle(goal: Goal) = launchCall {
        val next = if (goal.isDone) Goal.STATUS_TRACKING else Goal.STATUS_DONE
        sessions.requireApi().patch<JsonElement>("/api/goals/${goal.id}", buildJsonObject { put("status", next) })
        load()
    }

    /** Swiped away (or the bin tapped): hidden at once, deleted later by [commitRemove] unless [undoRemove] comes first.
     *  [pauseSchedules]: the user chose to turn its linked schedules off too (asked before this, see [GoalsRules.asksAboutSchedules]). */
    fun startRemove(goal: Goal, pauseSchedules: Boolean = false) = _state.update {
        it.copy(removing = it.removing + goal.id, pauseSchedules = if (pauseSchedules) it.pauseSchedules + goal.id else it.pauseSchedules - goal.id)
    }

    fun undoRemove(goal: Goal) = _state.update { it.copy(removing = it.removing - goal.id, pauseSchedules = it.pauseSchedules - goal.id) }

    /** The real delete: the server also stops the work running for the goal, and pauses or keeps its schedules as chosen. */
    fun commitRemove(goal: Goal) {
        if (goal.id !in _state.value.removing) return   // undone meanwhile
        val pause = goal.id in _state.value.pauseSchedules
        launchCall {
            try {
                sessions.requireApi().delete<JsonElement>(GoalsRules.deletePath(goal.id, pause))
                _state.update { it.copy(goals = it.goals.filter { g -> g.id != goal.id }) }
            } finally {
                // a failed delete brings the goal back
                _state.update { it.copy(removing = it.removing - goal.id, pauseSchedules = it.pauseSchedules - goal.id) }
            }
            load()
        }
    }

    /** Leaving the page with an undo still on offer: delete what was swiped away. */
    override fun onCleared() {
        val api = runCatching { sessions.requireApi() }.getOrNull() ?: return
        val ids = _state.value.removing
        val pause = _state.value.pauseSchedules
        if (ids.isEmpty()) return
        kotlinx.coroutines.GlobalScope.launch {
            ids.forEach { id -> runCatching { api.delete<JsonElement>(GoalsRules.deletePath(id, id in pause)) } }
        }
    }

    private fun launchCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiError) {
                _state.value = _state.value.copy(failed = true)
            }
        }
    }
}
