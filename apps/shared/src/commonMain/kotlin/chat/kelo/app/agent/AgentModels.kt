package chat.kelo.app.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The work data, as the server sends it (src/assistant/server.py, "AGENT SCREEN API" and "WORKERS API" above serialize()). Unknown
 * fields are ignored and missing ones take the defaults here, so an older or newer server never breaks the screen.
 */

/** One reply (typed in chat, an automatic run, a background task). */
@Serializable
data class AgentTask(
    val id: Long,
    @SerialName("turn_id") val turnId: String = "",
    val title: String = "",
    /** Running | waiting_approval | done | failed | stopped | interrupted (a restart cut it off) | waiting_model (the AI model is down) */
    val status: String = "",
    val result: String = "",
    /** Chat | api | schedule:<id> | background */
    val source: String = "",
    @SerialName("schedule_id") val scheduleId: Long? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("finished_at") val finishedAt: Double? = null,
    val seen: Boolean = true,
    /** "YYYY-MM-DD" in the profile's zone */
    val day: String = "",
    @SerialName("live_status") val liveStatus: String? = null,
    /** The task that handed this one off as a background job (null for ordinary replies) */
    @SerialName("parent_id") val parentId: Long? = null,
    /** The background job this task is a run of (null for ordinary replies) */
    @SerialName("worker_id") val workerId: String? = null,
) {
    val running: Boolean get() = status == STATUS_RUNNING || status == STATUS_WAITING
    val failed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_WAITING = "waiting_approval"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_WAITING_MODEL = "waiting_model"
    }
}

@Serializable
data class TaskPage(
    val items: List<AgentTask> = emptyList(),
    val unseen: Int = 0,
    @SerialName("next_before") val nextBefore: Long? = null,
)

/** GET /api/tasks/{id}: the task with its answer and the approvals it asked for. */
@Serializable
data class TaskDetail(
    val id: Long,
    val title: String = "",
    val status: String = "",
    val result: String = "",
    val source: String = "",
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("finished_at") val finishedAt: Double? = null,
    @SerialName("live_status") val liveStatus: String? = null,
    val answer: String = "",
    val error: String = "",
    val approvals: List<ApprovalRecord> = emptyList(),
) {
    val running: Boolean get() = status == AgentTask.STATUS_RUNNING || status == AgentTask.STATUS_WAITING
}

/** An approval as the history keeps it (never its arguments). */
@Serializable
data class ApprovalRecord(
    val id: String,
    @SerialName("task_id") val taskId: Long? = null,
    val tool: String = "",
    val label: String = "",
    val site: String = "",
    val detail: String = "",
    /** Pending | allowed_once | allowed_always | allowed_by_rule | denied | expired | cancelled */
    val decision: String = "",
    @SerialName("asked_at") val askedAt: Double = 0.0,
    val unattended: Boolean = false,
)

@Serializable
data class ApprovalHistory(
    val items: List<ApprovalRecord> = emptyList(),
    @SerialName("next_before") val nextBefore: Double? = null,
)

/** A standing "allow on this site" rule. kind: browser_action | login. */
@Serializable
data class ApprovalRule(
    val id: Long,
    val kind: String = "",
    @SerialName("kind_label") val kindLabel: String = "",
    val site: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    val uses: Int = 0,
    @SerialName("last_used") val lastUsed: Double? = null,
)

@Serializable
data class RuleList(val items: List<ApprovalRule> = emptyList())

/**
 * One Allow for a repeated step of one running task ("체험단 신청 on musinsa.com, up to 30"): those steps on that site go through
 * without a card until [maxCount] are used or the task ends. kind: apply | send | delete. Never payments or account changes.
 */
@Serializable
data class TaskPermission(
    val id: String,
    val site: String = "",
    val kind: String = "",
    val action: String = "",
    @SerialName("max_count") val maxCount: Int = 0,
    val used: Int = 0,
    @SerialName("task_id") val taskId: Long? = null,
    @SerialName("expires_at") val expiresAt: Double = 0.0,
)

@Serializable
data class TaskPermissionList(val items: List<TaskPermission> = emptyList())

@Serializable
data class LastRun(@SerialName("task_id") val taskId: Long? = null, val status: String = "", val at: Double? = null)

/** A saved automatic run. kind: cron | window (cron ± window_minutes) | interval (every_minutes) | once. */
@Serializable
data class Schedule(
    val id: Long,
    val name: String = "",
    val prompt: String = "",
    val kind: String = "cron",
    val cron: String? = null,
    @SerialName("run_at") val runAt: String? = null,
    @SerialName("every_minutes") val everyMinutes: Int? = null,
    /** The first local day a repeat may run (YYYY-MM-DD), when it has one */
    @SerialName("starts_on") val startsOn: String? = null,
    /** The last local day a repeat may run, when it has one */
    @SerialName("ends_on") val endsOn: String? = null,
    val timezone: String = "",
    val enabled: Boolean = true,
    val description: String = "",
    @SerialName("next_run") val nextRun: String? = null,
    @SerialName("next_run_ts") val nextRunTs: Double? = null,
    @SerialName("created_by") val createdBy: String = "user",
    @SerialName("last_run") val lastRun: LastRun? = null,
    @SerialName("disabled_reason") val disabledReason: String? = null,
    val running: Boolean = false,
    /** A run (or the user) paused it for a lasting blocker: off until resumed */
    val paused: Boolean = false,
    @SerialName("paused_reason") val pausedReason: String? = null,
    @SerialName("archived_at") val archivedAt: Double? = null,
    @SerialName("purge_at") val purgeAt: Double? = null,
    @SerialName("goal_id") val goalId: Long? = null,
)

/** One run of a schedule (GET /api/schedules/{id}/runs). status: running | done | quiet | failed | stopped | skipped | paused */
@Serializable
data class ScheduleRun(
    val id: Long = 0,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("finished_at") val finishedAt: Double? = null,
    val status: String = "",
    val result: String = "",
    @SerialName("task_id") val taskId: Long? = null,
    val error: String? = null,
)

@Serializable
data class ScheduleRuns(val items: List<ScheduleRun> = emptyList())

@Serializable
data class ScheduleArchive(val items: List<Schedule> = emptyList(), @SerialName("keep_days") val keepDays: Int = 30)

/** A watcher's last check without the agent (POST /api/watchers/{id}/dry-run). */
@Serializable
data class DryRun(
    val at: Double? = null, val ok: Boolean = false, val value: String? = null,
    val detail: String? = null, val error: String? = null, val url: String? = null, val title: String? = null,
)

/** A cheap condition check that wakes the agent only when it holds (GET /api/watchers). state: off | watching | error | done */
@Serializable
data class Watcher(
    val id: Long,
    val name: String = "",
    val kind: String = "page",
    val url: String? = null,
    val mode: String = "",
    val note: String? = null,
    @SerialName("every_minutes") val everyMinutes: Int = 0,
    val enabled: Boolean = false,
    val once: Boolean = false,
    val state: String = "off",
    val description: String = "",
    @SerialName("last_value") val lastValue: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: Double? = null,
    @SerialName("next_check_at") val nextCheckAt: Double? = null,
    @SerialName("last_fired_at") val lastFiredAt: Double? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("disabled_reason") val disabledReason: String? = null,
    @SerialName("dry_run") val dryRun: DryRun? = null,
    @SerialName("can_enable") val canEnable: Boolean = false,
    @SerialName("created_by") val createdBy: String = "user",
    val buy: BuyReady? = null,
)

/** A watcher that may buy at its hit (docs/instant-buy.md): its product page kept open and signed in, and what is not ready. */
@Serializable
data class BuyReady(
    val armed: Boolean = false,
    @SerialName("last_kept_at") val lastKeptAt: Double? = null,
    @SerialName("signed_in") val signedIn: Boolean? = null,
    val problems: List<BuyProblem> = emptyList(),
    val remembered: Boolean = false,
)

@Serializable
data class BuyProblem(val code: String = "", val text: String = "")

@Serializable
data class WatcherList(val items: List<Watcher> = emptyList(), val limit: Int = 0)

@Serializable
data class WatcherEvent(val id: Long = 0, val at: Double = 0.0, val kind: String = "", val detail: String? = null, val value: String? = null)

@Serializable
data class WatcherEvents(val items: List<WatcherEvent> = emptyList())

@Serializable
data class DryRunAnswer(val result: DryRun? = null, val watcher: Watcher? = null)

@Serializable
data class ScheduleList(val items: List<Schedule> = emptyList(), val timezone: String = "")

/** One browser tab the agent keeps (its own, a phone's, a schedule's, a background task's). */
@Serializable
data class BrowserTab(
    val id: String,
    val label: String = "",
    val kind: String = "",
    val title: String = "",
    val url: String = "",
    val host: String = "",
    /** Working | idle | failed */
    val status: String = "idle",
    @SerialName("status_text") val statusText: String = "",
    @SerialName("last_activity") val lastActivity: Double? = null,
    @SerialName("task_id") val taskId: Long? = null,
    val thumbnail: String? = null,
)

@Serializable
data class BrowserTabs(val available: Boolean = false, val items: List<BrowserTab> = emptyList())

/** A background job the agent handed off (GET /api/workers). */
@Serializable
data class Worker(
    val id: String,
    /** Browser | research | general */
    val kind: String = "",
    val title: String = "",
    /** Running | needs_input | blocked | done | failed | stopped | interrupted */
    val status: String = "",
    @SerialName("parent_task_id") val parentTaskId: Long? = null,
    /** Its latest run */
    @SerialName("task_id") val taskId: Long? = null,
    @SerialName("turn_id") val turnId: String? = null,
    /** Its browser tab ("task:<id>") */
    val tab: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
    @SerialName("finished_at") val finishedAt: Double? = null,
    val steps: Int = 0,
    @SerialName("max_steps") val maxSteps: Int = 0,
    /** Login | control | user (paused only) */
    @SerialName("waiting_for") val waitingFor: String? = null,
    val login: String? = null,
    val reason: String? = null,
    @SerialName("status_text") val statusText: String? = null,
    @SerialName("last_url") val lastUrl: String? = null,
    /** A path on the server (the tab's picture), or null */
    val thumbnail: String? = null,
) {
    val running: Boolean get() = status == RUNNING
    /** Stopped and waiting for the user (a login, the tab handed back, an answer) */
    val needsYou: Boolean get() = status == NEEDS_INPUT || status == BLOCKED
    val active: Boolean get() = running || needsYou || status == INTERRUPTED
    val canResume: Boolean get() = status in RESUMABLE

    companion object {
        const val RUNNING = "running"
        const val NEEDS_INPUT = "needs_input"
        const val BLOCKED = "blocked"
        const val DONE = "done"
        const val FAILED = "failed"
        const val STOPPED = "stopped"
        const val INTERRUPTED = "interrupted"
        val RESUMABLE = setOf(NEEDS_INPUT, BLOCKED, FAILED, STOPPED, INTERRUPTED)
    }
}

@Serializable
data class WorkerList(val items: List<Worker> = emptyList(), val running: Int = 0, val limit: Int = 0)

@Serializable
data class WorkerReport(val status: String = "", val summary: String = "", val details: String = "", val at: Double? = null)

@Serializable
data class WorkerLine(val at: Double = 0.0, val text: String = "")

/** GET /api/workers/{id}: the job with what it was asked, its last steps and its report. */
@Serializable
data class WorkerDetail(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val status: String = "",
    @SerialName("parent_task_id") val parentTaskId: Long? = null,
    @SerialName("task_id") val taskId: Long? = null,
    val tab: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("finished_at") val finishedAt: Double? = null,
    val steps: Int = 0,
    @SerialName("max_steps") val maxSteps: Int = 0,
    @SerialName("waiting_for") val waitingFor: String? = null,
    val login: String? = null,
    val reason: String? = null,
    @SerialName("status_text") val statusText: String? = null,
    @SerialName("last_url") val lastUrl: String? = null,
    val thumbnail: String? = null,
    val task: String = "",
    val url: String? = null,
    val files: List<String> = emptyList(),
    val report: WorkerReport? = null,
    val lines: List<WorkerLine> = emptyList(),
    val error: String? = null,
) {
    fun brief() = Worker(
        id, kind, title, status, parentTaskId, taskId, null, tab, startedAt, 0.0, finishedAt, steps, maxSteps, waitingFor, login,
        reason, statusText, lastUrl, thumbnail,
    )
}

@Serializable
data class JournalEntry(val id: Long, val date: String = "", val title: String = "", val summary: String = "", @SerialName("created_at") val createdAt: Double = 0.0)

/** A day the journal has not been written for yet (pending) or could not be (failed). */
@Serializable
data class JournalGap(val date: String = "", val status: String = "", val reason: String = "")

@Serializable
data class JournalMonth(val month: String = "", val items: List<JournalEntry> = emptyList(), val pending: List<JournalGap> = emptyList())

@Serializable
data class AvatarSaved(@SerialName("avatar_version") val avatarVersion: Long = 0)

/** GET /api/notifications items. */
@Serializable
data class ServerNotification(
    val id: Long,
    val type: String = "",
    val title: String = "",
    val body: String = "",
    @SerialName("task_id") val taskId: Long? = null,
    @SerialName("approval_id") val approvalId: String? = null,
    @SerialName("schedule_id") val scheduleId: Long? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
    val seen: Boolean = false,
    /** A follow-up: the agent's message that asks again (the chat opens at it) */
    @SerialName("message_id") val messageId: Long? = null,
    /** The provider it is about ("" for most): a cli_update names the CLI's provider, so My → Model opens at that CLI's box */
    val provider: String = "",
    /** A card of a task marked urgent at setup: time matters (its own louder channel; the body says by when and what is missed) */
    val urgent: Boolean = false,
)

@Serializable
data class NotificationPage(
    val items: List<ServerNotification> = emptyList(),
    val unseen: Int = 0,
    @SerialName("last_id") val lastId: Long = 0,
)

/** One night's short note of the look-back (reflection/YYYY-MM-DD.md). */
@Serializable
data class LookbackNote(val date: String = "", val text: String = "")

/**
 * The last nightly look-back: done | skipped | failed | pending, why ([code]: no_messages, switched_off, in_use, no_model, model_error,
 * bad_output; "" when done) and what it did (counts). [reason] is the server's log text (English, model notes): never shown.
 */
@Serializable
data class LookbackRun(
    val at: Double? = null,
    val status: String = "",
    val code: String = "",
    val added: Int = 0,
    val updated: Int = 0,
    val dropped: Int = 0,
    val goals: Int = 0,
    val held: Int = 0,
    val reason: String = "",
    val changed: Boolean = false,
)

/** When the last run happened, in words: last night (the small hours of today, or yesterday evening), today, or a date. */
enum class RunWhen { LAST_NIGHT, TODAY, DAY }

/** Which sentence the status line of the last run says; each app maps it to its own text. */
enum class LookbackLine { DONE, NO_CHAT, OFF, BUSY, SKIPPED, FAILED_WHY, FAILED, NO_MODEL }

/** The reason a failed run's sentence names (only with [LookbackLine.FAILED_WHY]). */
enum class LookbackFail { MODEL, ANSWER }

/**
 * The status line of the last run: [line] is the sentence; the app's text for it takes the "when" words (unless [withWhen] is
 * false) and, for [why], that reason's words; [newRules] (done only) adds " · N new rules suggested" or " · no new rules" when 0.
 */
data class RunLine(val line: LookbackLine, val why: LookbackFail? = null, val newRules: Int? = null, val failed: Boolean = false, val withWhen: Boolean = true)

/**
 * One preference the nightly look-back learned ("답은 짧게, 요점부터"). [evidence]: what in the day made it think so; [confidence]
 * 0..1; [status]: proposed (waiting for the user), accepted or rejected.
 */
@Serializable
data class LearnedRule(
    val id: Long,
    val rule: String = "",
    val evidence: String = "",
    val date: String = "",
    val confidence: Double = 0.0,
    val status: String = LearnedRule.PROPOSED,
) {
    companion object {
        const val PROPOSED = "proposed"
        const val ACCEPTED = "accepted"
        const val REJECTED = "rejected"
    }
}

/**
 * GET /api/reflection: each night the agent looks back on the day and proposes short preference rules; accepted ones (and proposed
 * ones it is sure of) shape every reply. [available] is false when the server has no agent files (404).
 */
@Serializable
data class Lookback(
    /** An older server has no switch: it always looks back */
    val enabled: Boolean = true,
    val rules: List<LearnedRule> = emptyList(),
    val notes: List<LookbackNote> = emptyList(),
    @SerialName("last_run") val lastRun: LookbackRun? = null,
    val available: Boolean = true,
    /** The memory pass that keeps the agent's notes about the user current (null from an older server). */
    val memory: MemoryUpkeep? = null,
)

/**
 * The last memory pass that changed something: how many lines it [added] and [tidied] (removed, folded or rewritten), whether the
 * user [undone] it and whether it [canUndo] still. [files] are the server's own file names: never shown, only grouped by [MemoryArea].
 */
@Serializable
data class MemoryPass(
    val at: Double? = null,
    val status: String = "",
    val code: String = "",
    val added: Int = 0,
    val tidied: Int = 0,
    val files: List<String> = emptyList(),
    val trigger: String = "",
    val undone: Boolean = false,
    @SerialName("can_undo") val canUndo: Boolean = false,
)

@Serializable
data class MemoryUpkeep(@SerialName("last_pass") val lastPass: MemoryPass? = null)

/** The lines one file gained and lost in the last pass (GET /api/reflection/memory). */
@Serializable
data class MemoryChange(val file: String = "", val added: List<String> = emptyList(), val removed: List<String> = emptyList())

@Serializable
data class MemoryChanges(
    @SerialName("last_pass") val lastPass: MemoryPass? = null,
    val changes: List<MemoryChange> = emptyList(),
)

/** What a memory file is about, in the user's words (the app never names the files themselves). */
enum class MemoryArea { ABOUT_YOU, TO_REMEMBER, PEOPLE, WORKING_NOTES }

/** The changed lines of one [MemoryArea], several people's pages together. */
data class MemoryAreaChange(val area: MemoryArea, val added: List<String>, val removed: List<String>)

/** The memory pass's small rules, apart from the UI. */
object MemoryPassRules {
    /** The pass line on the Records page: a pass that changed something, or one the user undid. */
    fun shown(pass: MemoryPass?): Boolean = pass != null && pass.status == "done" && (pass.undone || pass.added > 0 || pass.tidied > 0)

    fun area(file: String): MemoryArea = when {
        file == "user.md" -> MemoryArea.ABOUT_YOU
        file == "AGENTS.md" -> MemoryArea.WORKING_NOTES
        file.startsWith("memory/people/") -> MemoryArea.PEOPLE
        else -> MemoryArea.TO_REMEMBER
    }

    /** The changes grouped by area, in the order the sheet shows them; areas without a changed line are left out. */
    fun group(changes: List<MemoryChange>): List<MemoryAreaChange> =
        MemoryArea.entries.mapNotNull { a ->
            val mine = changes.filter { area(it.file) == a }
            val added = mine.flatMap { it.added }.filter { it.isNotBlank() }
            val removed = mine.flatMap { it.removed }.filter { it.isNotBlank() }
            if (added.isEmpty() && removed.isEmpty()) null else MemoryAreaChange(a, added, removed)
        }
}

/** The learned rules in the three parts the page shows. */
data class RuleGroups(val proposed: List<LearnedRule>, val accepted: List<LearnedRule>, val rejected: List<LearnedRule>) {
    val empty: Boolean get() = proposed.isEmpty() && accepted.isEmpty() && rejected.isEmpty()
}

/** The look-back's small rules, apart from the UI. */
object LookbackRules {
    /** The server puts a proposed rule into replies already from this confidence on (reflection.PROMPT_CONFIDENCE). */
    const val IN_USE_CONFIDENCE = 0.8

    /**
     * Proposed ones first (the most certain first, then the newest), then the accepted ones in the server's order, then the rejected
     * ones newest first. Blank rules and unknown statuses are left out.
     */
    fun group(rules: List<LearnedRule>): RuleGroups {
        val shown = rules.filter { it.rule.isNotBlank() }
        return RuleGroups(
            proposed = shown.filter { it.status == LearnedRule.PROPOSED }
                .sortedWith(compareByDescending<LearnedRule> { it.confidence }.thenByDescending { it.date }.thenByDescending { it.id }),
            accepted = shown.filter { it.status == LearnedRule.ACCEPTED },
            rejected = shown.filter { it.status == LearnedRule.REJECTED }.sortedWith(compareByDescending<LearnedRule> { it.date }.thenByDescending { it.id }),
        )
    }

    /** Whether replies already follow it: accepted, or proposed with enough confidence. */
    fun inUse(r: LearnedRule): Boolean = r.status == LearnedRule.ACCEPTED || (r.status == LearnedRule.PROPOSED && r.confidence >= IN_USE_CONFIDENCE)

    /**
     * The groups with the rules the user just decided on still among the proposed ones (shown with "kept" / "discarded" and an
     * undo for a few seconds), in their old place.
     */
    fun group(rules: List<LearnedRule>, justDecided: Set<Long>): RuleGroups =
        group(rules.map { if (it.id in justDecided) it.copy(status = LearnedRule.PROPOSED) else it })

    /** The list with one rule's status changed (shown at once, before the server answers). */
    fun withStatus(rules: List<LearnedRule>, id: Long, status: String): List<LearnedRule> =
        rules.map { if (it.id == id) it.copy(status = status) else it }

    /** The list with the server's copy of one rule in place of the old one. */
    fun replace(rules: List<LearnedRule>, rule: LearnedRule): List<LearnedRule> =
        if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule

    /** How long the "kept" / "discarded" note with its undo stays on a decided rule. */
    const val UNDO_MILLIS = 5_000L

    /** The status line of a run, built only from its structured fields (never the server's free text); null for an unknown status. */
    fun runLine(run: LookbackRun): RunLine? {
        return when (run.status) {
            "done" -> RunLine(LookbackLine.DONE, newRules = run.added.coerceAtLeast(0))
            "skipped" -> RunLine(when (run.code) {
                "no_messages" -> LookbackLine.NO_CHAT
                "switched_off" -> LookbackLine.OFF
                "in_use" -> LookbackLine.BUSY
                else -> LookbackLine.SKIPPED
            })
            "failed" -> when (run.code) {
                "model_error" -> RunLine(LookbackLine.FAILED_WHY, why = LookbackFail.MODEL, failed = true)
                "bad_output" -> RunLine(LookbackLine.FAILED_WHY, why = LookbackFail.ANSWER, failed = true)
                else -> RunLine(LookbackLine.FAILED, failed = true)
            }
            "pending" -> RunLine(LookbackLine.NO_MODEL, withWhen = false)
            else -> null
        }
    }

    /** When [atSeconds] was, seen from [now]: before 6 in the morning today or from 6 in the evening yesterday is "last night". */
    fun runWhen(atSeconds: Double, now: java.time.ZonedDateTime): RunWhen {
        val at = java.time.Instant.ofEpochMilli((atSeconds * 1000).toLong()).atZone(now.zone)
        val day = at.toLocalDate()
        val today = now.toLocalDate()
        return when {
            day == today -> if (at.hour < 6) RunWhen.LAST_NIGHT else RunWhen.TODAY
            day == today.minusDays(1) && at.hour >= 18 -> RunWhen.LAST_NIGHT
            else -> RunWhen.DAY
        }
    }
}
