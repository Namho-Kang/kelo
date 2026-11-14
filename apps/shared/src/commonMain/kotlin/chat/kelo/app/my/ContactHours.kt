package chat.kelo.app.my

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message hours learned from when the owner really uses Kelo (server: active_hours.py, GET /api/proactive "hours_suggestion"):
 * offered on 마이 → 기록 with "이 시간으로 바꾸기" / "지금대로 두기". After "keep", the server asks again at most every 14 days.
 */
@Serializable
data class HoursSuggestion(
    val id: String = "",
    @SerialName("wake_start") val wakeStart: String = "",
    @SerialName("wake_end") val wakeEnd: String = "",
    @SerialName("current_start") val currentStart: String = "",
    @SerialName("current_end") val currentEnd: String = "",
    @SerialName("active_days") val activeDays: Int = 0,
    @SerialName("created_at") val createdAt: Double? = null,
    /** weekday | weekend (weekends and public holidays); an older server sends none: weekday */
    @SerialName("day_type") val dayType: String = "weekday",
)

/** A window learned from the owner's use that differs from the hours in effect (GET /api/proactive "learned"; computed, no model). */
@Serializable
data class LearnedWindow(
    @SerialName("wake_start") val wakeStart: String = "",
    @SerialName("wake_end") val wakeEnd: String = "",
    @SerialName("active_days") val activeDays: Int = 0,
)

/**
 * The message-hours part of GET /api/proactive (what 마이 → 기록 needs; the notifications page reads the rest): the weekday hours
 * ([wakeStart]..[wakeEnd], what older servers send alone) and the weekend and holiday hours ([weekendStart]..[weekendEnd] in effect;
 * [weekendSource] "default" = they follow the weekdays), a suggestion per day type, and the learned windows that differ.
 */
@Serializable
data class ContactHours(
    @SerialName("wake_start") val wakeStart: String = "07:00",
    @SerialName("wake_end") val wakeEnd: String = "22:00",
    @SerialName("auto_hours") val autoHours: Boolean = false,
    /** default | manual | learned: where wake_start..wake_end came from. */
    @SerialName("hours_source") val hoursSource: String = "default",
    @SerialName("hours_suggestion") val suggestion: HoursSuggestion? = null,
    @SerialName("weekend_start") val weekendStart: String = "",
    @SerialName("weekend_end") val weekendEnd: String = "",
    @SerialName("weekend_source") val weekendSource: String = "default",
    @SerialName("weekend_suggestion") val weekendSuggestion: HoursSuggestion? = null,
    val learned: Map<String, LearnedWindow?> = emptyMap(),
    /** Today's day type (the server's; holidays included) */
    @SerialName("day_type") val dayType: String = "weekday",
    /** The server knows weekend hours (an older server does not: the page shows one row) */
    @SerialName("holiday_country") val holidayCountry: String? = null,
)

/** Small rules of the message hours, apart from the UI (shared with the desktop app; the iPhone app has the same in Swift). */
object ContactHoursRules {
    const val LEARNED = "learned"
    /** How long the row says what was chosen before it goes. */
    const val DONE_MILLIS = 2500L

    fun learned(source: String): Boolean = source == LEARNED

    /** "07:30" -> minutes of the day, or null when it is not a time. */
    fun minutes(text: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    /** A window whose end is before its start runs past midnight (10:00–01:00). Equal ends mean the whole day, not past midnight. */
    fun pastMidnight(start: String, end: String): Boolean {
        val a = minutes(start) ?: return false
        val b = minutes(end) ?: return false
        return b < a
    }

    /** A suggestion the page can show: an id, two real times, and different from the hours set now. */
    fun shown(s: HoursSuggestion?): Boolean =
        s != null && s.id.isNotBlank() && minutes(s.wakeStart) != null && minutes(s.wakeEnd) != null &&
            (s.wakeStart != s.currentStart || s.wakeEnd != s.currentEnd)

    const val WEEKDAY = "weekday"
    const val WEEKEND = "weekend"

    /** The open suggestion to show first: the weekday one, else the weekend one (each only when [shown]). */
    fun open(h: ContactHours?): HoursSuggestion? =
        h?.suggestion?.takeIf { shown(it) } ?: h?.weekendSuggestion?.takeIf { shown(it) }?.copy(dayType = WEEKEND)

    /** (start, end, source) of one day type's hours. Weekends without hours of their own show the weekday ones (source "default"). */
    fun window(h: ContactHours, kind: String): Triple<String, String, String> =
        if (kind == WEEKEND && minutes(h.weekendStart) != null && minutes(h.weekendEnd) != null) Triple(h.weekendStart, h.weekendEnd, h.weekendSource)
        else if (kind == WEEKEND) Triple(h.wakeStart, h.wakeEnd, "default")
        else Triple(h.wakeStart, h.wakeEnd, h.hoursSource)

    /** The learned window of [kind] worth an Apply button: real times, different from the hours in effect. */
    fun learnedFor(h: ContactHours, kind: String): LearnedWindow? {
        val l = h.learned[kind] ?: return null
        if (minutes(l.wakeStart) == null || minutes(l.wakeEnd) == null) return null
        val (a, b, _) = window(h, kind)
        return l.takeIf { it.wakeStart != a || it.wakeEnd != b }
    }

    /** The PUT /api/proactive fields of one day type's start or end. */
    fun field(kind: String, start: Boolean): String = when {
        kind == WEEKEND && start -> "weekend_start"
        kind == WEEKEND -> "weekend_end"
        start -> "wake_start"
        else -> "wake_end"
    }

    /**
     * The "from" and "until" buttons share a row only when each one's text fits on one line in half of it (equal halves look
     * calm); otherwise both go full width, one under the other, so neither label wraps ("다음 날 오전 1:00까지",
     * "Bis 22:00 am nächsten Tag" on a 320dp phone). Widths are the buttons' one-line widths, padding included.
     */
    fun timesSideBySide(first: Int, second: Int, available: Int, gap: Int): Boolean =
        available > 0 && 2 * maxOf(first, second) + gap <= available

    fun applyPath(kind: String): String = "/api/proactive/hours/learned/${KeloApi.segment(kind)}/apply"

    fun acceptPath(id: String): String = "/api/proactive/hours/${KeloApi.segment(id)}/accept"

    fun keepPath(id: String): String = "/api/proactive/hours/${KeloApi.segment(id)}/keep"
}
