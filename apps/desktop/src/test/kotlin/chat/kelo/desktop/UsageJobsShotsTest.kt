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
import chat.kelo.app.my.UsageLogic
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 마이 → 사용량's model usage card (one number, bar by model and legend) and 마이 → 모델's
 * background work model row, against tools/mock_server.py.
 */
class UsageJobsShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val mocks = mutableListOf<MockServer>()
    private val start = androidx.compose.runtime.mutableStateOf<String?>(null)

    @After fun stop() { mocks.forEach { it.stop() } }

    @Test fun ko() = shots("ko", listOf("light", "dark"), 1000)

    @Test fun en() = shots("en", listOf("light", "dark"), 1000)

    @Test fun deNarrow() = shots("de", listOf("light"), 360, "narrow-")

    @Test fun arNarrow() = shots("ar", listOf("light"), 360, "narrow-")

    private fun shots(lang: String, themes: List<String>, width: Int, tag: String = "") {
        val server = MockServer("--lang", if (lang == "ko") "ko" else "en").also { mocks += it }   // the mock speaks Korean or English
        val app = server.app(tmp.newFolder(), lang = lang, theme = "light")
        val s = Words.of(lang)
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(width.dp, 1500.dp)) {
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        val bigTokens = UsageLogic.compactTokens(2_990_000, lang)
        val bigNumber = s.get(Res.string.usage_jobs_tokens, bigTokens)
        val caption = s.get(Res.string.usage_models_caption, bigTokens)
        val legendModel = chat.kelo.app.chat.ModelNames.display("gemini-3.8-flash", null)
        try {
            for (theme in themes) {
                app.settings.setTheme(theme)
                ui.waitText(s.get(Res.string.my_section_account))
                ui.waitText(s.get(Res.string.usage_used, 61), timeout = 15_000)   // the usage row's value: GET /api/usage has answered
                start.value = MyView.USAGE
                ui.settle()
                ui.waitText(bigNumber, timeout = 15_000)
                ui.waitText(caption)
                ui.waitText(legendModel)
                compose.onAllNodesWithText(bigNumber).onFirst().performScrollTo()
                ui.shot("usage-by-model-$tag$lang-$theme")
                compose.onAllNodesWithContentDescription(s.get(Res.string.back))[0].performClick()
                ui.waitText(s.get(Res.string.my_section_account))
                start.value = MyView.MODEL
                ui.settle()
                ui.waitText(s.get(Res.string.model_background_title), timeout = 15_000)
                ui.waitText(s.get(Res.string.model_background_same))
                compose.onAllNodesWithText(s.get(Res.string.model_background_title)).onFirst().performScrollTo()
                ui.shot("model-background-$tag$lang-$theme")
                compose.onAllNodesWithContentDescription(s.get(Res.string.back))[0].performClick()
                ui.waitText(s.get(Res.string.my_section_account))
            }
        } catch (e: Throwable) {
            runCatching { ui.shot("usage-by-model-zz-failed-$tag$lang") }
            throw e
        }
    }
}
