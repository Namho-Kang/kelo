package chat.kelo.app.my

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import javax.crypto.KeyGenerator

/** The My page's view models against a local fake server (MockWebServer): the same calls the composables used to make. */
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    /** the answer to a request, by "METHOD path"; null = 404 */
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
                val key = "${request.method} ${request.path}"
                requests += key
                return answers[key] ?: MockResponse().setResponseCode(404).setBody("""{"detail":"none"}""")
            }
        }
        server.start()
    }

    @After fun stop() {
        vms.clear(); Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private fun sessions(): Sessions {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        return Sessions(store, requireE2e = false)
    }

    /** waits until the view model's action has answered (the call runs on the IO dispatcher) */
    private fun settle(vm: ServerUpdateViewModel) {
        val end = System.currentTimeMillis() + 10_000
        while ((vm.state.value.busy || vm.state.value.checking) && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    private fun ok(body: String) = MockResponse().setBody(body)

    @Test fun theHomeNumbersArriveAndAreKept() {
        answers["GET /api/devices"] = ok("""{"devices":[{"id":1,"name":"a"},{"id":2,"name":"b"}],"current":1}""")
        answers["GET /api/about"] = ok("""{"version":"1.2.3"}""")
        val sessions = sessions()
        val vm = vms.make { MyOverviewViewModel(sessions, ServerUpdateViewModel(sessions)) }
        runBlocking { vm.refresh() }
        val o = vm.state.value.overview
        assertEquals(2, o.devices)
        assertEquals("1.2.3", o.version)
        assertFalse(vm.state.value.offline)   // some parts answered, the rest were plain 404s
    }

    @Test fun whenNothingAnswersTheHomeSaysOffline() {
        val sessions = sessions()
        val vm = vms.make { MyOverviewViewModel(sessions, ServerUpdateViewModel(sessions)) }
        server.shutdown()
        runBlocking { vm.refresh() }
        assertTrue(vm.state.value.offline)
        assertNull(vm.state.value.overview.devices)
    }

    @Test fun clearingTheChatCallsTheServerThenForgetsLocally() {
        answers["DELETE /api/messages"] = ok("{}")
        val sessions = sessions()
        val vm = vms.make { MyOverviewViewModel(sessions, ServerUpdateViewModel(sessions)) }
        var cancelled = false
        val error = runBlocking { vm.clearChat { cancelled = true } }
        assertNull(error)
        assertTrue(cancelled)
        assertTrue("DELETE /api/messages" in requests)
    }

    @Test fun clearingTheChatReportsTheServersMessage() {
        answers["DELETE /api/messages"] = MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}""")
        val sessions = sessions()
        val vm = vms.make { MyOverviewViewModel(sessions, ServerUpdateViewModel(sessions)) }
        var cancelled = false
        assertEquals("boom", runBlocking { vm.clearChat { cancelled = true } })
        assertFalse(cancelled)
    }

    private val available = """{"state":"ok","current":"1.0.0","latest":{"version":"1.1.0"},"update_available":true}"""

    @Test fun theServerUpdatePageShowsTheStatusAfterAnAction() {
        answers["POST ${ServerUpdateRules.PATH}/check"] = ok(available)
        val vm = vms.make { ServerUpdateViewModel(sessions()) }
        vm.checkNow()
        settle(vm)
        val s = vm.state.value
        assertEquals("1.1.0", s.status?.latest?.version)
        assertFalse(s.busy)
        assertFalse(s.checking)
        assertNull(s.error)
    }

    @Test fun aRefusedServerUpdateShowsTheServersReason() {
        answers["POST ${ServerUpdateRules.PATH}/update"] = MockResponse().setResponseCode(409).setBody("""{"detail":"Another update is running"}""")
        val vm = vms.make { ServerUpdateViewModel(sessions()) }
        vm.update()
        settle(vm)
        val s = vm.state.value
        assertEquals("Another update is running", s.error)
        assertFalse(s.busy)
        assertNotNull(s)
    }
}
