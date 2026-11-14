package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The nightly look-back (GET/PUT /api/reflection): the switch, the learned rules and the memory pass. */
class AgentLookback(private val host: AgentHost) {
    private val _lookback = MutableStateFlow(Loadable<Lookback>())
    /** The nightly look-back (shown in 일지) */
    val lookback: StateFlow<Loadable<Lookback>> = _lookback.asStateFlow()

    private val _memoryChanges = MutableStateFlow(Loadable<MemoryChanges>())
    /** What the last memory pass changed (opened from its line on the Records page) */
    val memoryChanges: StateFlow<Loadable<MemoryChanges>> = _memoryChanges.asStateFlow()
    private val _memoryUndoing = MutableStateFlow(false)
    val memoryUndoing: StateFlow<Boolean> = _memoryUndoing.asStateFlow()

    fun loadLookback() { host.refresh(_lookback) {
        try {
            host.api().get<Lookback>("/api/reflection")
        } catch (e: ApiError) {
            if (e.status == 404) Lookback(available = false) else throw e
        }
    } }

    // ---- the memory pass (GET /api/reflection/memory, POST /api/reflection/memory/undo)
    fun loadMemoryChanges() {
        _memoryChanges.value = Loadable()
        host.refresh(_memoryChanges) { host.api().get<MemoryChanges>("/api/reflection/memory") }
    }

    /** Puts the memory files back as they were before the last pass, then reloads the page's line. */
    fun undoMemoryPass(onDone: () -> Unit = {}) {
        if (_memoryUndoing.value) return
        _memoryUndoing.value = true
        host.scope.launch {
            try {
                host.api().post<JsonElement>("/api/reflection/memory/undo")
                host.say(Res.string.memory_pass_undone)
                onDone()
            } catch (e: ApiError) {
                if (e.status == 404) { host.say(Res.string.memory_pass_undo_gone); onDone() } else host.sayError(e)
            } finally {
                _memoryUndoing.value = false
                loadLookback()
            }
        }
    }

    /** The switch: shown at once, put back if the server says no. */
    fun setLookback(on: Boolean) {
        val before = _lookback.value.data ?: return
        _lookback.update { it.copy(data = before.copy(enabled = on)) }
        host.scope.launch {
            try {
                val after = host.api().put<Lookback>("/api/reflection/enabled", buildJsonObject { put("enabled", on) })
                _lookback.update { Loadable(after) }
            } catch (e: ApiError) {
                _lookback.update { it.copy(data = before) }
                host.sayError(e)
            }
        }
    }

    /**
     * 맞아요 / 아니에요 on a learned rule (also 그만 지키기 and 다시 지키기): shown at once, put back if the server says no. A rule the
     * server no longer has (404) makes the list load again.
     */
    fun setRuleStatus(id: Long, status: String) {
        val before = _lookback.value.data ?: return
        _lookback.update { it.copy(data = before.copy(rules = LookbackRules.withStatus(before.rules, id, status))) }
        host.scope.launch {
            try {
                val rule = host.api().patch<LearnedRule>("/api/reflection/rules/$id", buildJsonObject { put("status", status) })
                _lookback.update { l -> l.copy(data = l.data?.let { d -> d.copy(rules = LookbackRules.replace(d.rules, rule)) }) }
            } catch (e: ApiError) {
                _lookback.update { it.copy(data = before) }
                if (e.status == 404) loadLookback() else host.sayError(e)
            }
        }
    }
}
