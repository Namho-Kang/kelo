package chat.kelo.app.keyguard

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/** The Keyguard page's view model against a local fake server and a fake device key. Nothing reaches a real server. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardViewModelTest {
    private val vms = chat.kelo.app.TestViewModels()
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var viewBody = """{"mode":"phone","locked":true,"phones":[{"device_id":7,"name":"Pixel","alg":1}]}"""
    @Volatile private var recoverAnswer = MockResponse().setBody("""{"ok":true,"locked":false}""")

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** A device key that passes the check at once; "encrypts" by appending 16 zero bytes (a GCM tag's size). */
    private class FakeKeys(override val available: Boolean = true) : KeyguardKeys {
        var forgot = 0
        override val checkEveryUse = true
        private val cipher = object : KeyguardCipher {
            override val iv = ByteArray(12) { 1 }
            override fun doFinal(input: ByteArray) = input + ByteArray(16)
        }
        override fun forWrap(title: String, subtitle: String, onDone: (KeyguardAuth) -> Unit) = onDone(KeyguardAuth.Ok(cipher))
        override fun forUnwrap(iv: ByteArray, title: String, subtitle: String, onDone: (KeyguardAuth) -> Unit) = onDone(KeyguardAuth.Cancelled)
        override fun forget() { forgot++ }
    }

    private val prompts = KeyguardPrompts("wrap", "title", "subtitle")

    @Before fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path ${request.body.readUtf8()}"
                return when {
                    request.method == "GET" && path == "/api/keyguard" -> MockResponse().setBody(viewBody)
                    request.method == "GET" && path == "/api/devices" -> MockResponse().setBody("""{"devices":[],"current":9}""")
                    request.method == "POST" && path == "/api/keyguard/recover" -> recoverAnswer
                    request.method == "POST" && path == "/api/keyguard/enroll" ->
                        MockResponse().setBody("""{"enrollment_id":"e1","key":"abc","enabling":true,"recovery_code":"ABCDEFGHJKLMNPQRSTUVWXYZ"}""")
                    request.method == "POST" && path == "/api/keyguard/enroll/finish" ->
                        MockResponse().setBody("""{"mode":"phone","locked":false,"phones":[{"device_id":9,"name":"Mine","alg":1}]}""")
                    request.method == "POST" && path == "/api/keyguard/disable" -> MockResponse().setBody("""{"mode":"server"}""")
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

    @Test fun loadsTheStateAndTellsTheSessionItIsLocked() {
        val s = sessions()
        val vm = vms.make { KeyguardViewModel(s, FakeKeys()) }
        until { vm.state.value.view != null }
        assertTrue(vm.state.value.view!!.locked)
        assertEquals(9L, vm.state.value.me)
        until { s.locked.value }
        assertTrue(s.locked.value)
    }

    @Test fun theRecoveryCodeUnlocks() {
        val s = sessions()
        val vm = vms.make { KeyguardViewModel(s, KeyguardKeys.None, startWithCode = true) }
        assertTrue(vm.state.value.codeOpen)
        until { vm.state.value.view != null }
        vm.typeCode("abcd-efgh-jklm-npqr-stuv-wxyz")
        viewBody = """{"mode":"phone","locked":false,"phones":[]}"""
        vm.recover()
        until { vm.state.value.unlocked }
        assertTrue(requests.any { it.startsWith("POST /api/keyguard/recover") && "ABCDEFGHJKLMNPQRSTUVWXYZ" in it })
        assertFalse(vm.state.value.codeOpen)
        assertEquals("", vm.state.value.code)
        until { !s.locked.value }
        assertFalse(s.locked.value)
    }

    @Test fun aWrongRecoveryCodeSaysSo() {
        recoverAnswer = MockResponse().setResponseCode(422).setBody("""{"detail":{"code":"wrong_code","message":"no"}}""")
        val vm = vms.make { KeyguardViewModel(sessions(), KeyguardKeys.None, startWithCode = true) }
        vm.typeCode("ABCDEFGHJKLMNPQRSTUVWXYZ")
        vm.recover()
        until { vm.state.value.problem != null }
        assertEquals(KeyguardProblem.CODE_WRONG, vm.state.value.problem)
    }

    @Test fun turningItOnShowsTheRecoveryCodeOnceThenSaysItIsOn() = runBlocking {
        viewBody = """{"mode":"server"}"""
        val vm = vms.make { KeyguardViewModel(sessions(), FakeKeys(), enableNow = true) }
        assertEquals(KeyguardConfirm.TURN_ON, vm.state.value.confirm)
        vm.confirmed(prompts)
        until { vm.state.value.recoveryCode != null }
        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZ", vm.state.value.recoveryCode)
        assertTrue(requests.any { it.startsWith("POST /api/keyguard/enroll/finish") && "\"enrollment_id\":\"e1\"" in it })
        vm.recoveryDone()
        assertNull(vm.state.value.recoveryCode)
        withTimeout(5_000) { vm.enabled.first() }
    }

    @Test fun aDeviceWithoutAKeyCannotEnrol() {
        viewBody = """{"mode":"server"}"""
        val vm = vms.make { KeyguardViewModel(sessions(), KeyguardKeys.None) }
        vm.enroll(prompts)
        Thread.sleep(200)
        assertTrue(requests.none { it.startsWith("POST /api/keyguard/enroll") })
    }

    @Test fun turningItOffForgetsThisDevicesKey() {
        viewBody = """{"mode":"phone","locked":false,"phones":[{"device_id":9,"name":"Mine","alg":1}]}"""
        val keys = FakeKeys()
        val vm = vms.make { KeyguardViewModel(sessions(), keys) }
        until { vm.state.value.view != null }
        vm.ask(KeyguardConfirm.TURN_OFF)
        vm.confirmed(prompts)
        until { vm.state.value.view?.on == false }
        assertEquals(1, keys.forgot)
    }

    @Test fun problemsFromTheServer() {
        assertEquals(KeyguardProblem.ENV_KEY, KeyguardProblems.of(ApiError(409, "x", code = "env_key")))
        assertEquals(KeyguardProblem.LAST_PHONE, KeyguardProblems.of(ApiError(409, "x", code = "last_keyguard_phone")))
        assertEquals(KeyguardProblem.NOT_OWNER, KeyguardProblems.of(ApiError(403, "x")))
        assertNull(KeyguardProblems.of(ApiError(500, "x")))
        assertEquals(KeyguardProblem.KEY_LOST, KeyguardProblems.ofUnlock(ApiError(422, "x", code = "wrong_key")))
        assertNull(KeyguardProblems.of(KeyguardAuth.Cancelled))
        assertEquals(KeyguardProblem.NO_LOCK, KeyguardProblems.of(KeyguardAuth.NoLock))
    }
}
