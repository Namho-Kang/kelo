package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** One week of the journal, as the 기록 page shows it. */
data class WeekDigest(val start: LocalDate, val entries: List<JournalEntry> = emptyList(), val gaps: List<JournalGap> = emptyList()) {
    val end: LocalDate get() = start.plusDays(6)
    val days: List<JournalDays.Day> get() = JournalDays.days(entries, gaps)
}

/** Weeks run Monday to Sunday; the journal is fetched by month, so a week may need two. */
object DigestRules {
    fun startOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The months the week starting [start] touches, in order (one, or two across a month's end). */
    fun months(start: LocalDate): List<YearMonth> = listOf(YearMonth.from(start), YearMonth.from(start.plusDays(6))).distinct()

    /** The entries and gaps that fall inside the week, newest day first. */
    fun week(start: LocalDate, entries: List<JournalEntry>, gaps: List<JournalGap>): WeekDigest {
        val from = start.toString()
        val to = start.plusDays(6).toString()
        fun inside(date: String) = date.take(10) in from..to
        return WeekDigest(
            start,
            entries.filter { inside(it.date) }.distinctBy { it.id }.sortedByDescending { it.date },
            gaps.filter { inside(it.date) }.distinctBy { it.date },
        )
    }

    /** No going past the current week. */
    fun canGoNext(start: LocalDate, today: LocalDate): Boolean = start < startOf(today)

    /** How many entries each day of the week has, Monday first (for the little bar strip). */
    fun perDay(d: WeekDigest): List<Int> = (0..6).map { i -> val day = d.start.plusDays(i.toLong()).toString(); d.entries.count { it.date.take(10) == day } }
}
