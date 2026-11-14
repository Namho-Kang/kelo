package chat.kelo.app.offers

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
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
class OffersViewModelTest {
    private val vms = chat.kelo.app.TestViewModels()
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var offersBody = """{"items":[{"id":"o1","sentence":"Task 1"},{"id":"o2","sentence":"Task 2"}]}"""
    @Volatile private var startAnswer: MockResponse = MockResponse().setBody("""{"turn_id":"t9"}""")

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
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                requests += "${request.method} $path $body"
                return when {
                    request.method == "GET" && path == "/api/offers" -> MockResponse().setBody(offersBody)
                    request.method == "POST" && path.startsWith("/api/offers/") && path.endsWith("/start") -> startAnswer
                    request.method == "POST" && path.startsWith("/api/offers/") && path.endsWith("/dismiss") -> MockResponse().setBody("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"detail":"no"}""")
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        server.shutdown()
        vms.clear(); Dispatchers.resetMain()
    }

    private fun sessions(): Sessions {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        return Sessions(store, requireE2e = false)
    }

    private fun until(ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun loadHasTwoItems() {
        val vm = OffersViewModel(sessions())
        until { vm.state.value.items.size == 2 }
        assertEquals(2, vm.state.value.items.size)
        assertEquals("o1", vm.state.value.items[0].id)
        assertEquals("o2", vm.state.value.items[1].id)
    }

    @Test fun startSuccessCallbackGetsTurnAndItemLeaves() {
        val vm = OffersViewModel(sessions())
        until { vm.state.value.items.size == 2 }
        val o1 = vm.state.value.items.first { it.id == "o1" }

        var turn: String? = null
        vm.start(o1) { turn = it }
        until { turn == "t9" }

        assertEquals("t9", turn)
        assertEquals(listOf("o2"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.starting)
        assertNull(vm.state.value.problem)
    }

    @Test fun startBusySetsProblemAndItemStays() {
        startAnswer = MockResponse().setResponseCode(409).setBody("""{"detail":"a reply is running"}""")
        val vm = OffersViewModel(sessions())
        until { vm.state.value.items.size == 2 }
        val o1 = vm.state.value.items.first { it.id == "o1" }

        vm.start(o1) {}
        until { vm.state.value.problem != null }

        assertEquals("o1" to OfferStart.Busy, vm.state.value.problem)
        assertTrue(vm.state.value.items.any { it.id == "o1" })
        assertNull(vm.state.value.starting)
    }

    @Test fun startWithCodeSetsProblemFailed() {
        startAnswer = MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"secret_in_message","message":"x"}}""")
        val vm = OffersViewModel(sessions())
        until { vm.state.value.items.size == 2 }
        val o1 = vm.state.value.items.first { it.id == "o1" }

        vm.start(o1) {}
        until { vm.state.value.problem != null }

        assertEquals("o1" to OfferStart.Failed, vm.state.value.problem)
        assertNull(vm.state.value.starting)
    }

    @Test fun dismissRemovesItemAtOnceAndCallsServer() {
        val vm = OffersViewModel(sessions())
        until { vm.state.value.items.size == 2 }
        val o2 = vm.state.value.items.first { it.id == "o2" }

        vm.dismiss(o2, OfferReason.DONE)
        assertFalse(vm.state.value.items.any { it.id == "o2" })

        until { requests.any { it.contains("POST /api/offers/o2/dismiss") && it.contains(""""reason":"done_already"""") } }
        assertTrue(requests.any { it.contains("POST /api/offers/o2/dismiss") && it.contains(""""reason":"done_already"""") })
    }
}
