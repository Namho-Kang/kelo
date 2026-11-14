package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.Goal
import chat.kelo.app.goals.GoalsRules
import chat.kelo.app.goals.GoalsState
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.pages.GoalsPane
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deleting a goal on the desktop, as on the phone: a goal with schedules on asks "이 다짐에 묶인 예약 N개도 끌까요?" first (turn
 * them off / keep them / cancel); then the usual undo offer; the real delete after it says ?schedules=delete or keep. A goal without
 * schedules asks nothing. Against tools/mock_server.py (goal 1 has two schedules on). Screenshots: v18-goal-delete-ko-*.png.
 */
class GoalDeleteTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")

    @After fun stop() { mock?.stop() }

    @Test fun theRuleAsksOnlyWithSchedulesOnAndSaysWhatToDoWithThem() {
        assertTrue(GoalsRules.asksAboutSchedules(Goal(1, "a", schedulesOn = 2)))
        assertFalse(GoalsRules.asksAboutSchedules(Goal(1, "a")))
        assertEquals("/api/goals/3?schedules=delete", GoalsRules.deletePath(3, true))
        assertEquals("/api/goals/3?schedules=keep", GoalsRules.deletePath(3, false))
        val s = GoalsState(listOf(Goal(1, "a"), Goal(2, "b")), removing = setOf(1L), pauseSchedules = setOf(1L))
        assertEquals(listOf(2L), s.tracking.map { it.id })
    }

    @Test fun aGoalWithSchedulesAsksFirstAndTheDeleteCarriesTheChoice() {
        val ui = UiSteps(compose, parityShots())
        try { flow(ui) } catch (e: Throwable) { runCatching { ui.shot("v18-goal-delete-zz-failed") }; throw e }
    }

    private fun deleted(app: AppContainer): List<String> = runBlocking {
        app.sessions.requireApi().get<JsonObject>("/mock/deleted")["items"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun bin(title: String) =
        compose.onAllNodes(hasContentDescription(ko.get(Res.string.delete)) and hasParent(hasText(title, substring = true))).onFirst()

    private fun undoWindowPasses(ui: UiSteps) {
        compose.mainClock.advanceTimeBy(11_000)
        ui.settle()
    }

    private fun flow(ui: UiSteps) {
        val m = MockServer("--lang", "ko").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        compose.mainClock.autoAdvance = false
        compose.setContent { KeloFrame(app) { Box(Modifier.fillMaxSize().background(Kelo.colors.bg)) { GoalsPane(app, topInset = 0.dp) } } }
        ui.waitText("매일 30분 걷기")
        val question = ko.plural(Res.plurals.goals_delete_schedules_q, 2, 2)

        // 1. two schedules on: the question comes first; cancel leaves the goal where it was
        bin("매일 30분 걷기").performClick()
        ui.waitText(question)
        ui.waitText(ko.get(Res.string.goals_delete_schedules_body))
        app.settings.setTheme("light"); ui.shot("v18-goal-delete-ko-light")
        app.settings.setTheme("dark"); ui.shot("v18-goal-delete-ko-dark")
        app.settings.setTheme("light")
        compose.onAllNodes(hasText(ko.get(Res.string.cancel)) and hasClickAction()).onFirst().performClick()
        ui.settle()
        assertFalse(ui.has(question))
        assertTrue(ui.has("매일 30분 걷기"))

        // 2. delete them too: the undo offer, then the delete with schedules=delete
        bin("매일 30분 걷기").performClick()
        ui.waitText(question)
        compose.onAllNodes(hasText(ko.get(Res.string.goals_delete_schedules_off)) and hasClickAction()).onFirst().performClick()
        ui.waitText(ko.get(Res.string.goals_removed, "매일 30분 걷기"))
        undoWindowPasses(ui)
        ui.waitUntil("the delete") { deleted(app).isNotEmpty() }
        assertEquals(listOf("/api/goals/1?schedules=delete"), deleted(app))

        // 3. no schedules: no question, and the delete keeps (there is nothing to pause)
        bin("영어 회화 3개월").performClick()
        ui.waitText(ko.get(Res.string.goals_removed, "영어 회화 3개월"))
        assertFalse(ui.has(ko.plural(Res.plurals.goals_delete_schedules_q, 1, 1)))
        undoWindowPasses(ui)
        ui.waitUntil("the second delete") { deleted(app).size == 2 }
        assertEquals("/api/goals/2?schedules=keep", deleted(app)[1])
    }
}
