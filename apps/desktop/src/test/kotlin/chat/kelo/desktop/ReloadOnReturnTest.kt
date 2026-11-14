package chat.kelo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import chat.kelo.app.platform.ExternalBrowser
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.WindowReturns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A sign-in page opens in the system browser and the window gets the focus back: the page that opened it reads again. The browser
 * is a fake (nothing opens), the focus event is the window listener's own call.
 */
class ReloadOnReturnTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun theWindowComingBackAfterTheBrowserReloadsTheOpenPage() {
        val server = MockServer("--lang", "en").also { mock = it }
        val app = server.app(tmp.newFolder(), lang = "en")
        val opened = mutableListOf<String>()
        val browser = ExternalBrowser { url -> opened += url; true }
        var loads by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = true
        compose.setContent { KeloFrame(app) { LocalReloadOnReturn.current(Unit) { loads++ } } }
        compose.waitForIdle()
        assertEquals("loaded once when the page opened", 1, loads)
        browser.open("https://example.com/sign-in")
        assertEquals(listOf("https://example.com/sign-in"), opened)
        WindowReturns.notifyReturn()
        compose.waitUntil(5_000) { loads == 2 }
        assertEquals("read again when the window came back", 2, loads)
    }
}
