package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
 * Quick-reply questions with typed answer fields, in the real chat against tools/mock_server.py --form: a date, a number, a choice
 * (with the app's own "Other" box) and days with a time, each with its own picker; what is sent is the answer in words.
 * Screenshots (ko and en, light and dark, wide and narrow) go to $KELO_SHOTS or build/parity as form-fields-*.png.
 */
class AnswerFieldsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun koreanFieldsShowTheirPickersAndSendWords() = flow("ko", wide = true)

    @Test fun englishFieldsShowTheirPickersAndSendWords() = flow("en", wide = true)

    @Test fun koreanFieldsFitANarrowWindow() = flow("ko", wide = false)

    @Test fun englishFieldsFitANarrowWindow() = flow("en", wide = false)

    /**
     * Scrolls the chat until the first node matching [target] is below the floating header, one ScrollBy at a time with the clock
     * moved on after each (performScrollTo would wait for its own scroll animation with the clock held, which never ends).
     */
    private fun bringIntoView(ui: UiSteps, target: SemanticsMatcher, header: Float = 96f) {
        var sign = 1f
        var lastMiss = Float.MAX_VALUE
        var blind = 0
        repeat(16) {
            val box = compose.onAllNodes(target).onFirst().fetchSemanticsNode().boundsInRoot
            val lists = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            val i = lists.indices.maxByOrNull { lists[it].boundsInRoot.height } ?: return
            val area = lists[i].boundsInRoot
            val top = area.top + header
            val bottom = minOf(area.bottom, 760f)
            val dy = when {
                box.width <= 0f -> {   // kept by the list but not laid out (out of view, above: the newest rows are at the bottom)
                    if (++blind % 3 == 0) sign = -sign
                    -area.height / 2
                }
                box.top < top -> box.top - top
                box.bottom > bottom && box.height < bottom - top -> box.bottom - bottom
                else -> return
            }
            if (box.width > 0f) {
                if (kotlin.math.abs(dy) > lastMiss) sign = -sign   // went the wrong way: turn round
                lastMiss = kotlin.math.abs(dy)
            }
            compose.onAllNodes(hasScrollAction())[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, sign * dy) }
            ui.settle()
        }
    }

    private fun flow(lang: String, wide: Boolean) {
        val words = Words.of(lang)
        val m = MockServer("--lang", lang, "--form").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(if (wide) 1024.dp else 480.dp, 760.dp)) {   // within the test window (768 high)
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        try {
            ui.waitText(words.get(Res.string.reply_other))
            ui.waitText(words.get(Res.string.reply_send))
            fun shots(name: String) {
                ui.shot("form-fields-$lang-$name-light")
                app.settings.setTheme("dark")
                ui.shot("form-fields-$lang-$name-dark")
                app.settings.setTheme("light")
                ui.settle()
            }
            val size = if (wide) "wide" else "narrow"
            // the form in view (a narrow window shows the bottom of the chat first)
            bringIntoView(ui, hasText(words.get(Res.string.reply_other)) and hasClickAction())
            shots(size)
            // the app's own "Other" opens a box under the choice (its hint is the question); a semantics click: the chip may sit
            // under the floating header at the top of the chat
            val ko = lang == "ko"
            compose.onAllNodes(hasText(words.get(Res.string.reply_other)) and hasClickAction()).onFirst()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            ui.settle()
            val otherBox = compose.onAllNodes(hasSetTextAction() and hasText(if (ko) "어떤 파일로 받을까요?" else "Which file type?"))
            ui.waitUntil("the Other box") { otherBox.fetchSemanticsNodes().isNotEmpty() }
            otherBox.onFirst().performTextInput("Markdown")
            compose.onAllNodes(hasSetTextAction() and hasText(if (ko) "개" else "tables")).onFirst().performTextInput("3")
            ui.settle()
            shots("$size-other")
            compose.onAllNodes(hasText(words.get(Res.string.reply_send)) and hasClickAction()).onFirst()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            ui.waitUntil("the answer sent") { chat.state.value.items.any { it is ChatItem.Msg && it.mine && "Markdown" in it.text } }
            val sent = chat.state.value.items.filterIsInstance<ChatItem.Msg>().last { it.mine }.text
            println("sent: $sent")
            assertTrue(sent, "Markdown" in sent && (": 3 " in sent || ": 3개" in sent) && ("09:00" in sent || "9:00" in sent))
        } catch (e: Throwable) {
            runCatching { ui.shot("form-fields-$lang-${if (wide) "wide" else "narrow"}-zz-failed") }
            throw e
        }
    }
}
