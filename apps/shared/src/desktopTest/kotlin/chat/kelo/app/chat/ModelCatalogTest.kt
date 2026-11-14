package chat.kelo.app.chat

import chat.kelo.app.Caches
import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.ScreenCache
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import chat.kelo.app.my.CliStatus
import chat.kelo.app.my.ProviderDetail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator

/** The kept provider and model lists (ModelCatalog) and the one way of writing a model's name the server also uses. */
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
    private fun sessions(server: String, kv: MapStore = MapStore()): Sessions =
        Sessions(SecureStore(kv, AesGcmCipher { key }), OkHttpClient(), ScreenCache(tmp.root, AesGcmCipher { key }, now), requireE2e = false)
            .apply { signIn(Session(server, "tok-0123456789abcdef")) }

    private val agy = ModelList(
        "antigravity-oauth", listOf("gemini-3.8-flash-medium", "claude-sonnet-4-6"),
        labels = mapOf("gemini-3.8-flash-medium" to "Gemini 3.8 Flash (Medium)", "claude-sonnet-4-6" to "Claude Sonnet 4.6 (Thinking)"),
        info = mapOf("claude-sonnet-4-6" to ModelFacts(images = true)),
    )

    // ---- the shared label rule

    /** server/tests/fixtures/model_labels.json: the same cases the server's server/tests/test_model_labels.py checks. */
    private fun fixture(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/model_labels.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        fail("server/tests/fixtures/model_labels.json not found above ${File("").absolutePath}")
        throw IllegalStateException()
    }

    @Test fun namesAreWrittenExactlyAsTheServerWritesThem() {
        val cases = Json.parseToJsonElement(fixture().readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val (id, want) = c.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("for $id", want, ModelNames.humanize(id))
        }
    }

    @Test fun aDerivedNameAndTheServersLabelNeverDifferInPunctuation() {
        // the CLIs' own labels (agy models, the claude CLI's names) for ids whose words say it all
        mapOf(
            "gemini-3.8-flash-medium" to "Gemini 3.8 Flash (Medium)", "gemini-3.8-flash-high" to "Gemini 3.8 Flash (High)",
            "gemini-3.8-flash-low" to "Gemini 3.8 Flash (Low)", "claude-opus-4-6-thinking" to "Claude Opus 4.6 (Thinking)",
            "gpt-oss-120b-medium" to "GPT-OSS 120B (Medium)", "sonnet" to "Sonnet (latest)", "claude-opus-5-5" to "Claude Opus 5.5",
        ).forEach { (id, label) ->
            assertEquals(label, ModelNames.display(id, null))
            assertEquals(ModelNames.short(label), ModelNames.short(ModelNames.display(id, null)))   // the chip's form too
        }
    }

    // ---- what a screen shows

    @Test fun neverBlank() {
        val failed = ModelList(agy.provider, emptyList(), error = "could not")
        assertEquals(agy, ModelCatalog.shown(null, agy))                      // not answered yet: the kept list
        assertEquals(agy.copy(stale = true), ModelCatalog.shown(failed, agy))  // failed: the kept list, marked stale
        assertEquals(failed, ModelCatalog.shown(failed, null))                // nothing kept: the reason
        val fresh = agy.copy(models = agy.models + "gemini-3.8-flash-high")
        assertEquals(fresh, ModelCatalog.shown(fresh, agy))                   // a good answer replaces it in place
        assertNull(ModelCatalog.shown(null, null))
        // nothing known at all: the chosen model by name (a select row), never the raw id in a text field
        assertEquals(listOf("sonnet"), ModelCatalog.placeholder("claude-cli", "sonnet")!!.models)
        assertEquals("Sonnet (latest)", ModelCatalog.name("sonnet", null, null))
        assertNull(ModelCatalog.placeholder("claude-cli", ""))
    }

    @Test fun theLabelComesFromTheFreshListThenTheKeptOneThenTheRule() {
        assertEquals("Claude Sonnet 4.6 (Thinking)", ModelCatalog.name("claude-sonnet-4-6", null, agy))
        assertEquals("Claude Sonnet 4.6 (Thinking)", ModelCatalog.name("claude-sonnet-4-6", agy.copy(labels = emptyMap()), agy))
        assertEquals("Claude Sonnet 4.6", ModelCatalog.name("claude-sonnet-4-6", null, null))
    }

    @Test fun onlyGoodListsAreKeptNewestFirstAndCapped() {
        var kept = emptyMap<String, ModelList>()
        kept = ModelCatalog.merge(kept, agy)
        kept = ModelCatalog.merge(kept, ModelList(agy.provider, emptyList(), error = "down"))   // a failure changes nothing
        assertEquals(agy, kept[agy.provider])
        kept = ModelCatalog.merge(kept, ModelList("openai", emptyList()))                        // an empty list is not kept
        assertFalse("openai" in kept)
        repeat(ModelCatalog.MAX_PROVIDERS + 4) { i -> kept = ModelCatalog.merge(kept, ModelList("p$i", listOf("m"))) }
        assertEquals(ModelCatalog.MAX_PROVIDERS, kept.size)
        assertEquals("p${ModelCatalog.MAX_PROVIDERS + 3}", kept.keys.first())
    }

    // ---- kept on the phone

    @Test fun listsAndProvidersSurviveARestartPerServer() {
        val a = "https://a.example.com"
        val first = sessions(a)
        ModelCatalog.keepList(first, first.requireApi(), agy.copy(stale = true))
        ModelCatalog.keepList(first, first.requireApi(), ModelList("openai", emptyList(), error = "no key"))
        val rows = listOf(ProviderDetail("antigravity-oauth", "Antigravity", configured = true, cli = CliStatus(installed = true, loggedIn = true)))
        first.keep(Caches.PROVIDER_DETAILS, rows)

        val again = sessions(a)   // the next launch
        assertEquals(agy, ModelCatalog.keptList(again, agy.provider))   // with its labels and facts, without the stale mark
        assertNull(ModelCatalog.keptList(again, "openai"))
        assertEquals(rows, ModelCatalog.keptProviders(again))
        assertEquals(listOf("antigravity-oauth"), ModelCatalog.keptProviderInfos(again).map { it.name })

        val other = sessions("https://b.example.com")   // another server never sees them
        assertNull(ModelCatalog.keptList(other, agy.provider))
        assertTrue(ModelCatalog.keptProviders(other).isEmpty())
    }

    @Test fun fetchKeepsAGoodAnswerAndAFailureKeepsTheLastGoodOne() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val s = sessions(base)
            server.enqueue(MockResponse().setBody(
                """{"provider":"antigravity-oauth","models":["gemini-3.8-flash-medium"],"labels":{"gemini-3.8-flash-medium":"Gemini 3.8 Flash (Medium)"},"info":{},"error":null,"stale":false,"fetched_at":1}""",
            ))
            val got = ModelCatalog.fetch(s, "antigravity-oauth")
            assertEquals("Gemini 3.8 Flash (Medium)", got.labels["gemini-3.8-flash-medium"])
            assertTrue(server.takeRequest().path!!.startsWith("/api/models?provider=antigravity-oauth"))
            server.enqueue(MockResponse().setBody("""{"provider":"antigravity-oauth","models":[],"labels":{},"info":{},"error":"agy models timed out"}"""))
            val failed = ModelCatalog.fetch(s, "antigravity-oauth")
            assertNotNull(failed.error)
            val kept = ModelCatalog.keptList(sessions(base), "antigravity-oauth")
            assertEquals(listOf("gemini-3.8-flash-medium"), kept!!.models)
            assertEquals("Gemini 3.8 Flash (Medium)", ModelCatalog.shown(failed, kept)!!.labels["gemini-3.8-flash-medium"])

            server.enqueue(MockResponse().setBody("""[{"name":"antigravity-oauth","display_name":"Antigravity","configured":true,"cli":{"installed":true,"logged_in":true,"checked_at":1700000000.5}}]"""))
            val rows = ModelCatalog.fetchProviders(s)
            assertEquals("Antigravity", rows.single().displayName)
            val next = sessions(base)
            assertEquals(rows, ModelCatalog.keptProviders(next))
            assertEquals("Antigravity", ModelCatalog.keptProviderInfos(next).single().displayName)
        } finally {
            server.shutdown()
        }
    }
}
