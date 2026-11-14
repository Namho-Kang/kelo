package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToKey
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
 * The smarter morning briefing card in the chat (headline, "worth noticing" notes with reference chips, next steps), drawn from the
 * mock's realistic card JSON (KELO_MOCK_CATCHUP=1; KELO_MOCK_BRIEFING=long for the long-text variant), in Korean and English, in a
 * narrow chat pane (412 dp) and a wide one (1024 dp), and at 320 dp. Saved as briefing-smart-*.png in $KELO_SHOTS (else build/parity);
 * the ones worth keeping are copied to apps/desktop/screens/. Taps: a next step writes its text into the composer, a chip does what a tap
 * on its item does (a mail chip writes the mail question), nothing is sent.
 */
class BriefingSmartShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun koreanNarrow() = flow("ko", 412, long = false)
    @Test fun koreanWide() = flow("ko", 1024, long = false)
    @Test fun englishNarrow() = flow("en", 412, long = false)
    @Test fun englishWide() = flow("en", 1024, long = false)
    @Test fun englishNarrowestLong() = flow("en", 320, long = true)
    @Test fun englishNarrowLong() = flow("en", 412, long = true)
    @Test fun koreanNarrowestLong() = flow("ko", 320, long = true)
    @Test fun koreanWideLong() = flow("ko", 1024, long = true)

    private fun flow(lang: String, width: Int, long: Boolean) {
        val words = Words.of(lang)
        val env = mapOf("KELO_MOCK_CATCHUP" to "1") + if (long) mapOf("KELO_MOCK_BRIEFING" to "long") else emptyMap()
        val m = MockServer("--lang", lang, env = env).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) { Box(Modifier.size(width.dp, 1300.dp)) { Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side) } }
        }
        val name = "briefing-smart-" + (if (long) "long-" else "") + "$lang-$width"
        try {
            ui.waitUntil("the chat loaded", timeout = 15_000) { chat.state.value.items.any { it is ChatItem.Msg && it.briefing != null } }
            val card = chat.state.value.items.filterIsInstance<ChatItem.Msg>().first { it.briefing != null }.briefing!!
            assertTrue(card.connections.size == 2 && card.suggestions.size == 2)
            compose.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("m3")
            ui.settle(12)
            ui.waitText(words.get(Res.string.briefing_connections))
            ui.waitText(words.get(Res.string.briefing_steps))
            ui.shot("$name-steps")
            if (!long) {
                // a next step writes its text into the composer; nothing is sent
                val step = card.suggestions[0].text
                val sent = chat.state.value.items.size
                compose.onAllNodes(hasText(step)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                ui.waitUntil("the step in the composer") { chat.state.value.input == step }
                assertEquals(sent, chat.state.value.items.size)
            }
            // the card is taller than the window: the same card scrolled up to its top (headline, notes with their chips, weather)
            val list = compose.onAllNodes(hasScrollToKeyAction()).onFirst()
            val titleTop = { compose.onAllNodes(hasText(words.get(Res.string.briefing_title))).onFirst().fetchSemanticsNode().positionInRoot.y }
            var part = 0
            while (titleTop() < 110f && part < 14) {   // 110: just under the floating agent's tag
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, if (titleTop() < -200f) 300f else 120f) }
                ui.settle(12)
                part++
            }
            ui.shot(name)
            // and a little further down: the rest of the notes and their chips under the headline
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -330f) }
            ui.settle(12)
            ui.shot("$name-mid")
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 330f) }
            ui.settle(12)
            if (!long) {
                // a chip does what a tap on its item does: the contract mail's chip writes the mail question
                val subject = if (lang == "ko") "계약서 검토 요청" else "Contract review request"
                compose.onAllNodes(hasText(subject)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                ui.waitUntil("the mail question in the composer") { chat.state.value.input == words.get(Res.string.briefing_mail_ask, subject) }
            }
        } catch (e: Throwable) {
            runCatching { ui.shot("$name-zz-failed") }
            throw e
        }
    }
}
