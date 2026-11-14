package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
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
 * A chat message with a key in it, against tools/mock_server.py --secrets (the real server's secret_guard.py answers 409): the
 * dialog asks Save in Keychain / Send as is / Edit; Save shows the value already filled in (hidden) with what it is for; Edit keeps the
 * text; Send as is sends the same text with "secrets": "allow". Screenshots go to $KELO_SHOTS or build/parity as secret-dialog-*.png
 * (en and ko, light and dark, a wide and a 412/320 dp window, and German and Japanese at 320 for long and spaceless labels).
 */
class SecretDialogTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    private val key = "sk-ant-api03-" + "Zq7xW2mK9pL4vN8rT1yB6cD3fG5hJ0sA"

    @Test fun englishWide() = flow("en", 1024, sendAsIs = true)
    @Test fun koreanWide() = flow("ko", 1024, sendAsIs = false)
    @Test fun english412() = flow("en", 412, sendAsIs = false)
    @Test fun korean320() = flow("ko", 320, sendAsIs = false)
    @Test fun german320() = flow("de", 320, sendAsIs = false)
    @Test fun japanese320() = flow("ja", 320, sendAsIs = false)

    private fun flow(lang: String, width: Int, sendAsIs: Boolean) {
        val words = Words.of(lang)
        val m = MockServer("--lang", if (lang == "ko") "ko" else "en", "--secrets").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(width.dp, 820.dp)) {
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        val name = "secret-dialog-$lang-$width"
        try {
            ui.waitUntil("the history") { chat.state.value.loaded }
            val typed = if (lang == "ko") "🙂 Anthropic 키는 $key 이거야. 이걸로 연결해줘" else "🙂 My Anthropic key is $key — please connect it"
            chat.setInput(typed)
            chat.send()
            ui.waitText(words.get(Res.string.secret_title))
            val before = chat.state.value
            assertEquals(typed, before.input)                                                  // back in the composer
            assertTrue(before.items.none { it is ChatItem.Msg && it.mine && key in it.text })  // no sent bubble
            fun shots(step: String) {
                ui.shot("$name-$step-light")
                app.settings.setTheme("dark")
                ui.shot("$name-$step-dark")
                app.settings.setTheme("light")
                ui.settle()
            }
            shots("ask")
            fun click(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            click(words.get(Res.string.secret_save))
            ui.waitText(words.get(Res.string.secret_for))
            ui.waitText(words.get(Res.string.secret_key_for, "Anthropic"))
            shots("save")
            click(words.get(Res.string.back))
            ui.waitText(words.get(Res.string.secret_send_as_is))
            if (sendAsIs) {
                click(words.get(Res.string.secret_send_as_is))
                ui.waitUntil("sent as is") { chat.state.value.items.any { it is ChatItem.Msg && it.mine && it.text == typed } }
                assertEquals("", chat.state.value.input)
            } else {
                click(words.get(Res.string.secret_edit))
                ui.waitUntil("closed") { chat.state.value.secretAsk == null }
                assertEquals(typed, chat.state.value.input)
            }
        } catch (e: Throwable) {
            runCatching { ui.shot("$name-zz-failed") }
            throw e
        }
    }
}
