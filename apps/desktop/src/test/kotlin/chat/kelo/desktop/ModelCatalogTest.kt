package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.ModelFacts
import chat.kelo.app.chat.ModelList
import chat.kelo.app.chat.ProviderInfo
import chat.kelo.app.core.CacheSlot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.Profile
import chat.kelo.app.core.ScreenCache
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import chat.kelo.app.my.CliStatus
import chat.kelo.app.my.ProviderDetail
import chat.kelo.app.chat.ModelCatalog
import chat.kelo.desktop.core.AppContainer
import chat.kelo.app.Caches
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.desktop.core.StoreKey
import chat.kelo.app.my.ModelPage
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator

/**
 * The kept provider and model lists on this computer (the shared ModelCatalog with the shared Caches; its rules are tested in
 * shared): what the desktop's own data folder keeps, and the files the desktop wrote before the shared Caches existed.
 */
class ModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = Executor { it.run() }

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** A new launch: a fresh cache instance on the same folder, so everything is read back from the disk. */
    private fun sessions(server: String, dir: File = tmp.root): Sessions =
        Sessions(SecureStore(MapStore(), AesGcmCipher { key }), OkHttpClient(), ScreenCache(dir, AesGcmCipher { key }, now), requireE2e = false)
            .apply { signIn(Session(server, "tok-0123456789abcdef")) }

    private val agy = ModelList(
        "antigravity-oauth", listOf("gemini-3.8-flash-medium", "claude-sonnet-4-6"),
        labels = mapOf("gemini-3.8-flash-medium" to "Gemini 3.8 Flash (Medium)", "claude-sonnet-4-6" to "Claude Sonnet 4.6 (Thinking)"),
        info = mapOf("claude-sonnet-4-6" to ModelFacts(images = true)),
    )

    /** The app's own storage (AppContainer: the cache folder in its data folder, sealed with its key) reads them back on a new run. */
    @Test fun theAppKeepsThemInItsDataFolder() {
        val dir = tmp.newFolder()
        fun app() = AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir), requireE2e = false)
        val first = app()
        first.sessions.signIn(Session("https://a.example.com", "tok-0123456789abcdef"))
        ModelCatalog.keepList(first.sessions, first.sessions.requireApi(), agy)
        first.sessions.keep(Caches.PROVIDER_DETAILS, listOf(ProviderDetail("antigravity-oauth", "Antigravity", configured = true)))
        // the disk write runs on the cache's own thread: a later run reads it once it is there
        val end = System.currentTimeMillis() + 10_000
        var next = app()
        while ((ModelCatalog.keptList(next.sessions, agy.provider) == null || ModelCatalog.keptProviders(next.sessions).isEmpty()) &&
            System.currentTimeMillis() < end) {
            Thread.sleep(50)
            next = app()
        }
        assertEquals(agy, ModelCatalog.keptList(next.sessions, agy.provider))
        assertEquals("Antigravity", ModelCatalog.keptProviders(next.sessions).single().displayName)
    }

    /**
     * The slots the desktop kept under its own cache object before the shared Caches took over (same names and serializers): a file
     * written that way is read by the shared object.
     */
    @Test fun filesWrittenUnderTheOldSlotsStayReadable() {
        val old = CacheSlot("providers", ListSerializer(ProviderInfo.serializer()))
        val oldLists = CacheSlot("model_lists", MapSerializer(String.serializer(), ModelList.serializer()))
        val dir = tmp.newFolder()
        val first = sessions("https://a.example.com", dir)
        first.keep(old, listOf(ProviderInfo(name = "openai")))
        first.keep(oldLists, mapOf(agy.provider to agy))
        val end = System.currentTimeMillis() + 10_000
        var next = sessions("https://a.example.com", dir)
        while (ModelCatalog.keptList(next, agy.provider) == null && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            next = sessions("https://a.example.com", dir)
        }
        assertEquals(agy, ModelCatalog.keptList(next, agy.provider))
        assertEquals(listOf("openai"), ModelCatalog.keptProviderInfos(next).map { it.name })
    }
}

/**
 * The model settings (마이 → 모델) against a server that never answers: the provider list and the model's name kept from the last
 * time show at once, no text field holds the raw id, and the refresh link says "loading" only once a second has passed.
 */
class ModelSettingsKeptTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val en = Words.of("en")
    private val silent = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val held = CopyOnWriteArrayList<Socket>()

    init {
        // accepts every request and never answers (closed in stop())
        Thread {
            while (!silent.isClosed) runCatching { held += silent.accept() }
        }.apply { isDaemon = true }.start()
    }

    @After fun stop() {
        runCatching { silent.close() }
        held.forEach { runCatching { it.close() } }
    }

    @Test fun theKeptProvidersAndTheModelsNameShowBeforeTheServerAnswers() {
        val dir = tmp.newFolder()
        val app = AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir), requireE2e = false)
        app.settings.setLanguage("en")
        app.applyLanguage()
        app.settings.setTheme("light")
        app.sessions.signIn(Session("http://127.0.0.1:${silent.localPort}", "tok-0123456789abcdef"))
        // what an earlier run kept for this server
        app.sessions.keep(Caches.PROVIDER_DETAILS, listOf(
            ProviderDetail("claude-cli", "Claude Code", configured = true),
            ProviderDetail("openrouter", "OpenRouter", configured = false, takesKey = true),
        ))
        ModelCatalog.keepList(app.sessions, app.sessions.requireApi(),
            ModelList("claude-cli", listOf("sonnet", "opus"), labels = mapOf("sonnet" to "Sonnet (latest)", "opus" to "Opus (latest)")))
        app.setProfile(Profile(provider = "claude-cli", model = "sonnet"))

        val ui = UiSteps(compose, File("build/model-kept"))
        compose.mainClock.autoAdvance = false
        compose.setContent { KeloFrame(app) { Box(Modifier.size(900.dp, 1400.dp)) { ModelPage(app.profile.value ?: chat.kelo.app.core.Profile(), app::setProfile, onBack = {}) } } }
        compose.mainClock.advanceTimeBy(200)

        ui.waitText(en.get(Res.string.model_picker_ready, "Claude Code"))   // the provider field, from the kept list
        ui.waitText("Sonnet (latest)")                                    // the model by its name
        assertTrue("no text field while the list loads", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        assertFalse("no raw id", ui.has("sonnet"))
        assertFalse("no loading hint in the first second", compose.onAllNodesWithText(en.get(Res.string.loading)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(ui.has(en.get(Res.string.refresh)))

        compose.mainClock.advanceTimeBy(ModelCatalog.SLOW_MS + 200)
        ui.waitText(en.get(Res.string.loading))                             // still nothing from the server: the link says so
        assertTrue("the name stays", ui.has("Sonnet (latest)"))

        // the kept provider list is what the provider field offers
        compose.onAllNodesWithText(en.get(Res.string.model_picker_ready, "Claude Code"), substring = true).onFirst().performClick()
        ui.waitText(en.get(Res.string.model_picker_needs_setup, "OpenRouter"))
    }
}
