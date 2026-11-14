package chat.kelo.app.agent

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToLong

/** How a list is split into days, newest first: today, yesterday, then dates. */
object DayGroups {
    sealed interface Label {
        data object Today : Label
        data object Yesterday : Label
        data class Date(val date: LocalDate) : Label
        /** The item carried no day that can be read */
        data object Unknown : Label
    }

    data class Group<T>(val label: Label, val items: List<T>)

    fun label(day: LocalDate?, today: LocalDate): Label = when (day) {
        null -> Label.Unknown
        today -> Label.Today
        today.minusDays(1) -> Label.Yesterday
        else -> Label.Date(day)
    }

    /** Keeps the items' order; a day's items stay together under the first place that day appears. */
    fun <T> group(items: List<T>, today: LocalDate, dayOf: (T) -> LocalDate?): List<Group<T>> {
        val order = LinkedHashMap<Label, MutableList<T>>()
        items.forEach { order.getOrPut(label(dayOf(it), today)) { mutableListOf() } += it }
        return order.map { (k, v) -> Group(k, v) }
    }

    /** "2026-11-07", or null when it is not a date. */
    fun parseDay(text: String?): LocalDate? = text?.takeIf { it.isNotBlank() }?.let {
        try { LocalDate.parse(it.take(10)) } catch (e: DateTimeParseException) { null }
    }

    /** A task's day: the server's "day", else the local date of when it started. */
    fun dayOf(task: AgentTask, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        parseDay(task.day) ?: task.startedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(zone).toLocalDate() }
}

/** "8시간 뒤" / "3분 전": how far a moment is from now, in the largest unit that fits. Plurals are picked by the screen. */
object RelTime {
    enum class Unit { NOW, MINUTES, HOURS, DAYS }

    data class Rel(val unit: Unit, val amount: Long, val future: Boolean)

    fun of(targetSeconds: Double, nowSeconds: Double): Rel {
        val d = targetSeconds - nowSeconds
        val future = d > 0
        val s = abs(d)
        if (s < 60) return Rel(Unit.NOW, 0, future)
        val minutes = (s / 60).roundToLong()
        if (minutes < 60) return Rel(Unit.MINUTES, minutes, future)
        val hours = (s / 3600).roundToLong()
        if (hours < 24) return Rel(Unit.HOURS, hours, future)
        return Rel(Unit.DAYS, (s / 86400).roundToLong().coerceAtLeast(1), future)
    }
}

/** How often a schedule runs, as the picker shows it. Days of the week are 0 = Sunday … 6 = Saturday (as in cron). */
sealed interface Repeat {
    data class Daily(val hour: Int, val minute: Int) : Repeat
    data class Weekdays(val hour: Int, val minute: Int) : Repeat
    data class Weekly(val days: Set<Int>, val hour: Int, val minute: Int) : Repeat
    data class Monthly(val day: Int, val hour: Int, val minute: Int) : Repeat
    data class Yearly(val month: Int, val day: Int, val hour: Int, val minute: Int) : Repeat
    data class EveryHours(val hours: Int, val minute: Int) : Repeat
    data class Once(val date: LocalDate, val hour: Int, val minute: Int) : Repeat
    /** Every [minutes] minutes from the first run (the server's "interval" kind) */
    data class Interval(val minutes: Int) : Repeat
    /** A cron expression typed by hand (or one the picker has no words for) */
    data class Custom(val cron: String) : Repeat
}

/** Turns picker choices into what POST/PATCH /api/schedules takes, and a saved schedule back into picker choices. */
object CronBuilder {
    const val MIN_INTERVAL = 5

    /** The cron expression, or null for a one-time run (that one goes as [runAt]). */
    fun cron(r: Repeat): String? = when (r) {
        is Repeat.Daily -> "${r.minute} ${r.hour} * * *"
        is Repeat.Weekdays -> "${r.minute} ${r.hour} * * 1-5"
        is Repeat.Weekly -> "${r.minute} ${r.hour} * * ${r.days.map { it % 7 }.toSortedSet().joinToString(",")}"
        is Repeat.Monthly -> "${r.minute} ${r.hour} ${r.day} * *"
        is Repeat.Yearly -> "${r.minute} ${r.hour} ${r.day} ${r.month} *"
        is Repeat.EveryHours -> if (r.hours <= 1) "${r.minute} * * * *" else "${r.minute} */${r.hours} * * *"
        is Repeat.Custom -> r.cron.trim()
        is Repeat.Once, is Repeat.Interval -> null
    }

    /** A one-time run as local date-time ("2026-11-08T09:00"); the server reads it in the schedule's zone. */
    fun runAt(r: Repeat.Once): String = r.date.toString() + "T" + String.format(java.util.Locale.ROOT, "%02d:%02d", r.hour, r.minute)

    /** What is missing before the choice can be saved, or null when it is complete. */
    fun problem(r: Repeat): Problem? = when (r) {
        is Repeat.Weekly -> if (r.days.isEmpty()) Problem.NO_DAY else null
        is Repeat.Custom -> if (r.cron.trim().split(Regex("\\s+")).size != 5 && !r.cron.trim().startsWith("@")) Problem.BAD_CRON else null
        is Repeat.Interval -> if (r.minutes < MIN_INTERVAL) Problem.TOO_OFTEN else null
        else -> null
    }

    enum class Problem { NO_DAY, BAD_CRON, TOO_OFTEN }

    fun fromSchedule(s: Schedule): Repeat = when {
        s.kind == "interval" || (s.cron.isNullOrBlank() && s.everyMinutes != null) -> Repeat.Interval(s.everyMinutes ?: 60)
        s.kind == "once" || (s.cron.isNullOrBlank() && s.runAt != null) -> parseRunAt(s.runAt) ?: Repeat.Custom(s.cron.orEmpty())
        else -> parse(s.cron)
    }

    /** "2026-11-08T09:00:00+09:00" (or without the offset) as picker choices, in the time written there. */
    fun parseRunAt(text: String?): Repeat.Once? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val local = try {
            OffsetDateTime.parse(t).toLocalDateTime()
        } catch (e: DateTimeParseException) {
            try { LocalDateTime.parse(t) } catch (e2: DateTimeParseException) { return null }
        }
        return Repeat.Once(local.toLocalDate(), local.hour, local.minute)
    }

    private val MACROS = mapOf(
        "@hourly" to "0 * * * *", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@weekly" to "0 0 * * 0",
        "@monthly" to "0 0 1 * *", "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *",
    )
    private val DAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

    /** The picker's choice for [cron]; anything it cannot show stays as [Repeat.Custom]. */
    fun parse(cron: String?): Repeat {
        val raw = cron?.trim().orEmpty()
        val expr = MACROS[raw.lowercase()] ?: raw
        val f = expr.split(Regex("\\s+"))
        if (f.size != 5) return Repeat.Custom(raw)
        val (mi, ho, dom, mon, dow) = f
        val minute = mi.toIntOrNull()?.takeIf { it in 0..59 } ?: return Repeat.Custom(raw)
        if (mon != "*") {
            // once a year: one month, one day, one time
            val month = mon.toIntOrNull()?.takeIf { it in 1..12 } ?: return Repeat.Custom(raw)
            val d = dom.toIntOrNull()?.takeIf { it in 1..31 } ?: return Repeat.Custom(raw)
            val h = ho.toIntOrNull()?.takeIf { it in 0..23 } ?: return Repeat.Custom(raw)
            return if (dow == "*") Repeat.Yearly(month, d, h, minute) else Repeat.Custom(raw)
        }
        // every N hours
        if (dom == "*" && dow == "*") {
            if (ho == "*") return Repeat.EveryHours(1, minute)
            if (ho.startsWith("*/")) {
                val n = ho.removePrefix("*/").toIntOrNull() ?: return Repeat.Custom(raw)
                return if (n in 1..23) Repeat.EveryHours(n, minute) else Repeat.Custom(raw)
            }
        }
        val hour = ho.toIntOrNull()?.takeIf { it in 0..23 } ?: return Repeat.Custom(raw)
        if (dom == "*" && dow == "*") return Repeat.Daily(hour, minute)
        if (dow == "*") {
            val d = dom.toIntOrNull()?.takeIf { it in 1..31 } ?: return Repeat.Custom(raw)
            return Repeat.Monthly(d, hour, minute)
        }
        if (dom != "*") return Repeat.Custom(raw)
        val days = weekdays(dow) ?: return Repeat.Custom(raw)
        return when {
            days == setOf(1, 2, 3, 4, 5) -> Repeat.Weekdays(hour, minute)
            days.size == 7 -> Repeat.Daily(hour, minute)
            else -> Repeat.Weekly(days, hour, minute)
        }
    }

    /** "1-5", "mon,wed", "0,6", "7" as a set of 0..6, or null when it is something else. */
    private fun weekdays(text: String): Set<Int>? {
        val out = sortedSetOf<Int>()
        for (part in text.lowercase().split(",")) {
            if (part.contains("/")) return null
            val ends = part.split("-")
            val nums = ends.map { e -> e.toIntOrNull() ?: DAY_NAMES.indexOf(e).takeIf { it >= 0 } ?: return null }
            if (nums.any { it !in 0..7 }) return null
            when (nums.size) {
                1 -> out += nums[0] % 7
                2 -> if (nums[0] <= nums[1]) (nums[0]..nums[1]).forEach { out += it % 7 } else return null
                else -> return null
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** cron's day number (0 = Sunday) as java.time's day. */
    fun dayOfWeek(cronDay: Int): DayOfWeek = DayOfWeek.of(if (cronDay % 7 == 0) 7 else cronDay % 7)

    /** Whether a text is (or carries) a cron expression rather than words: "0 9 * * 1", "@daily", "every 5 minutes" written as a step. */
    fun looksLikeCron(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("@") || '*' in t || Regex("(^|\\s)[\\d,/-]+(\\s+[\\d*,/-]+){4}(\\s|$)").containsMatchIn(t)
    }

    /** The days in the order the picker shows them: Monday first. */
    val PICKER_DAYS = listOf(1, 2, 3, 4, 5, 6, 0)
}

/** How often a schedule repeats, as a phone calendar asks it. */
enum class Freq { NONE, DAILY, WEEKDAYS, WEEKENDS, WEEKLY, MONTHLY, YEARLY }

/**
 * A schedule as the simple form shows it: a date (the one time, or the first day of a repeat), a time, how it repeats and an optional
 * last day. Weekly days are 0 = Sunday … 6 = Saturday; a monthly repeat takes the date's day of the month, a yearly one its month and day.
 */
data class SchedulePlan(
    val date: LocalDate, val hour: Int, val minute: Int, val freq: Freq = Freq.NONE, val days: Set<Int> = emptySet(),
    val until: LocalDate? = null,
)

/** Turns the simple form into what POST/PATCH /api/schedules takes (cron | run_at, starts_on, ends_on), and a saved schedule back. */
object PlanRules {
    enum class Problem { NO_DAY, PAST, END_BEFORE_START }

    private fun hm(p: SchedulePlan) = "${p.minute} ${p.hour}"

    /** The cron expression of a repeat, or null for a one-time run. */
    fun cron(p: SchedulePlan): String? = when (p.freq) {
        Freq.NONE -> null
        Freq.DAILY -> "${hm(p)} * * *"
        Freq.WEEKDAYS -> "${hm(p)} * * 1-5"
        Freq.WEEKENDS -> "${hm(p)} * * 0,6"
        Freq.WEEKLY -> "${hm(p)} * * ${p.days.map { it % 7 }.toSortedSet().joinToString(",")}"
        Freq.MONTHLY -> "${hm(p)} ${p.date.dayOfMonth} * *"
        Freq.YEARLY -> "${hm(p)} ${p.date.dayOfMonth} ${p.date.monthValue} *"
    }

    fun runAt(p: SchedulePlan): String = p.date.toString() + "T" + String.format(java.util.Locale.ROOT, "%02d:%02d", p.hour, p.minute)

    /** Whether a repeat runs on [d] (a one-time plan: only on its date). */
    fun runsOn(p: SchedulePlan, d: LocalDate): Boolean {
        val dow = d.dayOfWeek.value % 7
        return when (p.freq) {
            Freq.NONE -> d == p.date
            Freq.DAILY -> true
            Freq.WEEKDAYS -> dow in 1..5
            Freq.WEEKENDS -> dow == 0 || dow == 6
            Freq.WEEKLY -> dow in p.days.map { it % 7 }
            Freq.MONTHLY -> d.dayOfMonth == p.date.dayOfMonth
            Freq.YEARLY -> d.dayOfMonth == p.date.dayOfMonth && d.monthValue == p.date.monthValue
        }
    }

    /** The first day on or after [from] when the plan's time is still ahead of [now], or null within about 4 years. */
    fun firstDay(p: SchedulePlan, from: LocalDate, now: LocalDateTime): LocalDate? {
        var d = from
        repeat(366 * 4 + 1) {
            if (runsOn(p, d) && LocalDateTime.of(d, java.time.LocalTime.of(p.hour, p.minute)).isAfter(now)) return d
            d = d.plusDays(1)
        }
        return null
    }

    /** "starts_on" for the server: the chosen date when the repeat would otherwise begin before it, else "" (no first day). */
    fun startsOn(p: SchedulePlan, now: LocalDateTime): String {
        if (p.freq == Freq.NONE) return ""
        val natural = firstDay(p, now.toLocalDate(), now) ?: return ""
        return if (p.date.isAfter(natural)) p.date.toString() else ""
    }

    /** "ends_on": the last day of a repeat, or "" (none). */
    fun endsOn(p: SchedulePlan): String = if (p.freq != Freq.NONE) p.until?.toString().orEmpty() else ""

    fun problem(p: SchedulePlan, now: LocalDateTime): Problem? = when {
        p.freq == Freq.WEEKLY && p.days.isEmpty() -> Problem.NO_DAY
        p.freq == Freq.NONE && !LocalDateTime.of(p.date, java.time.LocalTime.of(p.hour, p.minute)).isAfter(now) -> Problem.PAST
        p.freq != Freq.NONE && p.until != null && p.until.isBefore(maxOf(p.date, now.toLocalDate())) -> Problem.END_BEFORE_START
        else -> null
    }

    /** The day a saved repeat is next due: its first day when that is still ahead, else its next run, else today. */
    private fun base(s: Schedule, today: LocalDate): LocalDate =
        ScheduleDays.parse(s.startsOn)?.takeIf { it.isAfter(today) }
            ?: s.nextRun?.let { try { OffsetDateTime.parse(it).toLocalDate() } catch (e: DateTimeParseException) { null } }
            ?: today

    /** The first day on or after [from] with that day of the month (and month, for a yearly repeat). */
    private fun nextWith(from: LocalDate, day: Int, month: Int? = null): LocalDate {
        var d = from
        repeat(366 * 4 + 1) {
            if (d.dayOfMonth == day && (month == null || d.monthValue == month)) return d
            d = d.plusDays(1)
        }
        return from
    }

    /**
     * A saved schedule in the simple form, or null when it does not fit it (every few hours, every N minutes, an unusual cron the
     * agent made): those are shown in words, with a way to replace them by a simple repeat.
     */
    fun fromSchedule(s: Schedule, today: LocalDate): SchedulePlan? {
        val until = ScheduleDays.parse(s.endsOn)
        return when (val r = CronBuilder.fromSchedule(s)) {
            is Repeat.Once -> SchedulePlan(r.date, r.hour, r.minute, Freq.NONE)
            is Repeat.Daily -> SchedulePlan(base(s, today), r.hour, r.minute, Freq.DAILY, until = until)
            is Repeat.Weekdays -> SchedulePlan(base(s, today), r.hour, r.minute, Freq.WEEKDAYS, until = until)
            is Repeat.Weekly -> if (r.days == setOf(0, 6)) SchedulePlan(base(s, today), r.hour, r.minute, Freq.WEEKENDS, until = until)
                else SchedulePlan(base(s, today), r.hour, r.minute, Freq.WEEKLY, r.days, until)
            is Repeat.Monthly -> SchedulePlan(nextWith(base(s, today), r.day), r.hour, r.minute, Freq.MONTHLY, until = until)
            is Repeat.Yearly -> SchedulePlan(nextWith(base(s, today), r.day, r.month), r.hour, r.minute, Freq.YEARLY, until = until)
            else -> null
        }
    }

    /** What "직접 바꾸기" starts from for a schedule the form cannot show: every day at its next run's time (9:00 without one). */
    fun replacing(s: Schedule, today: LocalDate): SchedulePlan {
        val next = s.nextRun?.let { try { OffsetDateTime.parse(it).toLocalDateTime() } catch (e: DateTimeParseException) { null } }
        return SchedulePlan(today, next?.hour ?: 9, next?.minute ?: 0, Freq.DAILY, until = ScheduleDays.parse(s.endsOn))
    }

    /** The seven weekdays (0 = Sunday … 6 = Saturday) in the order the week is shown, starting from the locale's [first] day. */
    fun weekOrder(first: DayOfWeek): List<Int> = (0 until 7).map { (first.value + it) % 7 }

    /** Choosing a repeat: a weekly one starts on the date's weekday when no day was picked yet. */
    fun withFreq(p: SchedulePlan, f: Freq): SchedulePlan =
        p.copy(freq = f, days = if (f == Freq.WEEKLY && p.days.isEmpty()) setOf(p.date.dayOfWeek.value % 7) else p.days)

    /** Tapping a weekday of a weekly repeat: on or off, but the last chosen day stays on. */
    fun toggleDay(days: Set<Int>, d: Int): Set<Int> = if (d in days) (days - d).ifEmpty { days } else days + d

    /** The last day first offered when "on a date" is chosen: a month after the start (or today, when that is later). */
    fun defaultUntil(start: LocalDate, today: LocalDate): LocalDate = maxOf(start, today).plusMonths(1)

    /**
     * The label in a small weekday circle: the short name without its trailing dot when it is at most 3 letters ("월", "Mon", "lun"),
     * else the narrow one ("T2" for "Th 2", "ن" for Arabic's full-length short name).
     */
    fun circleLabel(short: String, narrow: String): String = short.trimEnd('.').let { if (it.length in 1..3) it else narrow }

    /** The plan's repeat in the older picker's terms (for the words shared with the agenda). */
    fun asRepeat(p: SchedulePlan): Repeat = when (p.freq) {
        Freq.NONE -> Repeat.Once(p.date, p.hour, p.minute)
        Freq.DAILY -> Repeat.Daily(p.hour, p.minute)
        Freq.WEEKDAYS -> Repeat.Weekdays(p.hour, p.minute)
        Freq.WEEKENDS -> Repeat.Weekly(setOf(0, 6), p.hour, p.minute)
        Freq.WEEKLY -> Repeat.Weekly(p.days, p.hour, p.minute)
        Freq.MONTHLY -> Repeat.Monthly(p.date.dayOfMonth, p.hour, p.minute)
        Freq.YEARLY -> Repeat.Yearly(p.date.monthValue, p.date.dayOfMonth, p.hour, p.minute)
    }
}

/** A schedule's first and last day ("2026-11-08"). */
object ScheduleDays {
    fun parse(text: String?): LocalDate? = text?.takeIf { it.isNotBlank() }?.let {
        try { LocalDate.parse(it.take(10)) } catch (e: DateTimeParseException) { null }
    }
}

/** A schedule's next run time as unix seconds: the server's own number, else its ISO time. */
object ScheduleTimes {
    fun nextRunSeconds(s: Schedule): Double? = s.nextRunTs ?: s.nextRun?.let {
        try { OffsetDateTime.parse(it).toEpochSecond().toDouble() } catch (e: DateTimeParseException) { null }
    }
}
