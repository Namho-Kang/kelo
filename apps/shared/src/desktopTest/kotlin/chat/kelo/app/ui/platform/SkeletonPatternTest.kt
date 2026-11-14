package chat.kelo.app.ui.platform

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dates the desktop writes, without Android's skeleton patterns (moved from the desktop's WorkPagesTest). */
class SkeletonPatternTest {
    @Test fun theYearIsTakenOut() {
        assertEquals("MMM d", SkeletonPattern.withoutYear("MMM d, y"))
        assertEquals("dd.MM", SkeletonPattern.withoutYear("dd.MM.y"))
        assertEquals("M월 d일", SkeletonPattern.withoutYear("y년 M월 d일"))
        assertEquals("d MMM", SkeletonPattern.withoutYear("d MMM y"))
    }

    @Test fun theWeekdayGoesWhereTheLanguageWantsIt() {
        assertEquals("MMM d일 (EEE)", bestDatePattern("MMMdEEE", Locale.KOREAN))
        assertTrue(bestDatePattern("MMMdEEE", Locale.ENGLISH).startsWith("EEE, "))
        assertTrue('y' !in bestDatePattern("MMMdEEE", Locale.GERMAN))
        assertTrue('y' in bestDatePattern("yMMMdEEE", Locale.GERMAN))
    }
}
