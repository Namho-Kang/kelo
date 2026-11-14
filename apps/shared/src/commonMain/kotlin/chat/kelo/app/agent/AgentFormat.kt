package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import chat.kelo.app.ui.platform.bestDatePattern
import androidx.compose.runtime.Composable
import chat.kelo.app.my.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/* Dates, times and schedules in the app's language (the words come from strings.xml, the shapes from the locale). */

private fun pattern(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(bestDatePattern(skeleton, locale), locale)

private fun zoned(seconds: Double) = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault())

/** "오후 3:05" */
@Composable
fun clockText(seconds: Double): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(appLocale()).format(zoned(seconds))

@Composable
fun clockText(hour: Int, minute: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(appLocale()).format(LocalTime.of(hour, minute))

/** "11월 8일 (일)" */
@Composable
fun dayText(date: LocalDate): String = pattern(appLocale(), "MMMdEEE").format(date)

/** "2026년 11월 8일 (일)" */
@Composable
fun fullDayText(date: LocalDate): String = pattern(appLocale(), "yMMMdEEE").format(date)

/** "11월 8일 (일) 오전 9:00" */
@Composable
fun dayClockText(seconds: Double): String {
    val z = zoned(seconds)
    return string(Res.string.agent_day_time, dayText(z.toLocalDate()), clockText(seconds))
}

@Composable
fun dayLabel(label: DayGroups.Label): String = when (label) {
    DayGroups.Label.Today -> string(Res.string.agent_today)
    DayGroups.Label.Yesterday -> string(Res.string.agent_yesterday)
    is DayGroups.Label.Date -> if (label.date.year == LocalDate.now().year) dayText(label.date) else fullDayText(label.date)
    DayGroups.Label.Unknown -> string(Res.string.agent_day_unknown)
}

/** "8시간 뒤" / "3분 전" / "곧" / "방금" */
@Composable
fun relText(seconds: Double, nowSeconds: Double = System.currentTimeMillis() / 1000.0): String {
    val r = RelTime.of(seconds, nowSeconds)
    val n = r.amount.toInt()
    return when (r.unit) {
        RelTime.Unit.NOW -> string(if (r.future) Res.string.agent_rel_soon else Res.string.agent_rel_now)
        RelTime.Unit.MINUTES -> plural(if (r.future) Res.plurals.agent_rel_minutes_future else Res.plurals.agent_rel_minutes_past, n, n)
        RelTime.Unit.HOURS -> plural(if (r.future) Res.plurals.agent_rel_hours_future else Res.plurals.agent_rel_hours_past, n, n)
        RelTime.Unit.DAYS -> plural(if (r.future) Res.plurals.agent_rel_days_future else Res.plurals.agent_rel_days_past, n, n)
    }
}

/** A weekday's short name in the app's language ("월"). */
@Composable
fun weekdayShort(cronDay: Int): String = CronBuilder.dayOfWeek(cronDay).getDisplayName(TextStyle.SHORT, appLocale())

/** What a schedule's repeat means, in words ("매일 오전 9:00"); a cron the app has no words for uses the server's sentence (never the expression). */
@Composable
fun repeatText(r: Repeat, serverWords: String = ""): String = when (r) {
    is Repeat.Daily -> string(Res.string.agent_repeat_daily_at, clockText(r.hour, r.minute))
    is Repeat.Weekdays -> string(Res.string.agent_repeat_weekdays_at, clockText(r.hour, r.minute))
    is Repeat.Weekly -> if (r.days.map { it % 7 }.toSet() == setOf(0, 6)) string(Res.string.agent_repeat_weekends_at, clockText(r.hour, r.minute))
    else string(
        Res.string.agent_repeat_weekly_at,
        CronBuilder.PICKER_DAYS.filter { it in r.days }.map { weekdayShort(it) }.joinToString(string(Res.string.agent_list_separator)),
        clockText(r.hour, r.minute),
    )
    is Repeat.Monthly -> string(Res.string.agent_repeat_monthly_at, r.day, clockText(r.hour, r.minute))
    is Repeat.Yearly -> string(Res.string.agent_repeat_yearly_at, monthDayText(r.month, r.day), clockText(r.hour, r.minute))
    is Repeat.EveryHours -> if (r.hours <= 1) string(Res.string.agent_repeat_hourly_at, r.minute)
        else if (r.minute == 0) plural(Res.plurals.agent_repeat_every_hours, r.hours, r.hours)
        else string(Res.string.agent_repeat_every_hours_at, plural(Res.plurals.agent_repeat_every_hours, r.hours, r.hours), r.minute)
    is Repeat.Once -> string(Res.string.agent_repeat_once_at, fullDayText(r.date), clockText(r.hour, r.minute))
    is Repeat.Interval -> intervalText(r.minutes)
    // never the cron expression itself: the server's sentence, else a plain "repeats on a set rule"
    is Repeat.Custom -> serverWords.takeIf { it.isNotBlank() && !CronBuilder.looksLikeCron(it) } ?: string(Res.string.sched_text_rule)
}

/** Whether the app's language writes times on a 24-hour clock (the time pickers follow it, like [clockText]). */
@Composable
fun uses24Hour(): Boolean = bestDatePattern("jm", appLocale()).let { 'H' in it || 'k' in it }

/** "11월 8일" (no year, no weekday) */
@Composable
fun monthDayText(month: Int, day: Int): String =
    pattern(appLocale(), "MMMd").format(java.time.MonthDay.of(month, day.coerceIn(1, java.time.Month.of(month).maxLength())).atYear(2024))

/** A repeat's words with its first and last day ("매일 오전 9:00, 12월 31일까지"); a one-time run has neither. */
@Composable
fun withDays(words: String, startsOn: LocalDate?, endsOn: LocalDate?, today: LocalDate = LocalDate.now()): String {
    var out = words
    if (startsOn != null && startsOn.isAfter(today)) out = string(Res.string.sched_text_from, out, dayOrFullText(startsOn, today))
    if (endsOn != null) out = string(Res.string.sched_text_until, out, dayOrFullText(endsOn, today))
    return out
}

@Composable
fun dayOrFullText(d: LocalDate, today: LocalDate): String = if (d.year == today.year) dayText(d) else fullDayText(d)

/** How a saved schedule runs, in words: the same in the agenda and on its page. A cron the app has no words for uses the server's. */
@Composable
fun scheduleText(s: Schedule): String {
    val repeat = CronBuilder.fromSchedule(s)
    val words = repeatText(repeat, s.description)
    // the server's own description already says its first and last day
    return if (repeat is Repeat.Custom || repeat is Repeat.Once) words else withDays(words, ScheduleDays.parse(s.startsOn), ScheduleDays.parse(s.endsOn))
}

/** The simple form's choice in words, as the agenda will show it once saved. */
@Composable
fun planText(p: SchedulePlan, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
    val words = repeatText(PlanRules.asRepeat(p))
    if (p.freq == Freq.NONE) return words
    return withDays(words, ScheduleDays.parse(PlanRules.startsOn(p, now)), p.until, now.toLocalDate())
}

/** "30분마다" / "2시간마다" / "하루마다" */
@Composable
fun intervalText(minutes: Int): String = when {
    minutes % 1440 == 0 -> plural(Res.plurals.work_every_days, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> plural(Res.plurals.agent_repeat_every_hours, minutes / 60, minutes / 60)
    else -> plural(Res.plurals.work_every_minutes, minutes, minutes)
}
