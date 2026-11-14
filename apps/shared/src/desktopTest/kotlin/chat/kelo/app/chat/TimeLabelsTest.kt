package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimeLabelsTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val ny = ZoneId.of("America/New_York")

    private fun ms(text: String, zone: ZoneId = seoul) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // the patterns Android's DateFormat.getBestDateTimePattern gives for the skeletons jmm / MMMdjmm / yMMMdjmm
    private val ko = TimeLabels.Patterns("a h:mm", "MMM d일 a h:mm", "y년 MMM d일 a h:mm")
    private val en = TimeLabels.Patterns("h:mm a", "MMM d, h:mm a", "MMM d, y, h:mm a")

    private fun fmt(locale: Locale, zone: ZoneId) = { pattern: String, at: Long ->
        DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(java.time.Instant.ofEpochMilli(at)).replace(' ', ' ')
    }

    private fun koLabel(at: String, now: String) = TimeLabels.label(ms(at), ms(now), seoul, ko, "오늘", "어제", fmt(Locale.KOREAN, seoul))
    private fun enLabel(at: String, now: String) = TimeLabels.label(ms(at), ms(now), seoul, en, "Today", "Yesterday", fmt(Locale.ENGLISH, seoul))

    @Test fun koreanWords() {
        val now = "2026-11-08T23:00"
        assertEquals("오늘 오후 10:00", koLabel("2026-11-08T22:00", now))
        assertEquals("어제 오전 9:31", koLabel("2026-11-07T09:31", now))
        assertEquals("11월 1일 오후 9:11", koLabel("2026-11-01T21:11", now))
        assertEquals("2025년 10월 19일 오전 9:22", koLabel("2025-10-19T09:22", now))   // last year: the full year too
        assertEquals("2024년 10월 19일 오전 9:22", koLabel("2024-10-19T09:22", now))   // two years back
        assertEquals("오늘 오전 12:05", koLabel("2026-11-08T00:05", now))              // no leading zero, 12-hour
    }

    @Test fun englishWords() {
        val now = "2026-11-08T23:00"
        assertEquals("Today 10:00 PM", enLabel("2026-11-08T22:00", now))
        assertEquals("Yesterday 9:31 AM", enLabel("2026-11-07T09:31", now))
        assertEquals("Nov 1, 9:11 PM", enLabel("2026-11-01T21:11", now))
        assertEquals("Oct 19, 2024, 9:22 AM", enLabel("2024-10-19T09:22", now))
    }

    @Test fun midnightIsTheDayBoundary() {
        assertEquals(TimeLabels.Day.YESTERDAY, TimeLabels.day(ms("2026-11-07T23:59"), ms("2026-11-08T00:00"), seoul))
        assertEquals(TimeLabels.Day.TODAY, TimeLabels.day(ms("2026-11-08T00:00"), ms("2026-11-08T00:01"), seoul))
        assertEquals(TimeLabels.Day.THIS_YEAR, TimeLabels.day(ms("2026-11-06T23:59"), ms("2026-11-08T00:00"), seoul))
        // two messages a minute apart across midnight: a new day starts a new stretch
        assertTrue(TimeLabels.startsStretch(ms("2026-11-07T23:59"), ms("2026-11-08T00:00"), seoul))
    }

    @Test fun thirtyMinutesStartsAStretch() {
        assertFalse(TimeLabels.startsStretch(ms("2026-11-08T10:00"), ms("2026-11-08T10:29:59"), seoul))
        assertTrue(TimeLabels.startsStretch(ms("2026-11-08T10:00"), ms("2026-11-08T10:30"), seoul))
        assertTrue(TimeLabels.startsStretch(null, ms("2026-11-08T10:00"), seoul))
    }

    @Test fun newYear() {
        val now = ms("2027-01-01T08:00")
        assertEquals(TimeLabels.Day.YESTERDAY, TimeLabels.day(ms("2026-12-31T22:00"), now, seoul))
        assertEquals(TimeLabels.Day.EARLIER, TimeLabels.day(ms("2026-12-30T22:00"), now, seoul))   // last year: with the year
        assertEquals("어제 오후 10:00", koLabel("2026-12-31T22:00", "2027-01-01T08:00"))
        assertEquals("2026년 12월 30일 오후 10:00", koLabel("2026-12-30T22:00", "2027-01-01T08:00"))
    }

    @Test fun daylightSaving() {
        // 2026-03-08 in New York: 01:59 EST is followed by 03:00 EDT. 01:50 -> 03:10 is 20 real minutes: one stretch.
        assertFalse(TimeLabels.startsStretch(ms("2026-03-08T01:50", ny), ms("2026-03-08T03:10", ny), ny))
        // the 23-hour day is still "yesterday" the next morning
        assertEquals(TimeLabels.Day.YESTERDAY, TimeLabels.day(ms("2026-03-08T00:30", ny), ms("2026-03-09T00:10", ny), ny))
    }

    @Test fun onlyMessagesThatStartAStretch() {
        val items = listOf(
            ChatItem.Msg("a", mine = true, text = "hi", at = ms("2026-11-08T10:00")),
            ChatItem.Msg("b", mine = false, text = "hello", at = ms("2026-11-08T10:01")),
            ChatItem.Tool("t", "t1", "web_search", ToolState.OK),
            ChatItem.Msg("c", mine = true, text = "again", at = ms("2026-11-08T10:29")),
            ChatItem.Msg("d", mine = false, text = "later", at = ms("2026-11-08T11:00")),
            ChatItem.Msg("e", mine = true, text = "unknown time"),
            ChatItem.Msg("f", mine = false, text = "next day", at = ms("2026-11-09T00:01")),
        )
        assertEquals(setOf("a", "d", "f"), TimeLabels.labelled(items, seoul))
    }

    @Test fun liveMessagesAreDated() {
        val items = ChatReducer.apply(emptyList(), TurnEvent.Text("hi"), 1234L) { "k" }
        assertEquals(1234L, (items.single() as ChatItem.Msg).at)
        // more text goes on the same message and keeps its time
        val more = ChatReducer.apply(items, TurnEvent.Text("!"), 9999L) { "k2" }
        assertEquals(1234L, (more.single() as ChatItem.Msg).at)
    }
}
