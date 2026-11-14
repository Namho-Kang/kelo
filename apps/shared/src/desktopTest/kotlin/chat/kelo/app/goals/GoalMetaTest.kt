package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A goal's due date and progress, kept on the last line of its note. */
class GoalMetaTest {
    private val d = LocalDate.of(2026, 11, 20)

    @Test fun roundTrips() {
        listOf(GoalMeta("매일 30분", d, 40), GoalMeta("", d, null), GoalMeta("메모만", null, null), GoalMeta("", null, 100)).forEach { m ->
            assertEquals(m, GoalMetaCodec.parse(GoalMetaCodec.encode(m)))
        }
        assertEquals("매일 30분\n[due:2026-11-20 progress:40]", GoalMetaCodec.encode(GoalMeta("매일 30분 ", d, 40)))
        assertEquals("[progress:0]", GoalMetaCodec.encode(GoalMeta("", null, 0)))
        assertEquals("plain", GoalMetaCodec.encode(GoalMeta("plain")))
    }

    @Test fun oldNotesAndOddValues() {
        assertEquals(GoalMeta("그냥 메모"), GoalMetaCodec.parse("그냥 메모"))
        val bad = GoalMetaCodec.parse("x\n[due:2026-13-40 progress:250]")
        assertEquals("x", bad.note); assertNull(bad.due); assertEquals(100, bad.progress)
        // the line only counts at the end
        assertEquals("[due:2026-11-20] 다음 줄", GoalMetaCodec.parse("[due:2026-11-20] 다음 줄").note)
    }

    @Test fun nearestDueFirstThenTheRestInOrder() {
        val goals = listOf(
            Goal(1, "a"), Goal(2, "b", note = "[due:2026-12-01]"), Goal(3, "c"), Goal(4, "d", note = "x\n[due:2026-11-10 progress:20]"),
        )
        assertEquals(listOf(4L, 2L, 1L, 3L), GoalsRules.ordered(goals).map { it.id })
        assertTrue(GoalsRules.overdue(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)))
        assertFalse(GoalsRules.overdue(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7)))
        assertFalse(GoalsRules.overdue(null, LocalDate.of(2026, 11, 7)))
        assertEquals("건강", GoalsRules.tag("  건강 "))
        assertEquals(40, GoalsRules.tag("x".repeat(60)).length)
    }
}
