package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalDeleteRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theScheduleQuestionComesOnlyWhenSchedulesAreOn() {
        assertFalse(GoalsRules.asksAboutSchedules(Goal(1, "운동")))
        assertTrue(GoalsRules.asksAboutSchedules(Goal(1, "운동", schedulesOn = 2)))
        // an older server does not send the count: no question, the delete keeps the schedules as before
        assertEquals(0, json.decodeFromString<Goal>("""{"id": 3, "title": "독서"}""").schedulesOn)
        assertEquals(2, json.decodeFromString<Goal>("""{"id": 3, "title": "독서", "schedules_on": 2}""").schedulesOn)
    }

    @Test fun theRealDeleteCarriesTheChoice() {
        assertEquals("/api/goals/7?schedules=delete", GoalsRules.deletePath(7, true))
        assertEquals("/api/goals/7?schedules=keep", GoalsRules.deletePath(7, false))
    }

    @Test fun theChoiceIsKeptPerSwipedGoalUntilUndone() {
        val s = GoalsState(listOf(Goal(1, "a"), Goal(2, "b")), loaded = true, removing = setOf(1L, 2L), pauseSchedules = setOf(2L))
        assertEquals(emptyList<Long>(), s.tracking.map { it.id })
        assertTrue(2L in s.pauseSchedules && 1L !in s.pauseSchedules)
    }
}
