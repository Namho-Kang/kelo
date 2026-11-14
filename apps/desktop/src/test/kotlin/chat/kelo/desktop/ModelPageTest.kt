package chat.kelo.desktop

import chat.kelo.app.chat.ModelChooser

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.Profile
import chat.kelo.desktop.core.AppContainer
import chat.kelo.app.my.ModelPage
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 마이 → 모델 and the chat's model chooser against tools/mock_server.py --fresh --signin: a signed-in service shows its account
 * and 로그아웃 (DELETE /api/providers/{name}/login, which empties a fallback on it), and the chooser's sections fold, the current
 * one too. Screenshots in Korean, light and dark (parityShots()).
 */
class ModelPageTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")
    private val claude = "claude-subscription-directsdk-experimental"

    @After fun stop() { mock?.stop() }

    /** OpenAI with a key as the main model, the Claude subscription signed in as the fallback (all through the API). */
    private fun setUp(): AppContainer {
        val m = MockServer("--lang", "ko", "--fresh", "--signin").also { mock = it }
        val app = m.app(tmp.newFolder(), lang = "ko")
        runBlocking {
            val api = app.sessions.requireApi()
            api.put<JsonElement>("/api/providers/openai/key", buildJsonObject { put("api_key", "sk-mock-0000-1234") })
            api.put<JsonElement>("/api/providers/$claude/consent", buildJsonObject { put("accepted", true); put("version", 1) })
            val s = api.post<JsonObject>("/api/providers/$claude/login/start", buildJsonObject {})
            api.post<JsonElement>("/api/providers/$claude/login/finish", buildJsonObject {
                put("session", s["session"]!!.jsonPrimitive.content); put("code", "MOCK-CODE")
            })
            app.setProfile(api.put<Profile>("/api/profile", buildJsonObject {
                put("provider", "openai"); put("model", "gpt-5.5")
                put("fallback_provider", claude); put("fallback_model", "claude-sonnet-5-5")
            }))
        }
        return app
    }

    @Test fun theChooserFoldsEverySectionIncludingTheCurrentOne() {
        val app = setUp()
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                val p by app.profile.collectAsState()
                val providers = androidx.compose.runtime.remember { runBlocking { app.sessions.requireApi().get<List<chat.kelo.app.chat.ProviderInfo>>("/api/providers") } }
                Box(Modifier.size(900.dp, 900.dp)) {
                    ModelChooser(p ?: Profile(), providers, null, onProfile = app::setProfile, onEffort = {}, onMore = {}, onClose = {})
                }
            }
        }
        // folded at first (a model is chosen): the "모델" row with the model in use, no model rows
        ui.waitText(ko.get(Res.string.model_picker_model))
        ui.waitText("GPT-5.5")
        assertFalse(ui.has("GPT-5.5 Mini"))
        assertTrue("the thinking level under the folded list", ui.has(ko.get(Res.string.chat_effort)))
        ui.shot("parity-model-chooser-folded-light")
        compose.onAllNodesWithText(ko.get(Res.string.model_picker_model)).onLast().performClick()
        ui.waitText("GPT-5.5 Mini")
        ui.settle()   // the list grows in: its headers can be clicked once they are in place
        // the current provider's section folds with its arrow too
        compose.onAllNodesWithText("OpenAI (API key)").onLast().performClick()
        ui.waitUntil("the current section folded") { !ui.has("GPT-5.5 Mini") }
        compose.onAllNodesWithText("OpenAI (API key)").onLast().performClick()
        ui.waitText("GPT-5.5 Mini")
        ui.settle()   // the rows below move down while it grows: click them where they end up
        // another ready provider opens on a click
        val claudeLabel = ko.get(Res.string.consent_service_claude)
        compose.onAllNodesWithText(claudeLabel).onLast().performClick()
        ui.waitText("Claude Opus 5.5")
        ui.settle()
        ui.shot("parity-model-chooser-sections-light")
        app.settings.setTheme("dark")
        ui.shot("parity-model-chooser-sections-dark")
    }

    /** Where the "자세한 설정" row (below the list) sits right now: it moves with the list as the list grows or shrinks. */
    private fun moreTop(): Float =
        compose.onAllNodesWithText(ko.get(Res.string.chooser_more)).onLast().fetchSemanticsNode().boundsInRoot.top

    /** [n] frames of the test clock, one at a time (a single long jump would start an animation and skip what it does). */
    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeBy(16) }

    private fun showChooser(app: AppContainer) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                val p by app.profile.collectAsState()
                val providers = androidx.compose.runtime.remember { runBlocking { app.sessions.requireApi().get<List<chat.kelo.app.chat.ProviderInfo>>("/api/providers") } }
                Box(Modifier.size(900.dp, 900.dp)) {
                    ModelChooser(p ?: Profile(), providers, null, onProfile = app::setProfile, onEffort = {}, onMore = {}, onClose = {})
                }
            }
        }
    }

    /** A section opening grows and closing shrinks over several frames: what sits below moves part of the way, never all at once. */
    @Test fun theChooserSectionsGrowAndShrinkSmoothly() {
        val app = setUp()
        val ui = UiSteps(compose, parityShots())
        showChooser(app)
        ui.waitText(ko.get(Res.string.model_picker_model))
        ui.waitText("GPT-5.5")
        compose.onAllNodesWithText(ko.get(Res.string.model_picker_model)).onLast().performClick()
        ui.waitText("GPT-5.5 Mini")
        ui.settle(12)
        val claudeLabel = ko.get(Res.string.consent_service_claude)
        val closed = moreTop()

        compose.onAllNodesWithText(claudeLabel).onLast().performClick()
        ui.waitText("Claude Opus 5.5")    // the models are loaded and the section starts to grow
        ui.settle(12)
        val open = moreTop()
        assertTrue("the section took room ($closed -> $open)", open > closed + 40)

        // closing: a few frames in, the row below is between the two places
        compose.onAllNodesWithText(claudeLabel).onLast().performClick()
        frames(10)   // the exit curve starts slowly (emphasized accelerate, 200 ms)
        val mid = moreTop()
        assertTrue("shrinking, not jumping ($open > $mid > $closed)", mid < open - 2 && mid > closed + 2)
        val all = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        javax.imageio.ImageIO.write(all[0].captureToImage().toAwtImage(), "png", java.io.File(parityShots().apply { mkdirs() }, "parity-model-chooser-closing-light.png"))
        ui.settle(12)
        assertEquals(closed, moreTop(), 1f)

        // opening again: mid-way through it grows
        compose.onAllNodesWithText(claudeLabel).onLast().performClick()
        frames(6)
        val growing = moreTop()
        assertTrue("growing, not jumping ($closed < $growing < $open)", growing > closed + 2 && growing < open - 2)
        javax.imageio.ImageIO.write(all[0].captureToImage().toAwtImage(), "png", java.io.File(parityShots(), "parity-model-chooser-opening-light.png"))
        app.settings.setTheme("dark")
        frames(1)
        javax.imageio.ImageIO.write(all[0].captureToImage().toAwtImage(), "png", java.io.File(parityShots(), "parity-model-chooser-opening-dark.png"))
        app.settings.setTheme("light")
        ui.settle(12)
        assertEquals(open, moreTop(), 1f)

        // with reduced motion the fold is immediate: one frame and it is done
        app.settings.setReduceMotion(true)
        ui.settle(2)
        compose.onAllNodesWithText(claudeLabel).onLast().performClick()
        frames(2)
        assertEquals(closed, moreTop(), 1f)
    }
}
