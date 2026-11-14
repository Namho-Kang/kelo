package chat.kelo.app.my

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The learned message hours: reading GET /api/proactive, windows past midnight, when the review row shows, and its calls. */
class ContactHoursRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun readsTheSuggestionAndWhereTheHoursCameFrom() {
        val body = """{"enabled": true, "wake_start": "07:00", "wake_end": "22:00", "auto_hours": false, "hours_source": "default",
            "guard": {"owner_spoke": true}, "hours_suggestion": {"id": "a1b2c3", "wake_start": "10:00", "wake_end": "01:00",
            "current_start": "07:00", "current_end": "22:00", "active_days": 12, "created_at": 1762650000.5}}"""
        val h = json.decodeFromString<ContactHours>(body)
        assertEquals("a1b2c3", h.suggestion?.id)
        assertEquals(12, h.suggestion?.activeDays)
        assertTrue(ContactHoursRules.shown(h.suggestion))
        assertFalse(ContactHoursRules.learned(h.hoursSource))
        // an older server: no suggestion, no source
        val old = json.decodeFromString<ContactHours>("""{"enabled": false, "wake_start": "08:00", "wake_end": "21:00"}""")
        assertNull(old.suggestion)
        assertEquals("default", old.hoursSource)
        assertFalse(old.autoHours)
        assertTrue(ContactHoursRules.learned(json.decodeFromString<ContactHours>("""{"hours_source": "learned"}""").hoursSource))
    }

    @Test fun windowsPastMidnight() {
        assertTrue(ContactHoursRules.pastMidnight("10:00", "01:00"))
        assertTrue(ContactHoursRules.pastMidnight("23:30", "00:00"))
        assertFalse(ContactHoursRules.pastMidnight("07:00", "22:00"))
        assertFalse(ContactHoursRules.pastMidnight("07:00", "07:00"))     // the whole day
        assertFalse(ContactHoursRules.pastMidnight("bad", "01:00"))
        assertEquals(23 * 60 + 30, ContactHoursRules.minutes("23:30"))
        assertEquals(7 * 60, ContactHoursRules.minutes(" 7:00 "))
        assertNull(ContactHoursRules.minutes("24:00"))
        assertNull(ContactHoursRules.minutes("7"))
    }

    @Test fun onlyAUsableDifferentSuggestionIsShown() {
        val s = HoursSuggestion("x", "10:00", "01:00", "07:00", "22:00", 9)
        assertTrue(ContactHoursRules.shown(s))
        assertFalse(ContactHoursRules.shown(null))
        assertFalse(ContactHoursRules.shown(s.copy(id = "")))
        assertFalse(ContactHoursRules.shown(s.copy(wakeEnd = "25:00")))
        assertFalse(ContactHoursRules.shown(s.copy(currentStart = "10:00", currentEnd = "01:00")))   // already set
        assertTrue(ContactHoursRules.shown(s.copy(currentStart = "10:00")))                           // one end differs
    }

    @Test fun acceptAndKeepCallsEncodeTheId() {
        assertEquals("/api/proactive/hours/a1b2c3/accept", ContactHoursRules.acceptPath("a1b2c3"))
        assertEquals("/api/proactive/hours/a%2Fb/keep", ContactHoursRules.keepPath("a/b"))
        assertEquals(KeloApi.segment("a b"), "a%20b")
    }

    @Test fun weekdayAndWeekendHoursTheirSuggestionsAndLearnedWindows() {
        val body = """{"wake_start": "07:00", "wake_end": "22:00", "hours_source": "manual", "weekend_start": "07:00", "weekend_end": "22:00",
            "weekend_source": "default", "holiday_country": "KR", "day_type": "weekend",
            "weekend_suggestion": {"id": "w1", "day_type": "weekend", "wake_start": "10:00", "wake_end": "23:30", "current_start": "07:00",
              "current_end": "22:00", "active_days": 8},
            "learned": {"weekday": {"wake_start": "09:00", "wake_end": "23:00", "active_days": 20}, "weekend": null}}"""
        val h = json.decodeFromString<ContactHours>(body)
        assertEquals(Triple("07:00", "22:00", "default"), ContactHoursRules.window(h, ContactHoursRules.WEEKEND))
        assertEquals(Triple("07:00", "22:00", "manual"), ContactHoursRules.window(h, ContactHoursRules.WEEKDAY))
        assertEquals("w1", ContactHoursRules.open(h)?.id)
        assertEquals(ContactHoursRules.WEEKEND, ContactHoursRules.open(h)?.dayType)
        assertEquals("09:00", ContactHoursRules.learnedFor(h, ContactHoursRules.WEEKDAY)?.wakeStart)
        assertNull(ContactHoursRules.learnedFor(h, ContactHoursRules.WEEKEND))
        assertNull(ContactHoursRules.learnedFor(h.copy(wakeStart = "09:00", wakeEnd = "23:00"), ContactHoursRules.WEEKDAY))   // already in effect
        assertEquals("weekend_start", ContactHoursRules.field(ContactHoursRules.WEEKEND, true))
        assertEquals("wake_end", ContactHoursRules.field(ContactHoursRules.WEEKDAY, false))
        assertEquals("/api/proactive/hours/learned/weekend/apply", ContactHoursRules.applyPath("weekend"))
        // an older server: weekday hours only, no weekend fields
        val old = json.decodeFromString<ContactHours>("""{"wake_start": "08:00", "wake_end": "21:00"}""")
        assertNull(old.holidayCountry)
        assertEquals(Triple("08:00", "21:00", "default"), ContactHoursRules.window(old, ContactHoursRules.WEEKEND))
        assertNull(ContactHoursRules.open(old))
    }

    @Test fun timeButtonsShareARowOnlyWhenBothFitInHalf() {
        // 320dp phone: about 256dp for the two buttons, 10dp apart
        assertTrue(ContactHoursRules.timesSideBySide(110, 100, 256, 10))
        assertTrue(ContactHoursRules.timesSideBySide(123, 123, 256, 10))
        // "다음 날 오전 1:00까지" is wider than half although the two together would fit: stack, so nothing wraps
        assertFalse(ContactHoursRules.timesSideBySide(80, 150, 256, 10))
        assertFalse(ContactHoursRules.timesSideBySide(124, 100, 256, 10))
        assertFalse(ContactHoursRules.timesSideBySide(0, 0, 0, 10))     // not measured yet
    }
}
