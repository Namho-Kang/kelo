package chat.kelo.app.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The small time line above a message that starts a new stretch of the conversation, as messaging apps show it.
 *
 * A message gets one when it is the first message shown, the first of a new calendar day (device time zone), or comes [GAP_MINUTES]
 * or more after the message before it. Nothing in a running back-and-forth.
 *
 * The words: today "오늘 오후 10:00", yesterday "어제 오전 9:31", earlier this year "11월 1일 오후 9:11", any earlier year (last year
 * too) "2024년 10월 19일 오전 9:22". The patterns come from the platform's locale formatter ([Patterns]); only the choice of pattern
 * and the "Today" / "Yesterday" words are decided here, so this stays plain Kotlin (the desktop app shares it).
 */
object TimeLabels {
    /** A message this long after the one before it starts a new stretch (and gets a time line). */
    const val GAP_MINUTES = 30L

    enum class Day { TODAY, YESTERDAY, THIS_YEAR, EARLIER }

    /**
     * Locale patterns for the platform's date formatter. [time]: the time alone ("a h:mm" in Korean, "h:mm a" in English);
     * [monthDayTime]: month, day and time ("MMM d일 a h:mm"); [fullDateTime]: with the year ("y년 MMM d일 a h:mm").
     */
    data class Patterns(val time: String, val monthDayTime: String, val fullDateTime: String)

    /** Which calendar day [atMs] is, seen from [nowMs] in [zone]. A time a little in the future (clocks differ) counts as today. */
    fun day(atMs: Long, nowMs: Long, zone: ZoneId): Day {
        val at = date(atMs, zone)
        val today = date(nowMs, zone)
        return when {
            at == today || (at.isAfter(today) && at == today.plusDays(1) && atMs - nowMs < 60 * 60_000L) -> Day.TODAY
            at == today.minusDays(1) -> Day.YESTERDAY
            at.year == today.year -> Day.THIS_YEAR
            else -> Day.EARLIER
        }
    }

    /** Whether a message at [atMs] starts a new stretch after one at [prevMs] (null: it is the first message shown). */
    fun startsStretch(prevMs: Long?, atMs: Long, zone: ZoneId): Boolean {
        if (prevMs == null || prevMs <= 0L) return true
        return date(prevMs, zone) != date(atMs, zone) || atMs - prevMs >= GAP_MINUTES * 60_000L
    }

    /**
     * The keys of the messages in [items] (oldest first) that get a time line. Messages without a time ([ChatItem.Msg.at] 0) get
     * none and do not count; rows that are not messages (tool chips, cards) are skipped.
     */
    fun labelled(items: List<ChatItem>, zone: ZoneId): Set<String> {
        val out = HashSet<String>()
        var prev: Long? = null
        for (item in items) {
            if (item !is ChatItem.Msg || item.at <= 0L) continue
            if (startsStretch(prev, item.at, zone)) out += item.key
            prev = item.at
        }
        return out
    }

    /**
     * The words of the time line for [atMs]. [format] renders a pattern of [patterns] for that moment (in [zone], in the user's
     * locale); [today] / [yesterday] are the translated words.
     */
    fun label(
        atMs: Long, nowMs: Long, zone: ZoneId, patterns: Patterns, today: String, yesterday: String,
        format: (pattern: String, atMs: Long) -> String,
    ): String = when (day(atMs, nowMs, zone)) {
        Day.TODAY -> "$today ${format(patterns.time, atMs)}"
        Day.YESTERDAY -> "$yesterday ${format(patterns.time, atMs)}"
        Day.THIS_YEAR -> format(patterns.monthDayTime, atMs)
        Day.EARLIER -> format(patterns.fullDateTime, atMs)
    }

    private fun date(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
