package chat.kelo.app.chat

import chat.kelo.app.core.Languages
import chat.kelo.app.ui.platform.DesktopTimePatterns
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The time line's words ([TimeLabeler], with the desktop's JDK patterns). The phone's words come from ICU through the same
 * [TimeLabels] rules; its pattern call is the unchanged Android code (PlatformFunctions.android.kt).
 */
class TimeLabelerTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun ms(t: String) = LocalDateTime.parse(t).atZone(seoul).toInstant().toEpochMilli()
    private val now = ms("2026-11-08T23:00")

    private fun words(tag: String, today: String, yesterday: String, at: String) =
        TimeLabeler(Locale.forLanguageTag(tag), seoul, today, yesterday).label(ms(at), now).replace(' ', ' ')

    @Test fun korean() {
        assertEquals("오늘 오후 10:00", words("ko", "오늘", "어제", "2026-11-08T22:00"))
        assertEquals("어제 오전 9:31", words("ko", "오늘", "어제", "2026-11-07T09:31"))
        assertEquals("11월 1일 오후 9:11", words("ko", "오늘", "어제", "2026-11-01T21:11"))
        assertEquals("2024년 10월 19일 오전 9:22", words("ko", "오늘", "어제", "2024-10-19T09:22"))
        assertEquals("2025년 10월 19일 오전 9:22", words("ko", "오늘", "어제", "2025-10-19T09:22"))
    }

    @Test fun english() {
        assertEquals("Today 10:00 PM", words("en", "Today", "Yesterday", "2026-11-08T22:00"))
        assertEquals("Yesterday 9:31 AM", words("en", "Today", "Yesterday", "2026-11-07T09:31"))
        assertEquals("Nov 1, 9:11 PM", words("en", "Today", "Yesterday", "2026-11-01T21:11"))
        assertEquals("Oct 19, 2024, 9:22 AM", words("en", "Today", "Yesterday", "2024-10-19T09:22"))
    }

    @Test fun japanese() {
        assertEquals("今日 22:00", words("ja", "今日", "昨日", "2026-11-08T22:00"))
        assertEquals("11月1日 21:11", words("ja", "今日", "昨日", "2026-11-01T21:11"))
        assertEquals("2024年10月19日 9:22", words("ja", "今日", "昨日", "2024-10-19T09:22"))
    }

    @Test fun german() {
        assertEquals("Heute 22:00", words("de", "Heute", "Gestern", "2026-11-08T22:00"))
        assertEquals("Gestern 09:31", words("de", "Heute", "Gestern", "2026-11-07T09:31"))
    }

    @Test fun yearTakenOut() {
        assertEquals("MMM d, h:mm a", DesktopTimePatterns.withoutYear("MMM d, y, h:mm a"))
        assertEquals("M월 d일 a h:mm", DesktopTimePatterns.withoutYear("y년 M월 d일 a h:mm"))
        assertEquals("M月d日 H:mm", DesktopTimePatterns.withoutYear("y年M月d日 H:mm"))
        assertEquals("d MMM, HH:mm", DesktopTimePatterns.withoutYear("d MMM y 'г'., HH:mm"))
        assertEquals("d 'de' MMM HH:mm", DesktopTimePatterns.withoutYear("d 'de' MMM 'de' y HH:mm"))
        assertEquals("HH:mm, d MMM", DesktopTimePatterns.withoutYear("HH:mm, d MMM, y"))
    }

    @Test fun everyLanguageFormatsWithoutAYearThisYear() {
        Languages.ALL.forEach { lang ->
            val p = DesktopTimePatterns.of(Locale.forLanguageTag(lang.code))
            val thisYear = DateTimeFormatter.ofPattern(p.monthDayTime, Locale.forLanguageTag(lang.code)).withZone(seoul)
                .format(Instant.ofEpochMilli(ms("2026-11-01T21:11")))
            assertFalse(thisYear.contains("2026"), "${lang.code}: $thisYear")
            val earlier = DateTimeFormatter.ofPattern(p.fullDateTime, Locale.forLanguageTag(lang.code)).withZone(seoul)
                .format(Instant.ofEpochMilli(ms("2024-10-19T09:22")))
            assertTrue(earlier.contains("2024") || lang.code == "th", "${lang.code}: $earlier")
        }
    }
}
