package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A goal's live state on its row: Kelo working beats waiting for the user; the list is fetched again while either shows. */
class GoalActivityTest {
    @Test fun workingWinsOverWaitingAndDoneShowsNeither() {
        assertEquals(GoalActivity.WORKING, GoalsRules.activity(Goal(1, "a", asking = true, working = true)))
        assertEquals(GoalActivity.ASKING, GoalsRules.activity(Goal(1, "a", asking = true)))
        assertNull(GoalsRules.activity(Goal(1, "a")))
        assertNull(GoalsRules.activity(Goal(1, "a", status = Goal.STATUS_DONE, asking = true, working = true)))
    }

    @Test fun refreshesSoonWhileWorkingLessOftenWhileWaitingElseNever() {
        assertEquals(GoalsRules.WORKING_REFRESH_MS, GoalsRules.refreshMs(listOf(Goal(1, "a", asking = true), Goal(2, "b", working = true))))
        assertEquals(GoalsRules.ASKING_REFRESH_MS, GoalsRules.refreshMs(listOf(Goal(1, "a", asking = true))))
        assertNull(GoalsRules.refreshMs(listOf(Goal(1, "a"))))
    }

    @Test fun anOlderServerHasNoWorkingField() {
        val g = Json { ignoreUnknownKeys = true }.decodeFromString<Goal>("""{"id":1,"title":"a","asking":true}""")
        assertEquals(false, g.working)
        assertEquals(GoalActivity.ASKING, GoalsRules.activity(g))
    }
}
