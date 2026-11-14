package chat.kelo.app.wallet

import chat.kelo.app.chat.ShippingOutcome
import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

/**
 * The wallet's and the address pages' view models against a local fake server (MockWebServer) and a fake card key: the lock, the
 * list that is loaded only while the pages are open, and the address calls with their failures. Nothing reaches a real server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var walletAnswer = MockResponse().setBody("""{"paused":false,"auto_cards":0,"cards":[{"id":"c1","label":"Home card","last4":"4242"}]}""")
    @Volatile private var deleteAnswer = MockResponse().setBody("{}")
    @Volatile private var cancelAnswer = MockResponse().setBody("{}")
    private val clock = longArrayOf(1_000)

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** A card key that is there and never asks anything. */
    private class FakeKeys(override val available: Boolean = true) : CardKeys {
        override val credentialLeavesApp = false
        override fun keyId() = "fake-key"
        override fun forSaving(title: String, subtitle: String, onDone: (CardAuth) -> Unit) = onDone(CardAuth.Cancelled)
        override fun forPaying(iv: ByteArray, title: String, subtitle: String, onDone: (CardAuth) -> Unit) = onDone(CardAuth.Cancelled)
        override fun promptActive() = false
        override fun deviceSecure() = true
    }

    @Before fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path"
                return when {
                    request.method == "GET" && path == "/api/wallet" -> walletAnswer
                    request.method == "GET" && path.startsWith("/api/wallet/notifications") -> MockResponse().setBody("""{"items":[],"unseen":0}""")
                    request.method == "GET" && path == "/api/address/countries" -> MockResponse().setBody("""{"countries":[],"default":""}""")
                    request.method == "DELETE" && path.startsWith("/api/wallet/shipping/") -> deleteAnswer
                    request.method == "POST" && path.startsWith("/api/shipping-forms/") -> cancelAnswer
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

    /** The calls finish on other threads: waits (up to five seconds) until [ok]. */
    private fun until(ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun places() = WalletPlaces(CoroutineScope(Job()), { clock[0] })

    private fun wallet(keys: CardKeys = FakeKeys()) = vms.make { WalletViewModel(sessions(), keys, null, places()) { true } }

    @Test fun nothingIsLoadedBeforeTheOwnersCheckPasses() {
        val vm = wallet()
        assertEquals(LockPhase.LOCKED, vm.state.value.phase)
        assertNull(vm.state.value.info)
        assertTrue(requests.none { it.startsWith("GET /api/wallet") })
    }

    @Test fun theCardsAreLoadedWhenTheCheckPassesAndDroppedWhenTheAppLeaves() {
        val vm = wallet()
        vm.onResume()   // the check comes up by itself once
        vm.checkPassed()
        assertTrue(vm.state.value.open)
        until { vm.state.value.info != null }
        assertEquals("Home card", vm.state.value.info?.cards?.single()?.label)
        assertEquals("fake-key", vm.state.value.keyId)
        vm.onStop()
        assertFalse(vm.state.value.open)
        assertNull(vm.state.value.info)
        assertNull(vm.state.value.keyId)
    }

    @Test fun aFailedLoadSaysWhyAndKeepsThePagesOpen() {
        walletAnswer = MockResponse().setResponseCode(500).setBody("""{"detail":"server broke"}""")
        val vm = wallet()
        vm.checkPassed()
        assertTrue(vm.state.value.open)
        until { vm.state.value.failed.isNotEmpty() }
        assertNull(vm.state.value.info)
        assertEquals("server broke", vm.state.value.failed)
    }

    @Test fun aDeviceWithoutALockNeverOpens() {
        val vm = wallet()
        vm.noLock()
        assertEquals(LockPhase.NO_LOCK, vm.state.value.phase)
        assertFalse(vm.state.value.open)
    }

    @Test fun theOpenPageIsKeptForTheNextCheck() {
        val places = places()
        val vm = vms.make { WalletViewModel(sessions(), FakeKeys(), null, places) { true } }
        vm.checkPassed()
        vm.go(WalletView.Limits)
        vm.onStop()
        vm.tap()
        vm.checkPassed()
        assertEquals(WalletView.Limits, vm.state.value.view)
        vm.leave()
        assertEquals(WalletView.List, places.view())
    }

    @Test fun cancellingTheAddressQuestionFoldsIt() = runTest {
        val vm = vms.make { ShippingAskViewModel(sessions(), ShippingAsk(id = "a", formId = "f1"), "en") }
        vm.cancel()
        assertEquals(ShippingOutcome.Cancelled, vm.outcomes.first())
        assertEquals("", vm.state.value.msg)
        assertTrue(requests.any { it == "POST /api/shipping-forms/f1" })
    }

    @Test fun anAddressQuestionThatIsGoneSaysSo() = runTest {
        cancelAnswer = MockResponse().setResponseCode(404).setBody("""{"detail":"gone"}""")
        val vm = vms.make { ShippingAskViewModel(sessions(), ShippingAsk(id = "a", formId = "f2"), "en") }
        vm.cancel()
        assertEquals(ShippingOutcome.Gone, vm.outcomes.first())
    }

    @Test fun deletingASavedAddressTellsThePage() = runTest {
        val vm = vms.make { ShippingEditViewModel(sessions(), places(), ShippingProfile(id = "p1", label = "Home"), firstOne = false, home = "Home", lang = "en") }
        vm.delete()
        val seen = listOf(vm.events.first(), vm.events.first())
        assertEquals(listOf(WalletEvent.Confirm, WalletEvent.Deleted), seen)
        assertTrue(requests.any { it == "DELETE /api/wallet/shipping/p1" })
    }

    @Test fun aRefusedDeleteShowsTheServersWords() = runTest {
        deleteAnswer = MockResponse().setResponseCode(409).setBody("""{"detail":"it is the only one"}""")
        val vm = vms.make { ShippingEditViewModel(sessions(), places(), ShippingProfile(id = "p2", label = "Home"), firstOne = false, home = "Home", lang = "en") }
        vm.delete()
        assertEquals(WalletEvent.Reject, vm.events.first())
        assertEquals("it is the only one", vm.state.value.msg)
        assertNotNull(vm.profile)
    }
}
