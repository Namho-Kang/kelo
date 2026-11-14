package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

/** The typed answer fields: the plain forms the server checks too, and the words the chat shows (desktop runs it too). */
class ReplyFieldsTest {
    private fun q(kind: String, min: Double? = null, max: Double? = null, unit: String = "", default: String = "") =
        ReplyQuestion("Q", kind = kind, min = min, max = max, unit = unit, defaultValue = default)

    @Test fun kindsAreReadAndAnUnknownOneFallsBack() {
        assertEquals(ReplyFields.CHOICE, ReplyFields.kind(ReplyQuestion("Q", listOf("a", "b"))))
        assertEquals(ReplyFields.DATE, ReplyFields.kind(q(" Date ")))
        assertEquals(ReplyFields.CHOICE, ReplyFields.kind(ReplyQuestion("Q", listOf("a", "b"), kind = "colour")))   // a newer server
        assertEquals(ReplyFields.TEXT, ReplyFields.kind(q("colour")))
    }

    @Test fun answersAreCheckedLikeTheServerDoes() {
        assertEquals("2026-11-10", ReplyFields.check(q("date"), "2026-11-10"))
        assertNull(ReplyFields.check(q("date"), "2026-02-30"))
        assertEquals("09:05", ReplyFields.check(q("time"), "9:05"))
        assertNull(ReplyFields.check(q("time"), "24:00"))
        assertEquals("2026-01-02 07:00", ReplyFields.check(q("datetime"), "2026-01-02T7:00"))
        assertEquals("mon,wed", ReplyFields.check(q("weekdays"), "wed, mon mon"))
        assertNull(ReplyFields.check(q("weekdays"), "someday"))
        assertEquals("mon,fri 07:00", ReplyFields.check(q("weekdays_time"), "fri,mon 7:00"))
        assertNull(ReplyFields.check(q("weekdays_time"), "mon"))
        assertEquals("1000", ReplyFields.check(q("number"), "1,000"))
        assertEquals("2.5", ReplyFields.check(q("number"), "2.5"))
        assertNull(ReplyFields.check(q("number", min = 1.0), "0"))
        assertNull(ReplyFields.check(q("number"), "NaN"))
        assertNull(ReplyFields.check(q("text"), "   "))
        assertEquals("21:30", ReplyFields.start(q("time", default = "21:30")))
        assertNull(ReplyFields.start(q("time")))
        assertEquals("1" to "5", ReplyFields.range(q("number", 1.0, 5.0)))
        assertNull(ReplyFields.range(q("number", 1.0)))
    }

    private val ko = ReplyFields.Words("매일", "매주 %1\$s", "매일 %1\$s", "매주 %1\$s %2\$s", "%1\$s %2\$s", ", ")
    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    @Test fun answersShowInTheUsersWords() {
        val sun = DayOfWeek.SUNDAY
        val mon = DayOfWeek.MONDAY
        assertEquals("매주 월, 수 오후 12:00", plain(ReplyFields.display(q("weekdays_time"), "mon,wed 12:00", Locale.KOREAN, ko, mon)))
        assertEquals("매주 일, 월", plain(ReplyFields.display(q("weekdays"), "mon,sun", Locale.KOREAN, ko, sun)))   // the week's first day first
        assertEquals("매일 오전 7:00", plain(ReplyFields.display(q("weekdays_time"), "mon,tue,wed,thu,fri,sat,sun 07:00", Locale.KOREAN, ko, mon)))
        assertEquals("Every Mon, Wed at 12:00 PM", plain(ReplyFields.display(q("weekdays_time"), "mon,wed 12:00", Locale.ENGLISH, ReplyFields.Words(), sun)))
        assertEquals("Weekly on Sat", ReplyFields.display(q("weekdays"), "sat", Locale.ENGLISH, ReplyFields.Words(), sun))
        assertEquals("2026년 11월 10일 화요일", ReplyFields.display(q("date"), "2026-11-10", Locale.KOREAN, ko, mon))
        assertEquals("Tuesday, November 10, 2026 at 2:30 PM",
            plain(ReplyFields.display(q("datetime"), "2026-11-10 14:30", Locale.ENGLISH, ReplyFields.Words(), sun)))
        assertEquals("3 pcs", ReplyFields.display(q("number", unit = "pcs"), "3", Locale.ENGLISH, ReplyFields.Words(), sun))
        assertEquals("3개", ReplyFields.display(q("number", unit = "개"), "3", Locale.KOREAN, ko, mon))
        assertEquals("50%", ReplyFields.display(q("number", unit = "%"), "50", Locale.ENGLISH, ReplyFields.Words(), sun))
        assertEquals("14:30", ReplyFields.display(q("time"), "14:30", Locale.ENGLISH, ReplyFields.Words(), sun, formatTime = { "%02d:%02d".format(it.hour, it.minute) }))
    }

    @Test fun daysToggleFreelyAndFollowTheWeek() {
        assertEquals(setOf(DayOfWeek.MONDAY), ReplyFields.toggle(emptySet(), DayOfWeek.MONDAY))
        assertEquals(emptySet<DayOfWeek>(), ReplyFields.toggle(setOf(DayOfWeek.MONDAY), DayOfWeek.MONDAY))
        assertEquals(DayOfWeek.SUNDAY, ReplyFields.weekOrder(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SUNDAY, ReplyFields.weekOrder(DayOfWeek.MONDAY).last())
        assertEquals("tue,sun", ReplyFields.daysPlain(setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY)))
    }
}
