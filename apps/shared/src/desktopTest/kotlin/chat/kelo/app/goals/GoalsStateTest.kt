package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import org.junit.Assert.assertEquals
import org.junit.Test

class GoalsStateTest {
    private val goals = listOf(
        Goal(1, "운동"), Goal(2, "독서", status = Goal.STATUS_DONE), Goal(3, "물 마시기"),
    )

    @Test fun swipedGoalsAreHiddenUntilUndone() {
        val s = GoalsState(goals, loaded = true, removing = setOf(3L, 2L))
        assertEquals(listOf(1L), s.tracking.map { it.id })
        assertEquals(emptyList<Long>(), s.done.map { it.id })
        val undone = s.copy(removing = s.removing - 2L)
        assertEquals(listOf(2L), undone.done.map { it.id })
    }
}
