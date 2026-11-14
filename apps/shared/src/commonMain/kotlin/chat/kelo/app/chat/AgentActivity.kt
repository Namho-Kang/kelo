package chat.kelo.app.chat

import chat.kelo.app.ui.character.KeloMood
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow

/** GET /api/agent/status: only what the header uses (the agent screen of a later stage reads the rest). */
@Serializable
data class AgentStatus(
    val busy: Boolean = false,
    val status: String? = null,
    val title: String? = null,
    @SerialName("pending_approvals") val pendingApprovals: Int = 0,
    /** Follow-ups waiting for the owner (FOLLOW-UPS API: a question, a login, a job needing input, an approval to try again) */
    @SerialName("open_followups") val openFollowups: Int = 0,
    @SerialName("agent_name") val agentName: String? = null,
    /** The profile's tone ("friendly" | "polite"): a change made in the chat or on another device shows without a reload; null = older server */
    val tone: String? = null,
    @SerialName("unseen_tasks") val unseenTasks: Int = 0,
    @SerialName("unseen_notifications") val unseenNotifications: Int = 0,
    @SerialName("avatar_version") val avatarVersion: Long = -1,
    /** Secure entries the agent asked for that wait for the owner (a key, a login, a card): the chat asks for the list when it changes */
    @SerialName("secret_requests") val secretRequests: Int = 0,
    /** The same by kind ("key", "login", "card", "payment_pin"), so the badge can say what waits; empty from an older server */
    @SerialName("secret_request_kinds") val secretKinds: Map<String, Int> = emptyMap(),
    /** Changes when a briefing card already in the chat changes (its hours line answered on another device): the chat asks for the
     *  cards again ([ChatReducer.withBriefings]); null = an older server */
    @SerialName("briefing_rev") val briefingRev: String? = null,
    /** Keyguard: the server holds no key until an owner phone unlocks it ("Kelo is locked"); false from an older server */
    val locked: Boolean = false,
)

/**
 * What the agent is doing right now, for the line in the header's name pill. While the chat follows a reply, the reply's own events
 * say it ([turnLine]: the newest tool label or browser status); otherwise GET /api/agent/status, polled every few seconds in the
 * foreground, covers work the chat is not following (scheduled or background runs).
 */
class AgentActivity {
    val following = MutableStateFlow(false)
    val turnLine = MutableStateFlow<String?>(null)
    val polledLine = MutableStateFlow<String?>(null)
    /** The newest GET /api/agent/status answer (the agent screen and the header's dot read it) */
    val status = MutableStateFlow<AgentStatus?>(null)
    /** What kind of work the followed reply is doing now ([AgentActivityRules.turnKind]) */
    val turnKind = MutableStateFlow(TurnKind.Thinking)
    /** A run the chat is not following is using the browser ([AgentActivityRules.polledBrowsing]) */
    val polledBrowsing = MutableStateFlow(false)
    /** The server could not be reached on the last polls ([AgentActivityRules.OFFLINE_AFTER] network failures in a row) */
    val offline = MutableStateFlow(false)
    /** A job is stuck on the user ([AgentActivityRules.needsUser]) */
    val stuck = MutableStateFlow(false)
    /** Bumped when a screen learns that background work changed under it (the live view found its tab gone): the Work page and the
     *  job page load again at once instead of at their next poll. */
    val workChanged = MutableStateFlow(0)
    /** The chat's microphone is listening */
    val listening = MutableStateFlow(false)
    /** When work last finished (SystemClock.elapsedRealtime, 0 = not since launch): the character cheers for [AgentActivityRules.DONE_MS] */
    val doneAt = MutableStateFlow(0L)
    /**
     * When the owner or the agent last did something (the same clock as [doneAt], 0 = nothing since launch): the app opened, a
     * message typed, sent or come in, a reply, work, an approval or the microphone. At night the character falls asleep only
     * [AgentActivityRules.SLEEP_AFTER_IDLE_MS] after it.
     */
    val activeAt = MutableStateFlow(0L)
    /** The owner's contact hours (GET /api/proactive); null = not known, the night is then 23:00–07:00 */
    val sleepHours = MutableStateFlow<SleepHours?>(null)

    /**
     * A secure entry of [kind] was answered here (saved or cancelled): the badge counts it out at once instead of at the next poll
     * (the poll then says the same).
     */
    fun entryClosed(kind: String) {
        val s = status.value ?: return
        if (s.secretRequests <= 0) return
        val left = (s.secretKinds[kind] ?: 0) - 1
        status.value = s.copy(secretRequests = s.secretRequests - 1,
            secretKinds = if (left > 0) s.secretKinds + (kind to left) else s.secretKinds - kind)
    }

    /** Something happened at [now] (same clock as [activeAt]); at most one change a second, so typing does not redraw the header per key. */
    fun touch(now: Long) {
        val last = activeAt.value
        if (last <= 0L || now < last || now - last >= TOUCH_STEP_MS) activeAt.value = now.coerceAtLeast(1L)
    }

    private companion object {
        const val TOUCH_STEP_MS = 1_000L
    }
}

/**
 * The contact hours of GET /api/proactive, only what the character's night needs: the owner's weekday waking window (in [timezone])
 * and where it came from (default | manual | learned), the weekend and holiday window ([weekendSource] "default" = it follows the
 * weekdays), and the day type of the next days ([dayTypes], "YYYY-MM-DD" -> weekday | weekend, holidays already counted by the server;
 * an older server sends none, then Saturday and Sunday are the weekend).
 */
@Serializable
data class SleepHours(
    @SerialName("wake_start") val wakeStart: String = "",
    @SerialName("wake_end") val wakeEnd: String = "",
    @SerialName("hours_source") val hoursSource: String = "default",
    val timezone: String = "",
    @SerialName("weekend_start") val weekendStart: String = "",
    @SerialName("weekend_end") val weekendEnd: String = "",
    @SerialName("weekend_source") val weekendSource: String = "default",
    @SerialName("day_types") val dayTypes: Map<String, String> = emptyMap(),
)

/** What a followed reply is doing: writing (or between steps), in the browser, reading, or using another tool. */
enum class TurnKind { Thinking, Browser, Reading, Tool }

/** GET /api/tasks?limit=1: the newest task, to tell a finished run from a failed one nobody has looked at. */
@Serializable
data class TasksBrief(val items: List<TaskBrief> = emptyList())

@Serializable
data class TaskBrief(val id: Long = 0, val status: String = "", val seen: Boolean = true)

/** GET /api/workers?status=needs_input: background jobs waiting for the user (a login, a choice, a code). */
@Serializable
data class WorkersBrief(val items: List<WorkerBrief> = emptyList())

@Serializable
data class WorkerBrief(val id: String = "", val status: String = "", @SerialName("updated_at") val updatedAt: Double? = null)

/** GET /api/browser/sessions?thumbnails=false, only what tells whether the agent is at work in a tab. */
@Serializable
data class BrowserTabsBrief(val items: List<BrowserTabBrief> = emptyList())

@Serializable
data class BrowserTabBrief(
    val id: String = "",
    val status: String = "",
    @SerialName("last_activity") val lastActivity: Double? = null,
    @SerialName("task_id") val taskId: Long? = null,
)

object AgentActivityRules {
    const val POLL_MS = 3_000L

    /** The line to show: the followed reply's own, else the polled one. */
    fun shown(following: Boolean, turnLine: String?, polled: String?): String? =
        (if (following) turnLine else polled)?.trim()?.ifEmpty { null }

    /** The status endpoint's line: only while busy; its live status, else the task's title. */
    fun line(s: AgentStatus): String? = if (!s.busy) null else (s.status?.ifBlank { null } ?: s.title?.ifBlank { null })

    /**
     * The followed reply's line after [ev]: a visible tool's label, the browser's status, an approval's label; writing the answer
     * clears it; anything else keeps it.
     */
    fun next(current: String?, ev: TurnEvent): String? = when (ev) {
        is TurnEvent.ToolStarted -> if (ev.hidden) current else ev.label.ifEmpty { null } ?: current
        is TurnEvent.Browser -> ev.status.ifEmpty { null } ?: current
        is TurnEvent.ApprovalRequest -> ev.label.ifEmpty { null } ?: current
        is TurnEvent.Text, is TurnEvent.Done, is TurnEvent.Error -> null
        else -> current
    }

    /** Tools that read (files, documents, mail, memory, search): the character reads a book meanwhile. */
    private val READING_TOOLS = setOf(
        "read_file", "list_files", "search_files", "extract_document", "email_list", "email_read", "journal_search", "search_web",
        "web_search", "view_image", "workroom_files", "search_skills", "connector_get", "list_product_images",
    )

    /** The kind of a tool by its name. */
    fun toolKind(name: String): TurnKind = when {
        name.startsWith("browser_") -> TurnKind.Browser
        name in READING_TOOLS || name.startsWith("memory") -> TurnKind.Reading
        else -> TurnKind.Tool
    }

    /**
     * What the followed reply is doing after [ev]: a browser picture says browser; a visible tool starting says its kind
     * ([toolKind]); writing the answer (or the end) says thinking; anything else (a tool's result, an approval, a hidden tool)
     * keeps the last one, so back-to-back browser steps do not flicker.
     */
    fun turnKind(current: TurnKind, ev: TurnEvent): TurnKind = when (ev) {
        is TurnEvent.Browser -> TurnKind.Browser
        is TurnEvent.ToolStarted -> if (ev.hidden) current else toolKind(ev.name)
        is TurnEvent.Text, is TurnEvent.Done, is TurnEvent.Error -> TurnKind.Thinking
        else -> current
    }

    /** How recent a tab's last action must be (seconds) to count as the agent using the browser now. */
    const val BROWSER_RECENT_S = 30.0

    /**
     * Whether a run the chat is not following is using the browser: a tab a running task works in ("working") whose last action
     * is recent ([BROWSER_RECENT_S] against [nowS], the phone's clock) or moved since the previous poll ([before], by tab id). A
     * tab only counts while [s] says the agent is busy.
     */
    fun polledBrowsing(s: AgentStatus?, tabs: List<BrowserTabBrief>, before: Map<String, Double>, nowS: Double): Boolean {
        if (s == null || !s.busy) return false
        return tabs.any { t ->
            val at = t.lastActivity
            t.status == "working" && at != null && (nowS - at <= BROWSER_RECENT_S || (before[t.id] ?: at) < at)
        }
    }

    /** Network failures in a row before the character shows the server as unreachable (one blip is not enough). */
    const val OFFLINE_AFTER = 2
    /** How long the character cheers after work finished (two of its 0.8 s jumps). */
    const val DONE_MS = 1_600L
    /** A job waiting for the user counts this long after its last change (older ones stay in 작업, not on the character). */
    const val STUCK_FOR_S = 12 * 3600.0

    /**
     * A job is stuck on the user: the newest task failed and nobody has looked at it yet, or a background job has been waiting
     * for the user (a login, a choice, a code) since less than [STUCK_FOR_S] ago (against [nowS]).
     */
    fun needsUser(newest: TaskBrief?, waiting: List<WorkerBrief>, nowS: Double): Boolean =
        (newest != null && newest.status == "failed" && !newest.seen) ||
            waiting.any { it.status == "needs_input" && (it.updatedAt ?: 0.0) >= nowS - STUCK_FOR_S }

    /** Work just finished (the agent was busy at the previous poll and is not now) and the newest task went well. */
    fun finished(before: AgentStatus?, now: AgentStatus, newest: TaskBrief?): Boolean =
        before != null && before.busy && !now.busy && newest != null && newest.status == "done"

    /**
     * How long nothing must happen (no message typed, sent or come in, no reply, no work, approval or listening) before the
     * character may fall asleep at night; until then it rests awake.
     */
    const val SLEEP_AFTER_IDLE_MS = 5 * 60_000L

    /** The late night (23:00–07:00) used when the owner's contact hours are not known. */
    fun isNight(hour: Int): Boolean = hour >= 23 || hour < 7

    /** "07:30" -> minutes of the day, or null when it is not a time. */
    fun minutesOf(text: String?): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(text?.trim().orEmpty()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    /**
     * The owner's waking window as (start, end) minutes of the day, when the server knows it: hours the owner set or Kelo learned
     * ("manual" / "learned"). The server's untouched default (07:00–22:00, "default") is not the owner's, so it gives null.
     */
    fun wakeWindow(h: SleepHours?): Pair<Int, Int>? {
        if (h == null || h.hoursSource !in setOf("manual", "learned")) return null
        val a = minutesOf(h.wakeStart) ?: return null
        val b = minutesOf(h.wakeEnd) ?: return null
        return a to b
    }

    /**
     * Whether [minuteOfDay] is night: outside the owner's waking window when known ([wakeWindow]; its end not included, a window
     * past midnight such as 10:00–02:00 works, equal ends mean awake all day, as the server counts it), else 23:00–07:00.
     */
    fun isNight(minuteOfDay: Int, hours: SleepHours?): Boolean {
        val (a, b) = wakeWindow(hours) ?: return isNight(minuteOfDay / 60)
        if (a == b) return false
        val awake = if (a < b) minuteOfDay in a until b else (minuteOfDay >= a || minuteOfDay < b)
        return !awake
    }

    /** A late night belongs to the evening before: a moment counts for the day it was this many hours earlier (as the server does). */
    const val DAY_CUT_HOURS = 4L

    /** weekday | weekend of [date]: the server's [SleepHours.dayTypes] (holidays included), else Saturday and Sunday. */
    fun dayType(hours: SleepHours?, date: java.time.LocalDate): String =
        hours?.dayTypes?.get(date.toString()) ?: if (date.dayOfWeek.value >= 6) "weekend" else "weekday"

    /** The hours that apply on [date]: the weekend window on a weekend or holiday when it has its own, else the weekday one. */
    fun hoursFor(hours: SleepHours?, date: java.time.LocalDate): SleepHours? {
        if (hours == null || dayType(hours, date) != "weekend" || hours.weekendSource !in setOf("manual", "learned")) return hours
        if (minutesOf(hours.weekendStart) == null || minutesOf(hours.weekendEnd) == null) return hours
        return hours.copy(wakeStart = hours.weekendStart, wakeEnd = hours.weekendEnd, hoursSource = hours.weekendSource)
    }

    /**
     * Night at [epochMs]: the owner's window for that day's type ([hoursFor]; the day of a moment is the one [DAY_CUT_HOURS] earlier, so
     * 01:00 on a Saturday is still Friday night) read in their time zone ([SleepHours.timezone]); anything else in [local].
     */
    fun nightAt(epochMs: Long, local: java.time.ZoneId, hours: SleepHours?): Boolean {
        val known = wakeWindow(hours) != null || (hours != null && hours.weekendSource in setOf("manual", "learned"))
        val zone = if (!known) local else runCatching { java.time.ZoneId.of(hours!!.timezone) }.getOrDefault(local)
        val t = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = t.minusHours(DAY_CUT_HOURS).toLocalDate()
        return isNight(t.hour * 60 + t.minute, hoursFor(hours, day))
    }

    /** ms since the last activity ([AgentActivity.activeAt], 0 = none) at [now] on the same clock; null = nothing since launch. */
    fun idleMs(now: Long, activeAt: Long): Long? = if (activeAt <= 0L) null else (now - activeAt).coerceAtLeast(0L)

    /** Everything the character's mood is decided from. */
    data class MoodInput(
        val offline: Boolean = false,
        val status: AgentStatus? = null,
        val stuck: Boolean = false,
        val listening: Boolean = false,
        val following: Boolean = false,
        val turnKind: TurnKind = TurnKind.Thinking,
        val polledBrowsing: Boolean = false,
        /** ms since work finished (null = not recently) */
        val sinceDoneMs: Long? = null,
        val night: Boolean = false,
        /** ms since the owner or the agent last did something ([idleMs]; null = nothing since launch) */
        val idleMs: Long? = null,
    )

    /**
     * The character's mood, the first that applies: unreachable server, an approval waiting, a job stuck on the user, the
     * microphone listening, then the work itself (the followed reply's kind; else a polled run in the browser or at work), the
     * cheer after finishing, sleep at night once nothing has happened for [SLEEP_AFTER_IDLE_MS], else rest.
     */
    fun mood(m: MoodInput): KeloMood {
        val busy = m.following || m.status?.busy == true
        return when {
            m.offline -> KeloMood.Offline
            (m.status?.pendingApprovals ?: 0) > 0 -> KeloMood.Approval
            m.stuck -> KeloMood.Error
            m.listening -> KeloMood.Listening
            m.following -> when (m.turnKind) {
                TurnKind.Browser -> KeloMood.Browser
                TurnKind.Reading -> KeloMood.Reading
                TurnKind.Tool -> KeloMood.Working
                TurnKind.Thinking -> KeloMood.Thinking
            }
            busy -> if (m.polledBrowsing) KeloMood.Browser else KeloMood.Working
            m.sinceDoneMs != null && m.sinceDoneMs < DONE_MS -> KeloMood.Done
            m.night && (m.idleMs == null || m.idleMs >= SLEEP_AFTER_IDLE_MS) -> KeloMood.Sleeping
            else -> KeloMood.Idle
        }
    }
}
