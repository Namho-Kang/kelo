package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import org.jetbrains.compose.resources.StringResource
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Background jobs and watchers: their lists, and what the owner does to them (stop, resume, message, turn on, delete). */
class AgentJobs(private val host: AgentHost, private val tasks: AgentTasks) {
    private val _workers = MutableStateFlow(Loadable<WorkerList>())
    /** Background jobs (GET /api/workers); an older server without them answers 404, read as none */
    val workers: StateFlow<Loadable<WorkerList>> = _workers.asStateFlow()

    // ---- background jobs
    fun loadWorkers() { host.refresh(_workers) {
        try {
            host.api().get<WorkerList>("/api/workers?limit=${AgentViewModel.PAGE}")
        } catch (e: ApiError) {
            if (e.status == 404) WorkerList() else throw e
        }
    } }

    suspend fun worker(id: String): WorkerDetail = host.api().get("/api/workers/${KeloApi.segment(id)}")

    fun stopWorker(id: String, then: () -> Unit = {}) = workerCall(id, "stop", null, Res.string.work_job_stopped, then)

    fun resumeWorker(id: String, note: String = "", then: () -> Unit = {}) =
        workerCall(id, "resume", buildJsonObject { if (note.isNotBlank()) put("note", note.trim()) }, Res.string.work_job_resumed, then)

    fun messageWorker(id: String, text: String, then: () -> Unit = {}) =
        workerCall(id, "message", buildJsonObject { put("text", text.trim()) }, Res.string.work_job_message_sent, then)

    private fun workerCall(id: String, action: String, body: JsonObject?, done: StringResource, then: () -> Unit) {
        host.scope.launch {
            try {
                host.api().post<JsonElement>("/api/workers/${KeloApi.segment(id)}/$action", body)
                host.say(done)
                then()
            } catch (e: ApiError) {
                when (e.status) {
                    404 -> host.say(Res.string.work_job_gone)
                    409 -> host.say(Res.string.work_job_busy)
                    429 -> host.say(Res.string.work_job_too_many)
                    else -> host.sayError(e)
                }
            }
            loadWorkers()
            tasks.loadTasks()
        }
    }

    // ---- watchers
    private val _watchers = MutableStateFlow(Loadable<WatcherList>())
    /** The watchers (GET /api/watchers); an older server without them answers 404, read as none */
    val watchers: StateFlow<Loadable<WatcherList>> = _watchers.asStateFlow()

    fun loadWatchers() { host.refresh(_watchers) {
        try {
            host.api().get<WatcherList>("/api/watchers")
        } catch (e: ApiError) {
            if (e.status == 404) WatcherList() else throw e
        }
    } }

    suspend fun watcherEvents(id: Long): List<WatcherEvent> = host.api().get<WatcherEvents>("/api/watchers/$id/events").items
    suspend fun buyCheck(id: Long): BuyReady = host.api().post("/api/watchers/$id/buy-check", buildJsonObject {})

    private fun replaceWatcher(w: Watcher) =
        _watchers.update { l -> l.copy(data = l.data?.let { d -> d.copy(items = d.items.map { if (it.id == w.id) w else it }) }) }

    /** On or off; turning on needs a fresh successful check first (409: the server says why). */
    fun setWatcherEnabled(w: Watcher, on: Boolean) {
        replaceWatcher(w.copy(enabled = on))
        host.scope.launch {
            try {
                replaceWatcher(host.api().post<Watcher>("/api/watchers/${w.id}/enable", buildJsonObject { put("enabled", on) }))
            } catch (e: ApiError) {
                replaceWatcher(w)
                if (e.status == 409) host.say(Res.string.work_watch_needs_check) else host.sayError(e)
            }
        }
    }

    /** Checks once now, without the agent; the answer replaces the watcher (its dry run and whether it may be turned on). */
    fun dryRunWatcher(w: Watcher, then: (DryRun?) -> Unit = {}) {
        host.scope.launch {
            try {
                val a = host.api().post<DryRunAnswer>("/api/watchers/${w.id}/dry-run")
                a.watcher?.let(::replaceWatcher)
                then(a.result)
            } catch (e: ApiError) {
                host.sayError(e)
                then(null)
            }
        }
    }

    fun deleteWatcher(w: Watcher, then: () -> Unit) {
        host.scope.launch {
            try {
                host.api().delete<JsonElement>("/api/watchers/${w.id}")
                host.say(Res.string.work_watch_deleted)
                then()
                loadWatchers()
            } catch (e: ApiError) {
                if (e.status == 404) { then(); loadWatchers() } else host.sayError(e)
            }
        }
    }

    /** The chat history was cleared on the server: the background jobs' counts go at once. */
    fun forgetHistory() {
        _workers.update { l -> l.copy(data = l.data?.let { WorkerList(running = 0, limit = it.limit) }) }
    }
}
