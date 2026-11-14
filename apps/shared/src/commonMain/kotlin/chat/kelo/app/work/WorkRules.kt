package chat.kelo.app.work

import chat.kelo.app.agent.AgentTask
import chat.kelo.app.agent.BrowserTab
import chat.kelo.app.agent.DayGroups
import chat.kelo.app.agent.PendingApproval
import chat.kelo.app.agent.Schedule
import chat.kelo.app.agent.ScheduleTimes
import chat.kelo.app.agent.Worker
import chat.kelo.app.chat.AgentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The 작업 tab's one timeline, worked out apart from the UI so it can be tested: what runs now, what needs an answer, what comes
 * next (an agenda of the schedules) and what is done (by day, with a filter). Background jobs and their runs are nested under the
 * task that handed them off.
 */

/** A background job with the tasks that are its runs (oldest first). */
data class JobNode(val worker: Worker, val runs: List<AgentTask> = emptyList()) {
    val key: String get() = "job:" + worker.id
}

/** A task with the jobs it handed off and the other tasks that name it as their parent. */
data class TaskNode(val task: AgentTask, val jobs: List<JobNode> = emptyList(), val children: List<AgentTask> = emptyList()) {
    val key: String get() = "task:" + task.id
    /** The task itself, or anything under it, is still going */
    val live: Boolean get() = task.running || children.any { it.running } || jobs.any { it.worker.running || it.runs.any { r -> r.running } }
    /** The task itself is over but a job it handed off still goes on (running, waiting for the owner, or blocked) */
    val inBackground: Boolean get() = !task.running && jobs.any { j ->
        j.worker.status in Timeline.JOB_GOING || j.runs.any { it.running }
    }
    val problem: Boolean get() = task.status == AgentTask.STATUS_FAILED || task.status == AgentTask.STATUS_STOPPED ||
        jobs.any { it.worker.status == Worker.FAILED || it.worker.status == Worker.STOPPED }
}

/** Something that waits for the user. */
sealed interface Ask {
    val key: String
    val at: Double

    /** An approval card; [deliver]: a payment that also needs this phone to send its card */
    data class Approval(val approval: PendingApproval) : Ask {
        override val key: String get() = "ask:" + approval.id
        override val at: Double get() = approval.askedAt
        val deliver: Boolean get() = approval.event.card?.needsCard == true
    }

    /** A background job stopped for a login, the tab, an answer, or a restart */
    data class Job(val worker: Worker) : Ask {
        override val key: String get() = "askjob:" + worker.id
        override val at: Double get() = worker.updatedAt
    }

    /**
     * A secure entry waiting for its value (a key, a sign-in, a card, a payment PIN): a short row; the card itself is in the chat
     * ([chatCard]: the agent asked for it there) and a tap goes to it. [chatCard] false: a vault item waiting for its value that no
     * chat card stands for (the Keychain's own).
     */
    data class Entry(val request: chat.kelo.app.chat.vault.SecretRequest) : Ask {
        override val key: String get() = "askentry:" + request.id
        override val at: Double get() = request.createdAt
        val chatCard: Boolean get() = request.fromAgent
    }

    /** Something the agent asked about twice without an answer, now on hold (보류) until the user starts it again */
    data class Hold(val followup: chat.kelo.app.followup.Followup) : Ask {
        override val key: String get() = "askhold:" + followup.id
        override val at: Double get() = followup.createdAt
    }
}

/** The past list's filter chips. */
enum class PastFilter { ALL, DONE, PROBLEMS, AUTOMATIC }

object Timeline {
    /** A job that is not over yet: running, waiting for the owner's answer, or blocked; its browser tab can still be open */
    val JOB_GOING: Set<String> = setOf(Worker.RUNNING, Worker.NEEDS_INPUT, Worker.BLOCKED)

    /** Sources that mean "the agent started this by itself" rather than a chat message. */
    private val AUTO_SOURCES = setOf("api", "background", "worker", "handoff")

    fun automatic(t: AgentTask): Boolean = t.source.startsWith("schedule") || t.source in AUTO_SOURCES

    /**
     * Nests [tasks] (newest first) and [workers]: a job sits under the task that handed it off, a task that is a run of a known
     * job sits under that job, and a task naming a parent sits under it. What has no parent in the lists stays at the top, in
     * the tasks' order; a job whose parent task is not loaded becomes a node of its own (returned in [roots] as a job).
     */
    fun nest(tasks: List<AgentTask>, workers: List<Worker>): Nested {
        val workerIds = workers.mapTo(HashSet()) { it.id }
        val taskIds = tasks.mapTo(HashSet()) { it.id }
        val runsBy = tasks.filter { it.workerId != null && it.workerId in workerIds }.groupBy { it.workerId!! }
        val jobsByParent = workers.filter { it.parentTaskId != null && it.parentTaskId in taskIds }.groupBy { it.parentTaskId!! }
        val childrenBy = tasks.filter { t -> t.workerId !in workerIds && t.parentId != null && t.parentId in taskIds && t.parentId != t.id }
            .groupBy { it.parentId!! }
        val nested = HashSet<Long>()
        runsBy.values.flatten().forEach { nested += it.id }
        childrenBy.values.flatten().forEach { nested += it.id }
        val roots = tasks.filter { it.id !in nested }.map { t ->
            TaskNode(
                t,
                jobsByParent[t.id].orEmpty().map { w -> JobNode(w, runsBy[w.id].orEmpty().sortedBy { it.startedAt }) },
                childrenBy[t.id].orEmpty().sortedBy { it.startedAt },
            )
        }
        val orphans = workers.filter { it.parentTaskId == null || it.parentTaskId !in taskIds }
            .map { w -> JobNode(w, runsBy[w.id].orEmpty().sortedBy { it.startedAt }) }
        return Nested(roots, orphans)
    }

    data class Nested(val roots: List<TaskNode>, val orphanJobs: List<JobNode>)

    /** 지금 하는 일: tasks with anything still going, and jobs of their own that are running; newest first. */
    fun now(n: Nested): Now = Now(
        n.roots.filter { it.live },
        n.orphanJobs.filter { it.worker.running || it.runs.any { r -> r.running } },
    )

    data class Now(val tasks: List<TaskNode>, val jobs: List<JobNode>) {
        val empty: Boolean get() = tasks.isEmpty() && jobs.isEmpty()
        val count: Int get() = tasks.size + jobs.size
    }

    /**
     * 답이 필요해요: approvals (payments that need this phone's card first), secure entries waiting for a value, stopped jobs, then
     * follow-ups on hold. A follow-up about
     * an approval or a job already listed is left out (the card above settles it).
     */
    fun asks(
        pending: List<PendingApproval>, workers: List<Worker>, followups: List<chat.kelo.app.followup.Followup> = emptyList(),
        entries: List<chat.kelo.app.chat.vault.SecretRequest> = emptyList(),
    ): List<Ask> {
        val approvals = pending.map { Ask.Approval(it) }.sortedWith(compareByDescending<Ask.Approval> { it.deliver }.thenBy { it.at })
        val jobs = workers.filter { it.needsYou || it.status == Worker.INTERRUPTED }.map { Ask.Job(it) }.sortedBy { it.at }
        val shownApprovals = pending.map { it.event.approvalId }.toSet()
        val shownJobs = jobs.map { it.worker.id }.toSet()
        val holds = chat.kelo.app.followup.FollowupRules.onHold(followups).filter { f ->
            f.actions.none { (it.approvalId.isNotEmpty() && it.approvalId in shownApprovals) || (it.workerId.isNotEmpty() && it.workerId in shownJobs) }
        }.map { Ask.Hold(it) }
        val waiting = entries.filter { it.open }.sortedBy { it.createdAt }.map { Ask.Entry(it) }
        return approvals + waiting + jobs + holds
    }

    /** 지난 일: finished tasks (with what hangs under them) passing [filter], by day, newest first. */
    fun past(n: Nested, filter: PastFilter, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<DayGroups.Group<TaskNode>> {
        val done = n.roots.filter { !it.live }.filter { node ->
            when (filter) {
                PastFilter.ALL -> true
                PastFilter.DONE -> node.task.status == AgentTask.STATUS_DONE && !node.problem
                PastFilter.PROBLEMS -> node.problem
                PastFilter.AUTOMATIC -> automatic(node.task)
            }
        }
        return DayGroups.group(done, today) { DayGroups.dayOf(it.task, zone) }
    }

    /** The browser tab a task works in: one the server ties to it, or (for a job's run) the job's own tab. */
    fun tabFor(task: AgentTask, tabs: List<BrowserTab>, workers: List<Worker> = emptyList()): String? =
        tabs.firstOrNull { it.taskId == task.id && it.status != "ended" }?.id
            ?: task.workerId?.let { id -> workers.firstOrNull { it.id == id }?.tab }

    /** A job's tab, when it works in a browser: only while the job is going: a finished job's tab is closed. */
    fun tabFor(worker: Worker): String? = worker.tab?.takeIf {
        it.isNotBlank() && (worker.kind == "browser" || worker.thumbnail != null) && worker.status in JOB_GOING
    }

    /** How far a job is through its step budget (0..1), or null when the server gave no budget. */
    fun progress(worker: Worker): Float? = if (worker.maxSteps <= 0) null else (worker.steps.toFloat() / worker.maxSteps).coerceIn(0f, 1f)
}

/** 다가오는 일: the schedules as an agenda, by the day of their next run. */
object Agenda {
    data class Entry(val schedule: Schedule, val at: Double?)
    data class Day(val date: LocalDate, val entries: List<Entry>)

    /** [days] in date order with their runs in time order; [unscheduled]: on but with no next run; [paused]: switched off or paused. */
    data class Plan(val days: List<Day>, val unscheduled: List<Schedule>, val paused: List<Schedule>) {
        val empty: Boolean get() = days.isEmpty() && unscheduled.isEmpty() && paused.isEmpty()
    }

    fun build(schedules: List<Schedule>, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val on = schedules.filter { it.enabled && !it.paused && it.archivedAt == null }
        val timed = on.mapNotNull { s -> ScheduleTimes.nextRunSeconds(s)?.let { Entry(s, it) } }
        val days = timed
            .groupBy { Instant.ofEpochMilli((it.at!! * 1000).toLong()).atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (d, es) -> Day(d, es.sortedWith(compareBy<Entry> { it.at }.thenBy { it.schedule.name })) }
        val unscheduled = on.filter { s -> timed.none { it.schedule.id == s.id } }
        // paused by a run first (they want attention), then the ones switched off
        val off = schedules.filter { (!it.enabled || it.paused) && it.archivedAt == null }.sortedByDescending { it.paused }
        return Plan(days, unscheduled, off)
    }

    /** "오늘" / "내일" / a date, for a day's header. */
    enum class When { TODAY, TOMORROW, LATER }

    fun whenOf(date: LocalDate, today: LocalDate): When = when (date) {
        today -> When.TODAY
        today.plusDays(1) -> When.TOMORROW
        else -> When.LATER
    }
}

/** 지켜보는 중: the watchers, those that need a look (an error, a recent hit) first, then the ones on, then the ones off. */
object Watching {
    fun order(watchers: List<chat.kelo.app.agent.Watcher>): List<chat.kelo.app.agent.Watcher> =
        watchers.sortedWith(compareBy<chat.kelo.app.agent.Watcher> { rank(it) }.thenByDescending { it.lastFiredAt ?: 0.0 }.thenBy { it.name })

    fun rank(w: chat.kelo.app.agent.Watcher): Int = when (w.state) {
        "error" -> 0
        "watching" -> 1
        "done" -> 2
        else -> 3
    }
}

/**
 * The red badge on the 작업 tab and the red dot on the header's agent: only while something waits for the owner to act. That is
 * approvals waiting, open follow-ups (a question to answer, a login asked for, a job that needs input, an approval that ran out and
 * can be tried again) and secure entries the agent asked for. Finished work and notifications nobody has looked at get no badge (the
 * 작업 page marks its own unseen rows). Handled items leave the server's counts, so the badge goes on the next status poll.
 */
object WorkBadge {
    data class Shown(val count: Int)

    fun waiting(s: AgentStatus?): Int = if (s == null) 0 else s.pendingApprovals + s.openFollowups + s.secretRequests

    fun of(s: AgentStatus?): Shown? = waiting(s).takeIf { it > 0 }?.let { Shown(it) }

    /** One kind of thing that waits and how many, for the words a screen reader says ("1 key waiting to be entered"). */
    data class Part(val kind: String, val count: Int)

    const val APPROVALS = "approvals"
    const val FOLLOWUPS = "followups"
    const val KEYS = "key"
    const val LOGINS = "login"
    const val CARDS = "card"
    const val PINS = "payment_pin"
    /** Secure entries an older server does not sort by kind */
    const val ENTRIES = "entries"

    /** What waits, by kind, in this order: approvals, follow-ups, then secure entries (keys, logins, cards, payment PINs). */
    fun parts(s: AgentStatus?): List<Part> {
        if (s == null) return emptyList()
        val out = ArrayList<Part>()
        if (s.pendingApprovals > 0) out += Part(APPROVALS, s.pendingApprovals)
        if (s.openFollowups > 0) out += Part(FOLLOWUPS, s.openFollowups)
        var sorted = 0
        for (k in listOf(KEYS, LOGINS, CARDS, PINS)) {
            val n = s.secretKinds[k] ?: 0
            if (n > 0) { out += Part(k, n); sorted += n }
        }
        if (s.secretRequests > sorted) out += Part(ENTRIES, s.secretRequests - sorted)
        return out
    }
}

/**
 * A jump to one section of the timeline (a notification asked for it): it waits until that section's data has been answered (a
 * section that is still loading is not in the list yet, so a jump then would find nothing), then happens once.
 */
object SectionJump {
    /** [loaded]: the sections whose lists have been answered (found, or truly absent); true when the jump to [key] can be tried now. */
    fun ready(key: String, tasks: Boolean, perms: Boolean, schedules: Boolean, watchers: Boolean): Boolean = when (key) {
        SECTION_ASKS -> tasks && perms
        SECTION_UPCOMING -> tasks && schedules
        SECTION_WATCH -> tasks && watchers
        else -> tasks
    }
}
