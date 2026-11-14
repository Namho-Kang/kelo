package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Schedules: the list, saving and changing one, the archive (restore, delete for good with an undo window) and the grants. */
class AgentSchedules(private val host: AgentHost) {
    private val _schedules = MutableStateFlow(Loadable<ScheduleList>())
    val schedules: StateFlow<Loadable<ScheduleList>> = _schedules.asStateFlow()

    fun loadSchedules() { host.refresh(_schedules) { host.api().get<ScheduleList>("/api/schedules") } }

    fun setEnabled(s: Schedule, on: Boolean) {
        _schedules.update { l -> l.copy(data = l.data?.let { d -> d.copy(items = d.items.map { if (it.id == s.id) it.copy(enabled = on) else it }) }) }
        host.scope.launch {
            try {
                val saved = host.api().patch<Schedule>("/api/schedules/${s.id}", buildJsonObject { put("enabled", on) })
                replaceSchedule(saved)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                replaceSchedule(s)   // back as it was
                host.sayError(e)
            }
        }
    }

    private fun replaceSchedule(s: Schedule) =
        _schedules.update { l -> l.copy(data = l.data?.let { d -> d.copy(items = d.items.map { if (it.id == s.id) s else it }) }) }

    /**
     * Saves a new schedule (id null) or changes one. [plan] is the simple form's choice (null keeps a saved schedule's time as it is:
     * only the name and what to do change). A repeat goes as cron (+ starts_on / ends_on), a one-time run as run_at; the start-time
     * spread ("window") is never offered, so it is always 0. [onSaved] runs once the server took it; a refusal says why.
     */
    fun saveSchedule(
        id: Long?, name: String, prompt: String, plan: SchedulePlan?, timezone: String?, onFailed: () -> Unit = {},
        now: java.time.LocalDateTime = java.time.LocalDateTime.now(), onSaved: (Schedule) -> Unit,
    ) {
        val body = buildJsonObject {
            put("name", name.trim())
            put("prompt", prompt.trim())
            val none = kotlinx.serialization.json.JsonNull
            if (plan != null) {
                val cron = PlanRules.cron(plan)
                if (cron != null) {
                    put("cron", cron)
                    put("window_minutes", 0)
                    put("starts_on", PlanRules.startsOn(plan, now))
                    put("ends_on", PlanRules.endsOn(plan))
                    if (id != null) { put("run_at", none); put("every_minutes", none) }
                } else {
                    put("run_at", PlanRules.runAt(plan))
                    if (id != null) { put("cron", none); put("every_minutes", none) }
                }
            }
            if (!timezone.isNullOrBlank()) put("timezone", timezone)
        }
        host.scope.launch {
            try {
                val s = if (id == null) host.api().post<Schedule>("/api/schedules", body) else host.api().patch<Schedule>("/api/schedules/$id", body)
                host.say(Res.string.agent_schedule_saved)
                onSaved(s)
                loadSchedules()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed()
                host.sayError(e)
            }
        }
    }

    /** Back on after a pause (422: a one-time job whose time has passed; the server says so). */
    fun resumeSchedule(id: Long) {
        host.scope.launch {
            try {
                replaceSchedule(host.api().post<Schedule>("/api/schedules/$id/resume"))
                host.say(Res.string.work_schedule_resumed)
            } catch (e: ApiError) {
                host.sayError(e)
            }
            loadSchedules()
        }
    }

    suspend fun scheduleRuns(id: Long): List<ScheduleRun> = host.api().get<ScheduleRuns>("/api/schedules/$id/runs?limit=20").items

    /** What a schedule may do without asking (allowed once at setup), and taking one back. */
    suspend fun scheduleGrants(id: Long): List<ScheduleGrant> = ScheduleGrantCalls.list(host.api(), id)
    suspend fun revokeScheduleGrant(g: ScheduleGrant) = ScheduleGrantCalls.revoke(host.api(), g)
    /** What a watcher's / a job's task may do without asking (allowed once at setup) */
    suspend fun watcherGrants(id: Long): List<ScheduleGrant> = ScheduleGrantCalls.forWatcher(host.api(), id)
    suspend fun jobGrants(id: String): List<ScheduleGrant> = ScheduleGrantCalls.forJob(host.api(), id)

    private val _archive = MutableStateFlow(Loadable<ScheduleArchive>())
    /** Schedules put away (DELETE archives them for a while) */
    val archive: StateFlow<Loadable<ScheduleArchive>> = _archive.asStateFlow()

    fun loadArchive() { host.refresh(_archive) {
        val a = try {
            host.api().get<ScheduleArchive>("/api/schedules/archive")
        } catch (e: ApiError) {
            if (e.status == 404) ScheduleArchive() else throw e
        }
        // one swiped away whose delete is still waiting for the undo message stays hidden
        val waiting = synchronized(pendingErase) { pendingErase.keys + erasing }
        if (waiting.isEmpty()) a else a.copy(items = a.items.filterNot { it.id in waiting })
    } }

    fun restoreSchedule(id: Long) {
        host.scope.launch {
            try {
                host.api().post<Schedule>("/api/schedules/$id/restore")
                host.say(Res.string.work_schedule_restored)
            } catch (e: ApiError) {
                host.sayError(e)
            }
            loadArchive()
            loadSchedules()
        }
    }

    fun runNow(id: Long) {
        host.scope.launch {
            try {
                host.api().post<JsonElement>("/api/schedules/$id/run")
                host.say(Res.string.agent_schedule_started)
                loadSchedules()
            } catch (e: ApiError) {
                if (e.status == 409) host.say(Res.string.agent_schedule_already_running) else host.sayError(e)
            }
        }
    }

    /** The detail page's 삭제 dialog: into the archive (restorable for a while), or [forever]: gone at once. */
    fun deleteSchedule(id: Long, then: () -> Unit, forever: Boolean = false) {
        host.scope.launch {
            try {
                host.api().delete<JsonElement>("/api/schedules/$id" + if (forever) "?forever=true" else "")
                host.say(if (forever) Res.string.schedule_deleted_forever else Res.string.agent_schedule_deleted)
                then()
                loadSchedules()
                loadArchive()
            } catch (e: ApiError) {
                host.sayError(e)
            }
        }
    }

    /** A swipe on the schedules list: into the archive at once (the snackbar's 실행 취소 puts it back: [restoreSchedule]). */
    fun archiveSchedule(id: Long) {
        _schedules.update { l -> l.copy(data = l.data?.let { d -> d.copy(items = d.items.filterNot { it.id == id }) }) }
        host.scope.launch {
            try { host.api().delete<JsonElement>("/api/schedules/$id") } catch (e: ApiError) { host.sayError(e) }
            loadSchedules()
            loadArchive()
        }
    }

    /**
     * Deletes waiting for the undo message of a swipe in 보관한 예약, by schedule id. They run in [AgentHost.scope], not in the
     * page's scope, so leaving the page never cancels one: [commitArchivedErases] (called when the page closes) sends them at once,
     * and a delete nobody committed still goes out after [AgentViewModel.ERASE_GRACE_MS].
     */
    private val pendingErase = LinkedHashMap<Long, kotlinx.coroutines.Job>()

    /** Deletes on their way to the server (guarded by [pendingErase]): a list loaded meanwhile must not show them again. */
    private val erasing = mutableSetOf<Long>()

    /** A swipe in 보관한 예약: hidden at once and deleted for good unless [undoEraseArchived] comes first. */
    fun eraseArchivedSoon(id: Long) {
        _archive.update { l -> l.copy(data = l.data?.let { d -> d.copy(items = d.items.filterNot { it.id == id }) }) }
        val job = host.scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            delay(AgentViewModel.ERASE_GRACE_MS)
            eraseNow(id)
        }
        synchronized(pendingErase) { pendingErase.put(id, job) }?.cancel()
        job.start()
    }

    /** 실행 취소 on the message: the delete is dropped and the schedule shows again. */
    fun undoEraseArchived(id: Long) {
        val job = synchronized(pendingErase) { pendingErase.remove(id) } ?: return
        job.cancel()
        loadArchive()
    }

    /** The message ended without 실행 취소: delete [id] now. */
    fun eraseArchived(id: Long) {
        val job = synchronized(pendingErase) { pendingErase.remove(id) }
        job?.cancel()
        host.scope.launch { eraseNow(id) }
    }

    /** The page is closing (back, another tab, the app going away): every waiting delete is sent now. */
    fun commitArchivedErases() {
        val ids = synchronized(pendingErase) { pendingErase.keys.toList() }
        ids.forEach { eraseArchived(it) }
    }

    private suspend fun eraseNow(id: Long) {
        synchronized(pendingErase) { pendingErase.remove(id); erasing += id }
        try {
            host.api().delete<JsonElement>("/api/schedules/archive/$id")
        } catch (e: ApiError) {
            if (e.status != 404) host.sayError(e)
        } finally {
            synchronized(pendingErase) { erasing -= id }
        }
        loadArchive()
    }
}
