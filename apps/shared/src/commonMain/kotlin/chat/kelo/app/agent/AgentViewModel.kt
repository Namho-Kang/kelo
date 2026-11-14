package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import org.jetbrains.compose.resources.StringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.Sessions
import java.time.YearMonth
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The agent's work (tasks, background jobs, questions waiting, schedules, browser tabs) and its records (permission rules and
 * history, the journal, the nightly look-back). Each part loads when a page first shows it and refreshes when shown again; a
 * failed refresh keeps what is shown.
 *
 * The one entry the pages talk to; each area is a part sharing an [AgentHost]: [AgentTasks], [AgentJobs] (background jobs and
 * watchers), [AgentFollowups] (secure entries, follow-ups), [AgentSchedules], [AgentPermissions], [AgentBrowser], [AgentJournal],
 * [AgentLookback] and [AgentSkills].
 */
class AgentViewModel(
    private val sessions: Sessions,
    /** What only the app can do (the phone starts its own work after an approval) */
    platform: chat.kelo.app.chat.ChatPlatform = chat.kelo.app.chat.ChatPlatform.Default,
) : ViewModel() {
    private val host = AgentHost(viewModelScope, sessions, platform)
    private val taskPart = AgentTasks(host)
    private val jobPart = AgentJobs(host, taskPart)
    private val schedulePart = AgentSchedules(host)
    private val permPart = AgentPermissions(host)
    private val browserPart = AgentBrowser(host, taskPart)
    private val followupPart = AgentFollowups(host, jobPart, permPart)
    private val journalPart = AgentJournal(host)
    private val lookbackPart = AgentLookback(host)
    private val skillPart = AgentSkills(host)

    val tasks: StateFlow<Loadable<TaskList>> = taskPart.tasks
    val schedules: StateFlow<Loadable<ScheduleList>> = schedulePart.schedules
    val perms: StateFlow<Loadable<Permissions>> = permPart.perms
    val browser: StateFlow<Loadable<BrowserTabs>> = browserPart.browser
    val journal: StateFlow<Loadable<Journal>> = journalPart.journal
    /** The month the 일지 tab shows */
    val month: StateFlow<YearMonth> = journalPart.month
    /** Things the agent asks about again (GET /api/followups); the ones on hold go in 답이 필요해요 */
    val followups: StateFlow<Loadable<chat.kelo.app.followup.FollowupList>> = followupPart.followups
    /** Follow-ups with a button being done right now */
    val followupBusy: StateFlow<Set<String>> = followupPart.followupBusy
    /** The nightly look-back (shown in 일지) */
    val lookback: StateFlow<Loadable<Lookback>> = lookbackPart.lookback
    /** Custom skills waiting for the owner's approval (shown on the Records page, next to the learned rules) */
    val skillProposals: StateFlow<Loadable<SkillProposals>> = skillPart.skillProposals
    /** What the last memory pass changed (opened from its line on the Records page) */
    val memoryChanges: StateFlow<Loadable<MemoryChanges>> = lookbackPart.memoryChanges
    val memoryUndoing: StateFlow<Boolean> = lookbackPart.memoryUndoing
    val rebuilding: StateFlow<Set<String>> = journalPart.rebuilding
    val messages: SharedFlow<AgentMessage> = host.messages.asSharedFlow()
    /** Background jobs (GET /api/workers); an older server without them answers 404, read as none */
    val workers: StateFlow<Loadable<WorkerList>> = jobPart.workers
    /** Secure entries waiting for a value (GET /api/secret-requests): rows in 답이 필요해요; an older server without them counts as none */
    val entries: StateFlow<Loadable<List<chat.kelo.app.chat.vault.SecretRequest>>> = followupPart.entries
    /** The watchers (GET /api/watchers); an older server without them answers 404, read as none */
    val watchers: StateFlow<Loadable<WatcherList>> = jobPart.watchers
    /** The week the 기록 page shows: its journal entries and the days still waiting to be written */
    val week: StateFlow<Loadable<WeekDigest>> = journalPart.week
    /** Schedules put away (DELETE archives them for a while) */
    val archive: StateFlow<Loadable<ScheduleArchive>> = schedulePart.archive

    init {
        // 마이 → 대화 기록 삭제: the server deleted the records this screen may be holding
        viewModelScope.launch { sessions.historyCleared.drop(1).collect { forgetHistory() } }
    }

    /**
     * The chat history was cleared on the server: the task log, background jobs, approval history, follow-ups, day summaries and
     * ended browser tabs this screen holds go at once, and the lists that were loaded are asked for again. Rules, schedules and
     * watchers stay as they are (the clear keeps them).
     */
    fun forgetHistory() {
        val shown = listOf(tasks.value.loaded, workers.value.loaded, followups.value.loaded, perms.value.loaded, browser.value.loaded)
        taskPart.forgetHistory()
        jobPart.forgetHistory()
        followupPart.forgetHistory()
        permPart.forgetHistory()
        browserPart.forgetHistory()
        journalPart.forgetHistory()
        if (shown[0]) loadTasks()
        if (shown[1]) loadWorkers()
        if (shown[2]) loadFollowups()
        if (shown[3]) loadPerms()
        if (shown[4]) loadBrowser()
        journalPart.reloadJournal()
    }

    fun say(text: StringResource, vararg args: Any) = host.say(text, *args)

    /** Everything the 작업 timeline shows. */
    fun loadWork() {
        loadTasks()
        loadWorkers()
        loadWatchers()
        loadPerms()
        loadSchedules()
        loadBrowser()
        loadFollowups()
        loadEntries()
    }

    /** What changes while the agent works (the agenda and the rules change only when the user changes them). */
    fun refreshLive() {
        loadTasks()
        loadWorkers()
        loadWatchers()
        loadSchedules()
        loadPerms()
        loadBrowser()
        loadFollowups()
        loadEntries()
    }

    // ---- secure entries and follow-ups
    fun loadEntries() = followupPart.loadEntries()

    /** 그만두기 on an entry's row: the agent is told and does not ask again; the row and the badge go at once. */
    fun cancelEntry(r: chat.kelo.app.chat.vault.SecretRequest, onClosed: (String) -> Unit = {}) = followupPart.cancelEntry(r, onClosed)

    /** A server without follow-ups (404) counts as none. */
    fun loadFollowups() = followupPart.loadFollowups()

    /** One button of a follow-up card (다시 시작, 그만 묻기, …); the list loads again after it, and the jobs too when one went on. */
    fun runFollowup(f: chat.kelo.app.followup.Followup, step: chat.kelo.app.followup.FollowupStep) = followupPart.runFollowup(f, step)

    // ---- tasks
    fun loadTasks() = taskPart.loadTasks()
    fun loadMoreTasks() = taskPart.loadMoreTasks()

    /** The list was looked at: everything finished up to [upTo] counts as seen on the server (the dots stay for this visit). */
    fun markSeen(upTo: Long) = taskPart.markSeen(upTo)

    suspend fun task(id: Long): TaskDetail = taskPart.task(id)
    fun stopTask(id: Long, then: () -> Unit) = taskPart.stopTask(id, then)

    // ---- background jobs
    fun loadWorkers() = jobPart.loadWorkers()
    suspend fun worker(id: String): WorkerDetail = jobPart.worker(id)
    fun stopWorker(id: String, then: () -> Unit = {}) = jobPart.stopWorker(id, then)
    fun resumeWorker(id: String, note: String = "", then: () -> Unit = {}) = jobPart.resumeWorker(id, note, then)
    fun messageWorker(id: String, text: String, then: () -> Unit = {}) = jobPart.messageWorker(id, text, then)

    // ---- watchers
    fun loadWatchers() = jobPart.loadWatchers()
    suspend fun watcherEvents(id: Long): List<WatcherEvent> = jobPart.watcherEvents(id)
    suspend fun buyCheck(id: Long) = jobPart.buyCheck(id)

    /** On or off; turning on needs a fresh successful check first (409: the server says why). */
    fun setWatcherEnabled(w: Watcher, on: Boolean) = jobPart.setWatcherEnabled(w, on)

    /** Checks once now, without the agent; the answer replaces the watcher (its dry run and whether it may be turned on). */
    fun dryRunWatcher(w: Watcher, then: (DryRun?) -> Unit = {}) = jobPart.dryRunWatcher(w, then)
    fun deleteWatcher(w: Watcher, then: () -> Unit) = jobPart.deleteWatcher(w, then)

    // ---- schedules
    fun loadSchedules() = schedulePart.loadSchedules()
    fun setEnabled(s: Schedule, on: Boolean) = schedulePart.setEnabled(s, on)

    /**
     * Saves a new schedule (id null) or changes one. [plan] is the simple form's choice (null keeps a saved schedule's time as it is:
     * only the name and what to do change). A repeat goes as cron (+ starts_on / ends_on), a one-time run as run_at; the start-time
     * spread ("window") is never offered, so it is always 0. [onSaved] runs once the server took it; a refusal says why.
     */
    fun saveSchedule(
        id: Long?, name: String, prompt: String, plan: SchedulePlan?, timezone: String?, onFailed: () -> Unit = {},
        now: java.time.LocalDateTime = java.time.LocalDateTime.now(), onSaved: (Schedule) -> Unit,
    ) = schedulePart.saveSchedule(id, name, prompt, plan, timezone, onFailed, now, onSaved)

    /** Back on after a pause (422: a one-time job whose time has passed; the server says so). */
    fun resumeSchedule(id: Long) = schedulePart.resumeSchedule(id)

    suspend fun scheduleRuns(id: Long): List<ScheduleRun> = schedulePart.scheduleRuns(id)

    /** What a schedule may do without asking (allowed once at setup), and taking one back. */
    suspend fun scheduleGrants(id: Long): List<ScheduleGrant> = schedulePart.scheduleGrants(id)
    suspend fun revokeScheduleGrant(g: ScheduleGrant) = schedulePart.revokeScheduleGrant(g)
    /** What a watcher's / a job's task may do without asking (allowed once at setup) */
    suspend fun watcherGrants(id: Long): List<ScheduleGrant> = schedulePart.watcherGrants(id)
    suspend fun jobGrants(id: String): List<ScheduleGrant> = schedulePart.jobGrants(id)

    fun loadArchive() = schedulePart.loadArchive()
    fun restoreSchedule(id: Long) = schedulePart.restoreSchedule(id)
    fun runNow(id: Long) = schedulePart.runNow(id)

    /** The detail page's 삭제 dialog: into the archive (restorable for a while), or [forever]: gone at once. */
    fun deleteSchedule(id: Long, then: () -> Unit, forever: Boolean = false) = schedulePart.deleteSchedule(id, then, forever)

    /** A swipe on the schedules list: into the archive at once (the snackbar's 실행 취소 puts it back: [restoreSchedule]). */
    fun archiveSchedule(id: Long) = schedulePart.archiveSchedule(id)

    /** A swipe in 보관한 예약: hidden at once and deleted for good unless [undoEraseArchived] comes first. */
    fun eraseArchivedSoon(id: Long) = schedulePart.eraseArchivedSoon(id)

    /** 실행 취소 on the message: the delete is dropped and the schedule shows again. */
    fun undoEraseArchived(id: Long) = schedulePart.undoEraseArchived(id)

    /** The message ended without 실행 취소: delete [id] now. */
    fun eraseArchived(id: Long) = schedulePart.eraseArchived(id)

    /** The page is closing (back, another tab, the app going away): every waiting delete is sent now. */
    fun commitArchivedErases() = schedulePart.commitArchivedErases()

    // ---- approvals and permission rules
    fun loadPerms() = permPart.loadPerms()
    fun loadMoreHistory() = permPart.loadMoreHistory()

    /**
     * Answers a waiting approval ([remember]: allow this kind of step on this site from now on). [beforeAllow] runs first on Allow
     * (a payment with an end-to-end card sends the card from this phone); when it fails the question stays open.
     */
    fun answer(
        a: PendingApproval, approve: Boolean, remember: Boolean = false, beforeAllow: (suspend () -> Boolean)? = null,
        rememberSchedule: Boolean = false,
    ) = permPart.answer(a, approve, remember, beforeAllow, rememberSchedule)

    fun revokeRule(r: ApprovalRule) = permPart.revokeRule(r)

    /** Stops a task's permission at once: its next such step asks again. */
    fun revokeTaskPermission(g: TaskPermission) = permPart.revokeTaskPermission(g)

    // ---- browser tabs
    fun loadBrowser() = browserPart.loadBrowser()
    suspend fun screenshot(tabId: String): ByteArray = browserPart.screenshot(tabId)
    fun closeTab(t: BrowserTab, then: () -> Unit) = browserPart.closeTab(t, then)

    // ---- journal
    fun loadJournal(month: YearMonth) = journalPart.loadJournal(month)

    /** The week starting [start] (a Monday): one or two months of the journal, cut to the week. */
    fun loadWeek(start: java.time.LocalDate) = journalPart.loadWeek(start)
    fun deleteEntry(e: JournalEntry) = journalPart.deleteEntry(e)

    /** POST /api/journal/rebuild?date=: writes that day's entries again with the main model. */
    fun rebuild(date: String) = journalPart.rebuild(date)

    // ---- the nightly look-back (GET/PUT /api/reflection) and the memory pass
    fun loadLookback() = lookbackPart.loadLookback()
    fun loadMemoryChanges() = lookbackPart.loadMemoryChanges()

    /** Puts the memory files back as they were before the last pass, then reloads the page's line. */
    fun undoMemoryPass(onDone: () -> Unit = {}) = lookbackPart.undoMemoryPass(onDone)

    /** The switch: shown at once, put back if the server says no. */
    fun setLookback(on: Boolean) = lookbackPart.setLookback(on)

    /**
     * 맞아요 / 아니에요 on a learned rule (also 그만 지키기 and 다시 지키기): shown at once, put back if the server says no. A rule the
     * server no longer has (404) makes the list load again.
     */
    fun setRuleStatus(id: Long, status: String) = lookbackPart.setRuleStatus(id, status)

    // ---- custom skills waiting for approval (GET /api/skills/proposals) and "Save as skill" on a finished task or job
    fun loadSkillProposals() = skillPart.loadSkillProposals()

    /**
     * Approve (written as shown and used from now on) or discard a proposal; an edited skill is approved only as the owner saw it
     * (its hash goes along). [done] runs with true on success; on failure the list loads again and the reason is shown.
     */
    fun answerSkill(p: SkillProposal, approve: Boolean, done: (Boolean) -> Unit) = skillPart.answerSkill(p, approve, done)

    /**
     * Ask Kelo to draft a skill from a finished chat task or background job; the draft waits for approval on the Records page.
     * [where]: the names of the tab and page it will be on (마이, 기록), as the app shows them.
     */
    fun saveAsSkill(taskId: Long? = null, workerId: String? = null, where: List<String> = emptyList()) = skillPart.saveAsSkill(taskId, workerId, where)

    companion object {
        const val PAGE = 50
        /** How long a swiped-away archived schedule waits for 실행 취소 when the page never says (a little over the short message) */
        const val ERASE_GRACE_MS = 6_000L
    }
}
