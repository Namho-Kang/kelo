package chat.kelo.app.my

import chat.kelo.app.chat.AdminKeyInfo
import chat.kelo.app.chat.ProviderInfo
import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import chat.kelo.app.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class UsageAdminKeyTest {
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val answers = Collections.synchronizedMap(mutableMapOf<String, MockResponse>())

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Before fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.path}"
                return answers[key] ?: MockResponse().setResponseCode(404).setBody("""{"detail":"none"}""")
            }
        }
        server.start()
    }

    @After fun stop() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private fun sessions(): Sessions {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        return Sessions(store, requireE2e = false)
    }

    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    @Test fun providerInfoDecodesAdminKey() {
        val json = """{"name":"openai","admin_key":{"supported":true,"set":true,"hint":"sk-adm"}}"""
        val p = KeloApi.json.decodeFromString<ProviderInfo>(json)
        assertEquals("openai", p.name)
        assertTrue(p.adminKey.supported)
        assertTrue(p.adminKey.set)
        assertEquals("sk-adm", p.adminKey.hint)

        val missing = """{"name":"anthropic"}"""
        val p2 = KeloApi.json.decodeFromString<ProviderInfo>(missing)
        assertEquals("anthropic", p2.name)
        assertFalse(p2.adminKey.supported)
        assertFalse(p2.adminKey.set)
        assertNull(p2.adminKey.hint)
    }

    @Test fun providerAccountDecodesAdminAndAdminError() {
        val json = """{"provider":"openai","admin":true,"admin_error":"Key expired"}"""
        val a = KeloApi.json.decodeFromString<ProviderAccount>(json)
        assertEquals("openai", a.provider)
        assertTrue(a.admin)
        assertEquals("Key expired", a.adminError)

        val missing = """{"provider":"anthropic"}"""
        val a2 = KeloApi.json.decodeFromString<ProviderAccount>(missing)
        assertEquals("anthropic", a2.provider)
        assertFalse(a2.admin)
        assertNull(a2.adminError)
    }

    @Test fun saveAdminKeySendsPutAndRefreshes() {
        answers["GET /api/providers"] = MockResponse().setBody("""[{"name":"openai","admin_key":{"supported":true,"set":false}}]""")
        answers["PUT /api/providers/openai/admin-key"] = MockResponse().setBody("""{"ok":true,"checked":true}""")

        val vm = UsageViewModel(sessions())
        var done = false
        vm.saveAdminKey("openai", "sk-admin-secret") { done = true }

        settle { done }
        assertTrue(done)
        assertEquals(Res.string.admin_key_saved, vm.adminNote.value)
        assertFalse(vm.adminBusy.value)

        val putReq = requests.firstOrNull { it.method == "PUT" && it.path == "/api/providers/openai/admin-key" }
        assertTrue(putReq != null)
        assertEquals("""{"api_key":"sk-admin-secret"}""", putReq!!.body.readUtf8())
    }

    @Test fun saveAdminKeyHandlesKeyRefused422() {
        answers["GET /api/providers"] = MockResponse().setBody("""[]""")
        answers["PUT /api/providers/openai/admin-key"] = MockResponse()
            .setResponseCode(422)
            .setBody("""{"code":"key_refused","detail":"The key was rejected"}""")

        val vm = UsageViewModel(sessions())
        var done = false
        vm.saveAdminKey("openai", "bad-key") { done = true }

        settle { vm.adminNote.value != null }
        assertFalse(done)
        assertEquals(Res.string.admin_key_refused, vm.adminNote.value)
        assertFalse(vm.adminBusy.value)
    }

    @Test fun removeAdminKeySendsDelete() {
        answers["GET /api/providers"] = MockResponse().setBody("""[]""")
        answers["DELETE /api/providers/openai/admin-key"] = MockResponse().setBody("""{"ok":true}""")

        val vm = UsageViewModel(sessions())
        var done = false
        vm.removeAdminKey("openai") { done = true }

        settle { done }
        assertTrue(done)
        val delReq = requests.firstOrNull { it.method == "DELETE" && it.path == "/api/providers/openai/admin-key" }
        assertTrue(delReq != null)
    }
}
