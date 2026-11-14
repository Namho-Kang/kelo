package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import chat.kelo.app.ui.theme.Kelo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.pages.ConnectorsPane
import chat.kelo.desktop.pages.GoalsPane
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 다짐 and 커넥터 against tools/mock_server.py (tools/mock_parts/gc.py), in Korean: a goal's page with its plan, numbers, documents
 * and schedule (which opens 작업), Gmail offering only Gmail, the IMAP connector listing the server's services in the server's order,
 * the Google sign-in beta request, and the device-code flow. Screenshots: build/parity (or $KELO_SHOTS) parity-gc-*.png.
 */
class GoalsConnectorsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")
    private val ui by lazy { UiSteps(compose, parityShots()) }

    @After fun stop() { mock?.stop() }

    private fun start(): AppContainer {
        val m = MockServer("--lang", "ko").also { mock = it }
        compose.mainClock.autoAdvance = false
        return m.app(tmp.newFolder(), lang = "ko")
    }

    private fun both(app: AppContainer, name: String) {
        app.settings.setTheme("light"); ui.shot("parity-gc-$name-light")
        app.settings.setTheme("dark"); ui.shot("parity-gc-$name-dark")
        app.settings.setTheme("light"); ui.settle()
    }

    @Test fun aGoalOpensWithItsPlanNumbersDocumentsAndSchedule() {
        try { goals() } catch (e: Throwable) { runCatching { ui.shot("zz-goals-failed") }; throw e }
    }

    @Test fun connectorsOfferEveryWayAsOnThePhone() {
        try { connectors() } catch (e: Throwable) { runCatching { ui.shot("zz-connectors-failed") }; throw e }
    }

    private fun goals() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.fillMaxSize().background(Kelo.colors.bg)) { GoalsPane(app, topInset = 0.dp) } } }
        ui.waitText("매일 30분 걷기")
        ui.waitText(ko.get(Res.string.goals_suggest_title))
        both(app, "goals")
        compose.onAllNodesWithText("매일 30분 걷기").onFirst().performClick()
        ui.waitText(ko.get(Res.string.goal_numbers))
        ui.waitText("산책 코스 정리")   // a document
        ui.waitText(ko.get(Res.string.goal_fact_skipped))
        assertTrue(ui.has(ko.get(Res.string.goal_documents)))
        both(app, "goal-detail")
        // the schedule row is below the fold: scroll to it (with the clock running, as nothing on this page moves forever)
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithText("주간 걸음 수 확인").onLast().performScrollTo().performClick()
        compose.mainClock.autoAdvance = false
        ui.waitUntil("the schedule asked for") { app.openRequests.value != null }
        assertEquals(OpenTarget.Schedule(2), app.openRequests.value)
        compose.onAllNodesWithText(ko.get(Res.string.goals_add), useUnmergedTree = true).onFirst().performClick()   // the button's text is not in the merged tree
        ui.waitText(ko.get(Res.string.goals_due_pick))
        both(app, "goal-add")
    }

    private fun connectors() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.fillMaxSize().background(Kelo.colors.bg)) { ConnectorsPane(app, topInset = 0.dp) } } }
        ui.waitText("Gmail")
        ui.waitText(ko.get(Res.string.connectors_pinned))   // Notion is connected: pinned on top
        both(app, "connectors")

        // Gmail: Google sign-in waits for the review: the note, the beta request, and the app password offering only Gmail
        compose.onAllNodesWithText("Gmail").onFirst().performClick()
        ui.waitText(ko.get(Res.string.google_review_title))
        ui.waitText(ko.get(Res.string.beta_request))
        compose.onAllNodesWithText(ko.get(Res.string.beta_request)).onLast().performClick()
        ui.waitText(ko.get(Res.string.beta_sheet_note))
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("me@gmail.com")
        both(app, "gmail-beta")
        compose.onNodeWithText(ko.get(Res.string.beta_send)).performClick()
        ui.waitText(ko.get(Res.string.beta_requested))
        compose.onNodeWithText(ko.get(Res.string.mail_by_password)).performClick()
        ui.waitText(ko.get(Res.string.mail_password))
        assertFalse("Gmail offers no other mail service", ui.has("iCloud Mail"))
        assertTrue("no choice of service", compose.onAllNodesWithText(ko.get(Res.string.mail_provider)).fetchSemanticsNodes().isEmpty())
        both(app, "gmail-password")

        // IMAP: a choice of the server's services, in the server's order
        ui.retry("the IMAP page", action = { compose.onAllNodesWithText("메일 (IMAP)").onFirst().performClick() }) { ui.has(ko.get(Res.string.mail_provider)) }
        // the field shows the phone's default choice (네이버 메일) once the server's services are in
        ui.waitUntil("the service field") { compose.onAllNodesWithText("네이버 메일", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("네이버 메일", substring = true).onLast().performClick()   // the field opens its list
        ui.waitText("다음 메일")
        val order = listOf("iCloud Mail", "Yahoo Mail", "네이버 메일", "다음 메일").map { name ->
            compose.onAllNodesWithText(name).onLast().fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("the server's order", order.sorted(), order)
        both(app, "imap-providers")
        compose.onAllNodesWithText("Yahoo Mail").onLast().performClick()
        ui.waitUntil("the list closed") { compose.onAllNodesWithText("다음 메일").fetchSemanticsNodes().isEmpty() }
        ui.settle()

        // GitHub: a code to type on its page
        compose.onAllNodesWithText("GitHub").onFirst().performClick()
        val connect = ko.get(Res.string.device_connect, "GitHub")
        ui.waitText(connect)
        ui.settle()
        // not connected: no "permissions granted" (the connect panel has its own choice)
        assertFalse("no granted permission before connecting", ui.has(ko.get(Res.string.connector_permissions)))
        compose.onNodeWithText(connect).performClick()
        ui.waitText("WDJB-MJHT")
        both(app, "github-code")
    }
}
