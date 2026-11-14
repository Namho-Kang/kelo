package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The task log (GET /api/tasks): the list, older pages, marking it seen, one task and stopping one. */
class AgentTasks(private val host: AgentHost) {
    private val _tasks = MutableStateFlow(Loadable<TaskList>())
    val tasks: StateFlow<Loadable<TaskList>> = _tasks.asStateFlow()

    // ---- tasks
    private var markedUpTo = 0L

    fun loadTasks() { host.refresh(_tasks) {
        val p = host.api().get<TaskPage>("/api/tasks?limit=${AgentViewModel.PAGE}")
        TaskList(p.items, p.nextBefore)
    } }

    fun loadMoreTasks() {
        val cur = _tasks.value.data ?: return
        val before = cur.nextBefore ?: return
        if (cur.loadingMore) return
        _tasks.update { it.copy(data = cur.copy(loadingMore = true)) }
        host.scope.launch {
            try {
                val p = host.api().get<TaskPage>("/api/tasks?limit=${AgentViewModel.PAGE}&before=$before")
                _tasks.update { l ->
                    val d = l.data ?: return@update l
                    val known = d.items.mapTo(HashSet()) { it.id }
                    l.copy(data = d.copy(items = d.items + p.items.filter { it.id !in known }, nextBefore = p.nextBefore, loadingMore = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _tasks.update { l -> l.copy(data = l.data?.copy(loadingMore = false)) }
            }
        }
    }

    /** The list was looked at: everything finished up to [upTo] counts as seen on the server (the dots stay for this visit). */
    fun markSeen(upTo: Long) {
        if (upTo <= markedUpTo) return
        markedUpTo = upTo
        host.scope.launch { runCatching { host.api().post<JsonElement>("/api/tasks/seen", buildJsonObject { put("up_to", upTo) }) } }
    }

    suspend fun task(id: Long): TaskDetail = host.api().get("/api/tasks/$id")

    fun stopTask(id: Long, then: () -> Unit) {
        host.scope.launch {
            try {
                host.api().post<JsonElement>("/api/tasks/$id/stop")
                host.say(Res.string.agent_task_stopped)
            } catch (e: ApiError) {
                if (e.status == 404) host.say(Res.string.agent_task_not_running) else host.sayError(e)
            }
            then()
            loadTasks()
        }
    }

    /** The chat history was cleared on the server: the task log goes at once. */
    fun forgetHistory() {
        _tasks.update { l -> l.copy(data = l.data?.let { TaskList() }) }
    }
}
