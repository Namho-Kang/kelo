package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URI

/**
 * 마이 → Kelo 서버 자원 against tools/mock_server.py with KELO_MOCK_RESOURCES: the exact answer that left the Android page blank
 * (minimum/recommended memory as floats, 536870912.0) must show its numbers, in Korean, light and dark; a server that cannot answer
 * shows the error with "try again" (never a blank page), and "try again" then shows the numbers; a refresh that fails later keeps
 * them. Saved as resources-*.png in build/parity ($KELO_SHOTS).
 */
class ResourcesShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val mocks = mutableListOf<MockServer>()
    private val start = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val ko = Words.of("ko")

    @After fun stop() { mocks.forEach { it.stop() } }

    private fun mode(server: MockServer, name: String) {
        val c = URI("${server.base}/mock/resources").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer mock-desktop-token")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"mode":"$name"}""".toByteArray()) }
        check(c.responseCode == 200) { "the mock did not switch to $name" }
    }

    @Test fun thePhonesAnswerShowsItsNumbersAndAFailureNeverLeavesThePageBlank() {
        val server = MockServer("--lang", "ko", env = mapOf("KELO_MOCK_RESOURCES" to "fail")).also { mocks += it }
        val app = server.app(tmp.newFolder(), lang = "ko", theme = "light")
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(1000.dp, 760.dp)) {
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        val failed = ko.get(Res.string.resources_load_failed)
        val retry = ko.get(Res.string.retry)
        val host = "assistant limits --choose"
        try {
            ui.waitText(ko.get(Res.string.my_section_account))
            // the first read fails: the error and "try again", in light and dark
            start.value = MyView.RESOURCES
            ui.settle()
            ui.waitText(failed, timeout = 15_000)
            check(ui.has(retry)) { "try again is offered" }
            ui.shot("resources-error-ko-light")
            app.settings.setTheme("dark")
            ui.settle()
            ui.shot("resources-error-ko-dark")
            app.settings.setTheme("light")

            // the server answers again (the phone's exact answer, floats and all): "try again" shows the numbers
            mode(server, "phone")
            compose.onNodeWithText(retry).performClick()
            ui.waitText(host, timeout = 15_000)
            check(!ui.has(failed)) { "the error is gone" }
            check(ui.has(ko.get(Res.string.resources_unlimited_long))) { "the profile is shown" }
            check(ui.has("512MB")) { "the minimum memory (536870912.0) reads as 512MB" }
            ui.shot("resources-phone-ko-light")
            app.settings.setTheme("dark")
            ui.settle()
            ui.waitText(host)
            ui.shot("resources-phone-ko-dark")

            // a later refresh fails: the last numbers stay, no error
            mode(server, "fail")
            repeat(4) { compose.mainClock.advanceTimeBy(3_000); Thread.sleep(300) }
            ui.settle(20)
            check(ui.has(host) && ui.has("512MB")) { "the numbers stay after a failed refresh" }
            check(!ui.has(failed)) { "no error over numbers already shown" }

            // a fixed server (every size a whole number) reads the same
            mode(server, "phone-ints")
            repeat(4) { compose.mainClock.advanceTimeBy(3_000); Thread.sleep(300) }
            ui.settle(20)
            check(ui.has(host) && ui.has("512MB")) { "whole numbers read the same" }
            app.settings.setTheme("light")
        } catch (e: Throwable) {
            runCatching { ui.shot("resources-zz-failed") }
            throw e
        }
    }
}
