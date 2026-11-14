package chat.kelo.desktop

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
import chat.kelo.app.my.VaultPage
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Keychain → "입력 대기 / Waiting for input", against tools/mock_server.py --secret-entries (a key, a sign-in and a card the agent
 * asked for): the section lists all three newest first; [Fill in] opens the same secure entry as in the chat right there; [Delete]
 * cancels one and it leaves the list. Screenshots go to $KELO_SHOTS or build/parity as vault-waiting-*.png (en and ko, light and dark,
 * a wide and a 320 dp window, and German at 320 for long labels).
 */
class VaultWaitingTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun englishWide() = flow("en", 900)
    @Test fun koreanWide() = flow("ko", 900)
    @Test fun korean320() = flow("ko", 320)
    @Test fun german320() = flow("de", 320)

    private fun flow(lang: String, width: Int) {
        val words = Words.of(lang)
        val m = MockServer("--lang", if (lang == "ko") "ko" else "en", "--secret-entries").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) { Box(Modifier.size(width.dp, 1100.dp)) { VaultPage(onBack = {}, onGoModel = {}) } }
        }
        val name = "vault-waiting-$lang-$width"
        fun buttons(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().size
        fun click(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        fun shots(step: String) {
            ui.settle(20)
            ui.shot("$name-$step-light")
            app.settings.setTheme("dark")
            ui.settle(20)
            ui.shot("$name-$step-dark")
            app.settings.setTheme("light")
            ui.settle(4)
        }
        try {
            ui.waitText(words.get(Res.string.secret_waiting_title))
            ui.waitText("OpenWeather")
            ui.waitText(words.get(Res.string.secret_kind_card) + " · tickets.interpark.com")
            ui.waitUntil("three entries waiting") { buttons(words.get(Res.string.secret_fill)) == 3 }
            shots("list")
            click(words.get(Res.string.secret_fill))                      // the newest: the OpenWeather key
            ui.waitText(words.get(Res.string.secret_title_key, "OpenWeather"))
            ui.waitText(words.get(Res.string.secret_never_chat))
            ui.waitUntil("the other two still closed") { buttons(words.get(Res.string.secret_fill)) == 2 }
            shots("open")
            click(words.get(Res.string.delete))                           // the first closed one left
            ui.waitUntil("one entry left closed") { buttons(words.get(Res.string.secret_fill)) == 1 }
        } catch (e: Throwable) {
            runCatching { ui.shot("$name-zz-failed") }
            throw e
        }
    }
}
