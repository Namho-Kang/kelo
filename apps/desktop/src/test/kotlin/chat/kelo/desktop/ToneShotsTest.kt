package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URI

/**
 * 마이 → 에이전트 → 말투 against tools/mock_server.py in a narrow window: both choices with their example sentences, polite chosen at
 * first; the tone changed on the server (as the agent's set_tone does after "반말로 해 줘") shows as chosen when the page opens again;
 * clicking 공손 / Polite saves it back (PUT /api/profile). Screenshots tone-<lang>-narrow-<state>.png go to $KELO_SHOTS or build/parity.
 */
class ToneShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val start = androidx.compose.runtime.mutableStateOf<String?>(null)

    @After fun stop() { mock?.stop() }

    @Test fun korean() = flow("ko")

    @Test fun english() = flow("en")

    private fun call(method: String, path: String, body: String? = null): String {
        val c = URI("${mock!!.base}$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer mock-desktop-token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c.inputStream.readBytes().decodeToString()
    }

    private fun chosen(label: String): SemanticsMatcher = isSelected() and hasText(label, substring = true)

    private fun flow(lang: String) {
        val server = MockServer("--lang", lang).also { mock = it }
        val app = server.app(tmp.newFolder(), lang = lang, theme = "light")
        val words = Words.of(lang)
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(480.dp, 900.dp)) {
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        val friendly = words.get(Res.string.my_agent_tone_friendly)
        val polite = words.get(Res.string.my_agent_tone_polite)
        fun open() { start.value = MyView.AGENT; ui.settle(); ui.waitText(words.get(Res.string.my_agent_tone)) }
        fun waitChosen(label: String) = ui.waitUntil("$label chosen") { compose.onAllNodes(chosen(label)).fetchSemanticsNodes().isNotEmpty() }
        try {
            open()
            ui.waitText(words.get(Res.string.my_agent_tone_friendly_example))
            ui.waitText(words.get(Res.string.my_agent_tone_polite_example))
            waitChosen(polite)
            ui.shot("tone-$lang-narrow-polite")
            // the agent switched it in the chat (set_tone saves the same profile field): the page shows it when opened again
            call("PUT", "/api/profile", """{"tone":"friendly"}""")
            compose.onAllNodesWithContentDescription(words.get(Res.string.back))[0].performClick()
            ui.waitText(words.get(Res.string.my_section_account))
            open()
            waitChosen(friendly)
            ui.shot("tone-$lang-narrow-friendly")
            // clicking polite saves it back
            compose.onNodeWithText(polite).performClick()
            waitChosen(polite)
            ui.waitUntil("the server has polite") { call("GET", "/api/profile").contains("\"tone\": \"polite\"") }
            assertEquals("polite", app.profile.value?.tone)
        } catch (e: Throwable) {
            runCatching { ui.shot("tone-$lang-zz-failed") }
            throw e
        }
    }
}
