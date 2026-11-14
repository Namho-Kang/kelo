package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.ChatItem
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The composer floats over the messages: no opaque band behind it, the conversation runs on under it and fades out in its scrim,
 * and the list keeps room for the composer so its last row can scroll fully above the pill. Against tools/mock_server.py --form
 * (a long last message with a form); screenshots (ko and en, light and dark, wide and narrow) go to $KELO_SHOTS or build/parity as
 * composer-*.png: the newest rows at rest, then scrolled a little (with a long draft) so text passes under the fade and the "↓" shows on the
 * composer's top border.
 */
class ComposerShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun korean() = flow("ko", wide = true)

    @Test fun english() = flow("en", wide = true)

    @Test fun koreanNarrow() = flow("ko", wide = false)

    @Test fun englishNarrow() = flow("en", wide = false)

    private fun flow(lang: String, wide: Boolean) {
        val m = MockServer("--lang", lang, "--form", "--reactions").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(if (wide) 1024.dp else 480.dp, 760.dp)) {
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        val size = if (wide) "wide" else "narrow"
        fun shots(name: String) {
            ui.shot("composer-$lang-$size-$name-light")
            app.settings.setTheme("dark")
            ui.shot("composer-$lang-$size-$name-dark")
            app.settings.setTheme("light")
            ui.settle()
        }
        try {
            ui.waitUntil("the history") { chat.state.value.items.count { it is ChatItem.Msg } >= 4 }
            ui.settle(20)
            val newest = if (lang == "ko") "골라서 보내거나" else "Pick one to send"   // the hint under the form, the newest row
            // at rest: the newest row ends above the pill (the list keeps room for the composer's measured height)
            val list = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.height }
            val field = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }.boundsInRoot
            assertTrue("the list runs on under the composer: ${list.boundsInRoot} / $field", list.boundsInRoot.bottom >= field.bottom)
            shots("rest")
            // a long draft: the "↓" (on the card's top border once the list is scrolled up) must stay clear of its first line
            chat.setInput(if (lang == "ko") "수요일 오후에 치과 예약 잡아 주고, 하루 전에 한 번 더 알려 줘. 그리고 주차할 곳이 있는지도 확인해 주고, 없으면 근처 공영주차장 위치랑 요금도 같이 찾아봐 줘."
                else "Book the dentist for Wednesday afternoon and remind me the day before. Also check whether there is parking, and if not, find the nearest public car park with its prices.")
            ui.settle(6)
            // scrolled back a little: the rows pass under the composer and fade out
            val i = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().let { l -> l.indices.maxBy { l[it].boundsInRoot.height } }
            // a row that is on screen now (the newest message, or the background job's question below it) shows which way the list moved
            val probe = listOf(newest, if (lang == "ko") "직접 풀기" else "Solve it")
                .firstOrNull { compose.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() } ?: newest
            fun newestTop() = compose.onAllNodes(hasText(probe, substring = true)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
            val before = newestTop()
            for (dy in listOf(-140f, 140f)) {   // whichever way moves a reversed list back towards older rows
                compose.onAllNodes(hasScrollAction())[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                ui.settle(12)
                val after = newestTop()
                if (after == null || before == null || after > before + 20f) break
            }
            shots("under")
        } catch (e: Throwable) {
            runCatching { ui.shot("composer-$lang-$size-zz-failed") }
            throw e
        }
    }
}
