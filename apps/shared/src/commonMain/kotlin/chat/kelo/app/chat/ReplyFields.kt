package chat.kelo.app.chat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The typed answer fields of a quick-reply question, apart from the UI (the phone, desktop and iPhone apps follow the same rules;
 * the server checks the same plain forms, goals.py check_answer).
 *
 * A question's `kind` says what its answer is: choice (buttons; the default), text, number, date, time, datetime, weekdays (one or
 * more days of the week) or weekdays_time. Every kind but choice opens the device's own picker for that kind of value, whatever the
 * question is about. An answer is kept in its plain form ([check]): number "3", date "2026-11-10", time "14:30", datetime
 * "2026-11-10 14:30", weekdays "mon,wed", weekdays_time "mon,wed 14:30". The chat shows (and sends) it in the user's words
 * ([display]); the plain forms go along as "answers" so the server never has to read a date out of words.
 */
object ReplyFields {
    const val CHOICE = "choice"
    const val TEXT = "text"
    const val NUMBER = "number"
    const val DATE = "date"
    const val TIME = "time"
    const val DATETIME = "datetime"
    const val WEEKDAYS = "weekdays"
    const val WEEKDAYS_TIME = "weekdays_time"
    private val FIELDS = setOf(TEXT, NUMBER, DATE, TIME, DATETIME, WEEKDAYS, WEEKDAYS_TIME)

    /** The plain day codes, Monday first (ISO), as the server writes them. */
    val DAY_CODES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private const val ANSWER_MAX = 300

    /** The question's kind; one this app does not know yet (a newer server) is a choice when it has buttons, else a text box. */
    fun kind(q: ReplyQuestion): String {
        val k = q.kind.trim().lowercase()
        return when {
            k.isEmpty() || k == CHOICE -> CHOICE
            k in FIELDS -> k
            q.options.size >= 2 -> CHOICE
            else -> TEXT
        }
    }

    /** The question takes a typed field (a picker or a box) instead of buttons. */
    fun isField(q: ReplyQuestion): Boolean = kind(q) != CHOICE

    /** The answer [value] in its plain form, or null when it does not fit the question (the server's check, mirrored). */
    fun check(q: ReplyQuestion, value: String?): String? {
        val v = value?.trim()?.replace(Regex("\\s+"), " ")?.take(ANSWER_MAX).orEmpty()
        if (v.isEmpty()) return null
        return when (kind(q)) {
            CHOICE, TEXT -> v
            NUMBER -> {
                val n = v.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                if (q.min != null && n < q.min || q.max != null && n > q.max) return null
                if (n == Math.floor(n) && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()
            }
            DATE -> date(v)?.toString()
            TIME -> time(v)?.let(::timePlain)
            DATETIME -> {
                val parts = v.replace('T', ' ').split(' ', limit = 2)
                val d = date(parts[0]) ?: return null
                val t = parts.getOrNull(1)?.let { time(it) } ?: return null
                "$d ${timePlain(t)}"
            }
            WEEKDAYS -> days(v)?.let(::daysPlain)
            WEEKDAYS_TIME -> {
                val at = v.lastIndexOf(' ')
                if (at <= 0) return null
                val d = days(v.substring(0, at)) ?: return null
                val t = time(v.substring(at + 1)) ?: return null
                "${daysPlain(d)} ${timePlain(t)}"
            }
            else -> null
        }
    }

    fun date(v: String): LocalDate? = runCatching { LocalDate.parse(v.trim()) }.getOrNull()

    fun time(v: String): LocalTime? {
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(v.trim()) ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    fun timePlain(t: LocalTime): String = "%02d:%02d".format(Locale.ROOT, t.hour, t.minute)

    /** "mon, wed" / "wed mon" -> the days; null when empty or a word is not a day code. */
    fun days(v: String): Set<DayOfWeek>? {
        val words = v.split(Regex("[,\\s]+")).map { it.trim().lowercase().take(3) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.map { w -> DAY_CODES.indexOf(w).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) } ?: return null }.toSet()
    }

    fun daysPlain(days: Set<DayOfWeek>): String = days.sortedBy { it.value }.joinToString(",") { DAY_CODES[it.value - 1] }

    /** The seven days in the order to show them, [first] first (the locale's first day of the week). */
    fun weekOrder(first: DayOfWeek): List<DayOfWeek> = (0 until 7).map { first.plus(it.toLong()) }

    /** Toggling a day of a weekdays field; unlike a schedule's repeat, the last day may go too (then nothing is picked). */
    fun toggle(days: Set<DayOfWeek>, d: DayOfWeek): Set<DayOfWeek> = if (d in days) days - d else days + d

    /**
     * The words an answer is shown and sent in. [words]: the app's own phrases for repeats ("Every day", "Weekly on %1$s", ...);
     * dates and times use the locale's formats unless the app passes its own ([formatTime] follows the phone's 12/24-hour setting).
     */
    fun display(
        q: ReplyQuestion,
        plain: String,
        locale: Locale,
        words: Words,
        first: DayOfWeek,
        formatTime: (LocalTime) -> String = { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(it) },
        formatDate: (LocalDate) -> String = { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(it) },
    ): String {
        fun dayList(d: Set<DayOfWeek>): String = weekOrder(first).filter { it in d }
            .joinToString(words.separator) { it.getDisplayName(java.time.format.TextStyle.SHORT, locale) }
        return when (kind(q)) {
            NUMBER -> q.unit.trim().let { u -> if (u.isEmpty()) plain else if (closeUnit(u)) "$plain$u" else "$plain $u" }
            DATE -> date(plain)?.let(formatDate) ?: plain
            TIME -> time(plain)?.let(formatTime) ?: plain
            DATETIME -> {
                val (d, t) = plain.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val dd = date(d) ?: return plain
                val tt = time(t) ?: return plain
                words.dateAt.format(formatDate(dd), formatTime(tt))
            }
            WEEKDAYS -> {
                val d = days(plain) ?: return plain
                if (d.size == 7) words.daily else words.weekly.format(dayList(d))
            }
            WEEKDAYS_TIME -> {
                val at = plain.lastIndexOf(' ')
                val d = days(plain.substring(0, at.coerceAtLeast(0))) ?: return plain
                val tt = time(plain.substring(at + 1)) ?: return plain
                if (d.size == 7) words.dailyAt.format(formatTime(tt)) else words.weeklyAt.format(dayList(d), formatTime(tt))
            }
            else -> plain
        }
    }

    /** The app's phrases [display] needs (Android/desktop: agent_repeat_daily, sched_value_weekly, agent_repeat_daily_at,
     *  agent_repeat_weekly_at, reply_date_at, agent_list_separator). */
    data class Words(
        val daily: String = "Every day",
        val weekly: String = "Weekly on %1\$s",
        val dailyAt: String = "Every day at %1\$s",
        val weeklyAt: String = "Every %1\$s at %2\$s",
        val dateAt: String = "%1\$s at %2\$s",
        val separator: String = ", ",
    )

    /** A unit written right after the number: Korean, Chinese and Japanese counters ("3개", "3個"), and "%". */
    fun closeUnit(unit: String): Boolean = unit.first().let { it == '%' || Character.UnicodeScript.of(it.code) in CLOSE_SCRIPTS }
    private val CLOSE_SCRIPTS = setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA)

    /** Where a picker starts: the question's default, else nothing picked yet. */
    fun start(q: ReplyQuestion): String? = check(q, q.defaultValue)

    /** The number range as words for the hint under a number box ("1–5"), or null without both ends. */
    fun range(q: ReplyQuestion): Pair<String, String>? {
        val lo = q.min ?: return null
        val hi = q.max ?: return null
        fun s(n: Double) = if (n == Math.floor(n)) n.toLong().toString() else n.toString()
        return s(lo) to s(hi)
    }
}
