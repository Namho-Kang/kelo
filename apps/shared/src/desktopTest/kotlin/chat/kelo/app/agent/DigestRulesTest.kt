package chat.kelo.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The weekly digest of 마이 → 기록, and the schedule kinds the server added. */
class DigestRulesTest {
    @Test fun weeksStartOnMondayAndMayNeedTwoMonths() {
        assertEquals(LocalDate.of(2026, 11, 2), DigestRules.startOf(LocalDate.of(2026, 11, 7)))
        assertEquals(LocalDate.of(2026, 11, 2), DigestRules.startOf(LocalDate.of(2026, 11, 2)))
        assertEquals(listOf(YearMonth.of(2026, 11)), DigestRules.months(LocalDate.of(2026, 11, 2)))
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), DigestRules.months(LocalDate.of(2026, 10, 26)))
    }

    @Test fun theWeekKeepsOnlyItsOwnDays() {
        val entries = listOf(
            JournalEntry(1, "2026-11-01", "전 주"), JournalEntry(2, "2026-11-02", "월"), JournalEntry(3, "2026-11-08", "일"),
            JournalEntry(3, "2026-11-08", "일"), JournalEntry(4, "2026-11-09", "다음 주"),
        )
        val d = DigestRules.week(LocalDate.of(2026, 11, 2), entries, listOf(JournalGap("2026-11-05", "pending"), JournalGap("2026-11-10", "pending")))
        assertEquals(listOf(3L, 2L), d.entries.map { it.id })     // newest first, duplicates once
        assertEquals(listOf("2026-11-05"), d.gaps.map { it.date })
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 1), DigestRules.perDay(d))
        assertEquals(LocalDate.of(2026, 11, 8), d.end)
        assertEquals(listOf("2026-11-08", "2026-11-05", "2026-11-02"), d.days.map { it.date })
    }

    @Test fun noGoingPastThisWeek() {
        val today = LocalDate.of(2026, 11, 7)
        assertTrue(DigestRules.canGoNext(LocalDate.of(2026, 10, 26), today))
        assertFalse(DigestRules.canGoNext(LocalDate.of(2026, 11, 2), today))
    }

    @Test fun intervalSchedulesBecomeTheIntervalChoice() {
        assertEquals(Repeat.Interval(90), CronBuilder.fromSchedule(Schedule(1, kind = "interval", everyMinutes = 90)))
        assertEquals(Repeat.Interval(30), CronBuilder.fromSchedule(Schedule(1, kind = "", everyMinutes = 30)))
        assertEquals(Repeat.Daily(9, 0), CronBuilder.fromSchedule(Schedule(1, kind = "window", cron = "0 9 * * *")))
        assertEquals(null, CronBuilder.cron(Repeat.Interval(60)))
        assertEquals(CronBuilder.Problem.TOO_OFTEN, CronBuilder.problem(Repeat.Interval(3)))
        assertEquals(null, CronBuilder.problem(Repeat.Interval(5)))
    }
}
