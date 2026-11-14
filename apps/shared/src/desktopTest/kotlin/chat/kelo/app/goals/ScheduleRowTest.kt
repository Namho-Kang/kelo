package chat.kelo.app.goals

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.ui.theme.KeloTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A schedule shown in a goal opens on the Work tab with a click (phone and desktop alike). */
@OptIn(ExperimentalTestApi::class)
class ScheduleRowTest {
    @Test fun clickingAScheduleRowEmitsItsOpenTarget() = runComposeUiTest {
        val seen = mutableListOf<OpenTarget>()
        setContent { KeloTheme(dark = false) { ScheduleRow(GoalSchedule(id = 7, name = "Weekly check", description = "Sundays 20:00")) { seen += it } } }
        onNodeWithText("Weekly check").performClick()
        assertEquals(listOf<OpenTarget>(OpenTarget.Schedule(7)), seen)
    }
}
