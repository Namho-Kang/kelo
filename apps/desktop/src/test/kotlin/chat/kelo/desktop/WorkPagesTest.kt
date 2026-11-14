package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import chat.kelo.app.agent.TaskPermissionList
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.pages.WorkPane
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/**
 * The 작업 page against tools/mock_server.py (tools/mock_parts/work.py): the timeline's sections, the task-wide permission card and
 * its 그만 허용, a task opened from a notification (app.workRequests), the open browser tabs with their pictures, a job, a watcher
 * and a schedule. Screenshots in Korean, light and dark, go to $KELO_SHOTS (else build/parity) as parity-work-*.png.
 */
class WorkPagesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")

    @After fun stop() { mock?.stop() }

    @Test fun theWorkPageMatchesThePhone() {
        val ui = UiSteps(compose, parityShots())
        try { flow(ui) } catch (e: Throwable) { runCatching { ui.shot("parity-work-zz-failed") }; throw e }
    }

    private fun flow(ui: UiSteps) {
        val m = MockServer("--lang", "ko").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        compose.mainClock.autoAdvance = false
        compose.setContent { KeloFrame(app) { Box(Modifier.size(1100.dp, 1000.dp)) { WorkPane(app, topInset = 16.dp) } } }

        // the timeline: questions first while some wait (답이 필요해요 at the top), running work with its job, the task-wide
        // permission, watchers, the agenda and the past
        ui.waitText(ko.get(Res.string.work_asks))
        // the sections fill in as their calls come back: scroll again until the task-wide permission is in view
        ui.retry("the running work and its permission", action = { compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:now") }) {
            ui.has(ko.get(Res.string.work_now)) && ui.has(ko.get(Res.string.work_grant_title))
        }
        ui.waitText("항공사 세 곳 가격 확인")
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:asks")   // (above 지금 하는 일, with its waiting entries)
        ui.waitText(ko.get(Res.string.work_asks))
        for (theme in listOf("light", "dark")) {
            app.settings.setTheme(theme)
            ui.shot("parity-work-timeline-$theme")
        }
        app.settings.setTheme("light")
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:asks")
        ui.waitText("제주 숙소 후보 찾기")
        ui.waitText("택배 조회 사이트")
        ui.shot("parity-work-asks-light")
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:watch")
        ui.waitText("제주 항공권 가격")
        ui.shot("parity-work-timeline-lower-light")
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("title")
        ui.settle()

        // 그만 허용: the card goes and the server no longer has the permission
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:now")
        ui.waitText(ko.get(Res.string.work_grant_revoke))
        compose.onNode(hasText(ko.get(Res.string.work_grant_revoke))).performClick()
        ui.waitUntil("the permission card gone") { !ui.has(ko.get(Res.string.work_grant_title)) }
        val left = runBlocking { app.sessions.requireApi().get<TaskPermissionList>("/api/task-permissions") }
        assertTrue("the permission is revoked on the server", left.items.isEmpty())

        // a notification asked for task 1: its page opens with its answer
        app.workRequests.value = OpenTarget.Task(1)
        ui.waitText("다음 분기 계획")
        assertEquals(null, app.workRequests.value)
        shots(ui, app, "task")
        back()

        // 열린 브라우저: every tab with its last picture; a click asks the shell for the live view
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("title")
        ui.waitText(ko.get(Res.string.work_open_browsers))
        compose.onNode(hasText(ko.get(Res.string.work_open_browsers))).performClick()
        ui.waitText("항공권 검색 결과")
        ui.waitText("치과 예약 페이지")
        shots(ui, app, "browsers")
        compose.onNode(hasText("치과 예약 페이지")).performClick()
        ui.waitUntil("the live view asked for") { (app.openRequests.value as? OpenTarget.Tab)?.id == "7" }
        back()

        // a job, from the running task's card
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("sec:now")
        ui.waitText("항공사 세 곳 가격 확인")
        compose.onNode(hasText("항공사 세 곳 가격 확인")).performClick()
        ui.waitText("가장 싼 왕복 98,000원 확인")
        shots(ui, app, "job")
        back()

        // a watcher and a schedule, from notifications
        app.workRequests.value = OpenTarget.Watchers
        ui.waitText("제주 항공권 가격")
        ui.settle(30)
        compose.onNode(hasText("제주 항공권 가격")).performClick()
        ui.waitText("flights.example.com/cju")
        shots(ui, app, "watcher")
        back()
        app.workRequests.value = OpenTarget.Schedule(1)
        ui.waitText(ko.get(Res.string.work_schedule_runs))
        shots(ui, app, "schedule")
        back()

        // 예약: every schedule and the archive
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("title")
        ui.settle()
        compose.onNode(hasText(ko.get(Res.string.work_schedules))).performClick()
        ui.waitText(ko.get(Res.string.schedules_archive_entry))
        ui.waitText("아침 뉴스 요약")
        shots(ui, app, "schedules")

        // the trash button puts one in the archive at once; 실행 취소 brings it back
        compose.onAllNodesWithText("아침 뉴스 요약").onFirst().performTouchInput { swipeLeft() }   // a drag to the left, as on the phone
        ui.waitText(ko.get(Res.string.agent_schedule_deleted))
        ui.waitUntil("the row to leave") { !ui.has("아침 뉴스 요약") }
        ui.waitUntil("the archive call") { deleted(app).contains("/api/schedules/1") }
        compose.onNode(hasText(ko.get(Res.string.undo))).performClick()
        ui.waitText("아침 뉴스 요약")

        // 보관함: the archived ones; deleting one hides it at once and deletes it for good once the undo message is gone
        compose.onNode(hasText(ko.get(Res.string.schedules_archive_entry))).performClick()
        ui.waitText("여름 휴가 체크리스트")
        shots(ui, app, "archive")
        compose.onAllNodesWithText("여름 휴가 체크리스트").onFirst().performTouchInput { swipeLeft() }   // a drag to the left, as on the phone
        // (the "deleted" message is short-lived and may be gone before it is looked for: the row leaving and the calls below are the checks)
        ui.waitUntil("the row to leave") { !ui.has("여름 휴가 체크리스트") }
        assertTrue("not deleted before the undo message ends", "/api/schedules/archive/9" !in deleted(app))
        // leaving the page at once (before the message ends) must not drop the delete: it is sent as the page closes, and the
        // schedule does not come back when the archive is opened again
        back()
        ui.waitUntil("the delete for good, sent as the page closed", timeout = 3_000) { "/api/schedules/archive/9" in deleted(app) }
        compose.onNode(hasText(ko.get(Res.string.schedules_archive_entry))).performClick()
        ui.waitText(ko.get(Res.string.work_archive_empty))
        assertTrue("the deleted schedule came back", !ui.has("여름 휴가 체크리스트"))
        back()
        back()
    }

    private fun deleted(app: chat.kelo.desktop.core.AppContainer): List<String> = runBlocking {
        app.sessions.requireApi().get<kotlinx.serialization.json.JsonObject>("/mock/deleted")["items"]!!.let { items ->
            (items as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }
    }

    private fun shots(ui: UiSteps, app: chat.kelo.desktop.core.AppContainer, what: String) {
        for (theme in listOf("light", "dark")) {
            app.settings.setTheme(theme)
            ui.shot("parity-work-$what-$theme")
        }
        app.settings.setTheme("light")
    }

    private fun back() {
        compose.onAllNodes(hasContentDescription(ko.get(Res.string.back))).onFirst().performClick()
        compose.mainClock.advanceTimeBy(200)
    }
}
