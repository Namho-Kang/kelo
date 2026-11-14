package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URI

/**
 * 마이 → 서버 업데이트 against tools/mock_server.py with KELO_MOCK_SERVER_UPDATE (tools/mock_parts/server_update.py): the card at
 * the top of My home and the page, with a newer release, an update running, and one the server undid by itself; Korean and
 * English, light and dark, plus a narrow window in German and Thai. Saved as server-update-*.png in build/parity ($KELO_SHOTS).
 * Also checks that an older server (404) and another device (403) show no row and no card.
 */
class ServerUpdateShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val mocks = mutableListOf<MockServer>()
    private val start = androidx.compose.runtime.mutableStateOf<String?>(null)

    @After fun stop() { mocks.forEach { it.stop() } }

    @Test fun ko() = shots("ko", listOf("light", "dark"), 1000)

    @Test fun en() = shots("en", listOf("light", "dark"), 1000)

    @Test fun deNarrow() = shots("de", listOf("light"), 360, "narrow-")

    @Test fun thNarrow() = shots("th", listOf("light"), 360, "narrow-")

    @Test fun hiddenForAnOlderServer() = hidden("")

    @Test fun hiddenForAnotherDevice() = hidden("forbidden")

    @Test fun hiddenWithoutTheUpdater() = hidden("unavailable")

    /** An older server (404), a device that is not the owner's (403), a server without the updater: no row, no card. */
    private fun hidden(scenario: String) {
        val server = MockServer("--lang", "en", env = mapOf("KELO_MOCK_SERVER_UPDATE" to scenario)).also { mocks += it }
        val app = server.app(tmp.newFolder(), lang = "en", theme = "light")
        val s = Words.of("en")
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(1000.dp, 1500.dp)) {
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        ui.waitText(s.get(Res.string.my_server_version, "0.9.0"), timeout = 15_000)   // GET /api/about has answered
        ui.settle(30)
        check(!ui.has(s.get(Res.string.server_update_title))) { "no server update row ($scenario)" }
        check(!ui.has(s.get(Res.string.server_update_card_open))) { "no server update card ($scenario)" }
    }

    private fun scenario(server: MockServer, name: String) {
        val c = URI("${server.base}/mock/server-update").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer mock-desktop-token")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"scenario":"$name"}""".toByteArray()) }
        check(c.responseCode == 200) { "the mock did not switch to $name" }
    }

    private fun shots(lang: String, themes: List<String>, width: Int, tag: String = "") {
        val server = MockServer("--lang", if (lang == "ko") "ko" else "en", env = mapOf("KELO_MOCK_SERVER_UPDATE" to "available")).also { mocks += it }
        val app = server.app(tmp.newFolder(), lang = lang, theme = "light")
        val s = Words.of(lang)
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(width.dp, 760.dp)) {   // the captured window is 768 high: what is scrolled to is in the picture
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        fun back() {
            compose.onAllNodesWithContentDescription(s.get(Res.string.back))[0].performClick()
            ui.waitText(s.get(Res.string.my_section_account))
        }
        try {
            for (theme in themes) {
                app.settings.setTheme(theme)
                scenario(server, "available")
                ui.waitText(s.get(Res.string.my_section_account))
                start.value = MyView.PROFILE   // leave and come back: My home asks again
                ui.settle()
                back()
                // a newer release: the card on My home, then the page with what's new and the button
                ui.waitText(s.get(Res.string.server_update_card_available, "0.10.0"), timeout = 15_000)
                ui.waitText(s.get(Res.string.server_update_title))
                ui.shot("server-update-card-$tag$lang-$theme")
                start.value = MyView.SERVER_UPDATE
                ui.settle()
                ui.waitText(s.get(Res.string.server_update_whats_new), timeout = 15_000)
                ui.waitText(s.get(Res.string.server_update_auto_title))
                ui.shot("server-update-page-$tag$lang-$theme")
                back()

                // an update running: the card's progress on My home, and the page's step, bar and note
                scenario(server, "running")
                start.value = MyView.PROFILE   // leave and come back: My home asks again
                ui.settle()
                back()
                ui.waitText(s.get(Res.string.server_update_step_download), timeout = 15_000)
                ui.shot("server-update-card-running-$tag$lang-$theme")
                start.value = MyView.SERVER_UPDATE
                ui.settle()
                ui.waitText(s.get(Res.string.server_update_running_note), timeout = 15_000)
                check(!ui.has(s.get(Res.string.server_update_check_now))) { "nothing to press while an update runs" }
                ui.shot("server-update-progress-$tag$lang-$theme")

                // the new version failed its check: the server went back by itself and put the data back
                scenario(server, "rolled_back")
                ui.waitText(s.get(Res.string.server_update_rolled_back, "0.9.0"), timeout = 20_000)
                ui.waitText(s.get(Res.string.server_update_data_restored))
                compose.onAllNodesWithText(s.get(Res.string.server_update_data_restored)).onFirst().performScrollTo()
                ui.shot("server-update-result-$tag$lang-$theme")
                back()
                ui.waitText(s.get(Res.string.server_update_card_rolled_back), timeout = 15_000)
            }
        } catch (e: Throwable) {
            runCatching { ui.shot("server-update-zz-failed-$tag$lang") }
            throw e
        }
    }
}
