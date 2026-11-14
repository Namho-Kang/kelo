package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.chat.AttachmentWords

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.ChatItem
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The chat as on the phone, against tools/mock_server.py (tools/mock_parts/chat.py): questions from a background job wait at the
 * bottom of the chat and fold into one line once answered; a human check that ended on the server folds to its outcome
 * ("resolved"); a message's follow-up buttons work; a browser card's 보기 opens the tab its pictures came from, live (zoomable).
 * Screenshots (ko, light and dark) go to $KELO_SHOTS or build/parity as parity-chat-*.png.
 */
class ChatParityTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")

    @After fun stop() { mock?.stop() }

    @Test fun chatMatchesThePhone() {
        val ui = UiSteps(compose, parityShots())
        try { flow(ui) } catch (e: Throwable) { runCatching { ui.shot("parity-chat-zz-failed") }; throw e }
    }

    private fun both(ui: UiSteps, app: chat.kelo.desktop.core.AppContainer, name: String) {
        ui.shot("parity-chat-$name-light")
        app.settings.setTheme("dark")
        ui.shot("parity-chat-$name-dark")
        app.settings.setTheme("light")
        ui.settle()
    }

    private fun flow(ui: UiSteps) {
        val m = MockServer("--lang", "ko").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(1024.dp, 768.dp)) {
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }

        // 1. the background job's two questions at the bottom: an approval (the server's own question) and a human check
        ui.waitText("항공권 결제 페이지를 열까요?")
        ui.waitText(ko.get(Res.string.handover_title, "flights.example.com"))
        ui.waitText(ko.get(Res.string.chat_ask_titled, ko.get(Res.string.chat_ask_from_job), "제주 항공권 찾기"))
        assertTrue(ui.has(ko.get(Res.string.approval_remember_site)))
        // the follow-up under the newest message
        ui.waitText(ko.get(Res.string.followup_action_drop))
        both(ui, app, "pending-asks")

        // 2. allowing the job's approval folds it into one line
        compose.onAllNodesWithText(ko.get(Res.string.chat_approval_allow)).onLast().performClick()
        ui.waitText(ko.get(Res.string.chat_ask_resolved, "결제 페이지 열기", ko.get(Res.string.agent_approval_allowed)))

        // 3. the human check ends on the server (solved in the live view elsewhere): it folds to "solved" from the list's "resolved"
        ui.waitText(ko.get(Res.string.handover_solved), timeout = 30_000)

        // 4. a follow-up button: the buttons give way to the line that says what was done
        val drop = compose.onAllNodes(androidx.compose.ui.test.hasText(ko.get(Res.string.followup_action_drop)) and androidx.compose.ui.test.hasClickAction())
        println("drop buttons: " + drop.fetchSemanticsNodes().map { it.boundsInRoot })
        drop.onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.settle()
        runCatching { ui.waitUntil("the follow-up answered") { chat.state.value.outcomes.isNotEmpty() } }
            .onFailure { throw AssertionError("follow-up: acting=${chat.state.value.acting} outcomes=${chat.state.value.outcomes} items=${chat.state.value.items.takeLast(2)}") }
        ui.waitText(ko.get(Res.string.followup_done_dropped))
        both(ui, app, "folded")

        // 5. a reply with a browser card: 보기 opens that card's own tab live (with the card's picture while nothing live shows)
        chat.setInput("항공권 찾아줘")
        chat.send(words = AttachmentWords({ it }, { it }, ", "))
        ui.waitUntil("the browser card") { chat.state.value.items.any { it is ChatItem.Browser } }
        ui.waitText(ko.get(Res.string.chat_browser_view_short))
        both(ui, app, "browser-card")
        compose.onAllNodesWithText(ko.get(Res.string.chat_browser_view_short)).onLast().performClick()
        ui.waitUntil("the live view") { app.liveTab.value != null }
        assertEquals("task:w-mock", app.liveTab.value!!.id)
        assertEquals(false, app.liveTab.value!!.control)
        assertTrue(app.liveTab.value!!.picture?.startsWith("data:image/") == true)
        ui.waitText(ko.get(Res.string.desktop_chat_zoom_hint, "⌘").substringAfter("⌘").take(8))
        repeat(3) { compose.onAllNodesWithContentDescription(ko.get(Res.string.desktop_chat_zoom_in)).onFirst().performClick(); ui.settle(2) }
        ui.waitText("195%")
        both(ui, app, "live-zoomed")
        chatHandle.close()
    }
}
