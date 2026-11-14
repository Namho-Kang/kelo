package chat.kelo.app.start

import chat.kelo.app.core.PairingClient
import chat.kelo.app.core.Profile
import chat.kelo.app.core.UiText
import chat.kelo.app.my.MyFakeServer
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import chat.kelo.app.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The start screens' view models: the connect screen's link handling and the first-run profile gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }

    @After fun stop() { vms.clear(); server?.shutdown(); Dispatchers.resetMain() }

    private fun serve(answer: (String, String, String) -> okhttp3.mockwebserver.MockResponse?) = MyFakeServer(answer).also { server = it }

    private fun connect(s: MyFakeServer, allowHttp: Boolean = false) =
        vms.make { ConnectViewModel(s.sessions(), PairingClient(OkHttpClient()), { "Test · Kelo" }, allowHttp) }

    @Test fun aPairingLinkWaitsForTheOwnersConfirmationBeforeAnythingIsSent() {
        val s = serve { _, _, _ -> null }
        val vm = connect(s)
        assertTrue(vm.useLink("https://kelo.example.com/#pair=ABCDEFGHJKMNPQRS"))
        assertEquals("https://kelo.example.com", vm.state.value.confirm?.serverUrl)
        assertTrue("nothing is sent yet", s.requests.isEmpty())
        vm.cancelPair()
        assertNull(vm.state.value.confirm)
    }

    @Test fun aTextThatIsNotALinkAndAPlainHttpServerAreRefusedWithTheirOwnLines() {
        val vm = connect(serve { _, _, _ -> null })
        assertFalse(vm.useLink("hello"))
        assertEquals(UiText.Res(Res.string.connect_link_invalid), vm.state.value.linkError)
        assertFalse(vm.useLink("http://kelo.example.com/#pair=ABCDEFGHJKMNPQRS"))
        assertEquals(UiText.Res(Res.string.connect_https_only), vm.state.value.linkError)
        vm.connectWithKey("not an address", "key")
        assertEquals(UiText.Res(Res.string.connect_server_invalid), vm.state.value.keyError)
    }

    @Test fun aLinkHandedOverAtStartIsUsedAtOnce() {
        val vm = ConnectViewModel(serve { _, _, _ -> null }.sessions(), PairingClient(OkHttpClient()), { "x" }, false, "https://kelo.example.com/#pair=ABCDEFGHJKMNPQRS")
        assertNotNull(vm.state.value.confirm)
    }

    @Test fun theProfileLoadsAfterSignInAndAServerErrorKeepsTheFailedPage() {
        val s = serve { m, p, _ -> if (m == "GET" && p == "/api/profile") ok("""{"user_name":"a","agent_name":"Kelo"}""") else null }
        val sessions = s.sessions()
        val vm = vms.make { AppStateViewModel(sessions) }
        until("ready") { vm.profile.value is ProfileState.Ready }
        assertEquals("Kelo", (vm.profile.value as ProfileState.Ready).profile.agentName)

        val bad = serve { _, _, _ -> fail(500, "down") }
        val vm2 = vms.make { AppStateViewModel(bad.sessions()) }
        until("failed") { vm2.profile.value == ProfileState.Failed }
    }

    @Test fun theSetupFormSavesBothNamesOrShowsTheServersWords() {
        val s = serve { m, p, _ -> if (m == "PUT" && p == "/api/profile") ok("""{"user_name":"Jo","agent_name":"Kelo"}""") else null }
        val vm = vms.make { OnboardingViewModel(s.sessions()) }
        var saved: Profile? = null
        vm.save(" Jo ", "Kelo", { saved = it })
        until("saved") { saved != null }
        assertEquals("Jo", saved!!.userName)

        val bad = serve { _, _, _ -> fail(400, "too long") }
        val vm2 = vms.make { OnboardingViewModel(bad.sessions()) }
        vm2.save("a", "b", { })
        until("the error") { vm2.state.value.error != null }
        assertEquals("too long", vm2.state.value.error!!.detail)
    }
}
