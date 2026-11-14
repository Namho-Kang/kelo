package chat.kelo.app.connectors

import chat.kelo.app.my.MyFakeServer
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import chat.kelo.app.platform.ExternalBrowser
import chat.kelo.app.platform.FilePicker
import chat.kelo.app.platform.PickedFile
import chat.kelo.app.platform.PickedImage
import chat.kelo.app.platform.SaveTarget
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** The connect panels' view models against a local fake server (MockWebServer): the calls the composables used to make themselves. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelsTest {
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }   // real time: the polls wait with delay()

    // the view models are cleared with the test (as a page leaving would): none is left running when Main goes away
    private val made = mutableListOf<ViewModel>()
    private fun <T : ViewModel> T.tracked(): T = also { made += it }

    @After fun stop() {
        runBlocking { made.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }   // finished, not only asked to stop
        server?.shutdown()
        Dispatchers.resetMain()
    }

    private fun fake(answer: (method: String, path: String, body: String) -> okhttp3.mockwebserver.MockResponse?) = MyFakeServer(answer).also { server = it }

    private class FakeBrowser(private val opens: Boolean = true) : ExternalBrowser {
        val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun open(url: String): Boolean { opened += url; return opens }
    }

    private fun count(s: MyFakeServer, prefix: String) = s.requests.count { it.startsWith(prefix) }

    // ---- the list and the lookup

    @Test fun theListLoadsWithItsConnectionsAndAFailedLoadIsSaid() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/connectors" -> ok("""[{"id":"gmail","name":"Gmail","status":"available","connect":"mail"}]""")
                "GET /api/credentials" -> ok("""[]""")
                else -> null
            }
        }
        val vm = ConnectorsViewModel(s.sessions(), null).tracked()
        vm.reload()
        until("the list") { vm.state.value.loaded }
        assertEquals(listOf("gmail"), vm.state.value.list.map { it.id })
        assertFalse(vm.state.value.failed)

        val down = fake { _, _, _ -> fail(500, "down") }
        val bad = ConnectorsViewModel(down.sessions(), null).tracked()
        bad.reload()
        until("the failure") { bad.state.value.loaded }
        assertTrue(bad.state.value.failed)
    }

    @Test fun anOpenConnectorTheServerNoLongerListsClosesAndThePhoneCalendarRowIsAskedFor() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/connectors" -> ok("""[{"id":"gmail","name":"Gmail"}]""")
                "GET /api/credentials" -> ok("""[]""")
                else -> null
            }
        }
        val vm = ConnectorsViewModel(s.sessions(), "gone").tracked()
        assertEquals("gone", vm.state.value.openId)
        vm.reload()
        until("reloaded") { vm.state.value.loaded }
        assertNull(vm.state.value.openId)

        vm.setQuery("x"); vm.open("gmail")
        vm.showPhoneCalendar()
        assertTrue(vm.state.value.phoneCalendar)
        assertEquals("", vm.state.value.query)
        assertNull(vm.state.value.openId)
        vm.phoneCalendarShown()
        assertFalse(vm.state.value.phoneCalendar)
    }

    @Test fun aPanelWithoutAConnectorFindsItById() {
        val s = fake { m, p, _ -> if ("$m $p" == "GET /api/connectors") ok("""[{"id":"a"},{"id":"b","name":"B"}]""") else null }
        val vm = ConnectPanelViewModel(s.sessions(), "b").tracked()
        vm.lookUp()
        until("found") { vm.state.value.looked != null }
        assertEquals("B", vm.state.value.looked?.name)
    }

    // ---- hosted MCP services

    @Test fun mcpSignInOpensTheServicesPageAndTheButtonIsReadyWhenTheUserIsBack() {
        val s = fake { m, p, _ -> if ("$m $p" == "POST /api/mcp-oauth/linear/start") ok("""{"url":"https://linear.app/oauth"}""") else null }
        val browser = FakeBrowser()
        val vm = McpConnectViewModel(s.sessions(), browser, "linear").tracked()
        vm.signIn()
        until("the page opens") { browser.opened.isNotEmpty() }
        assertEquals(listOf("https://linear.app/oauth"), browser.opened)
        assertTrue("waiting for the user to come back", vm.state.value.busy)
        vm.returned()
        assertFalse(vm.state.value.busy)
    }

    @Test fun mcpSignInSaysWhyItFailed() {
        val s = fake { _, _, _ -> fail(502, "service unreachable") }
        val vm = McpConnectViewModel(s.sessions(), FakeBrowser(), "linear").tracked()
        vm.signIn()
        until("the error") { vm.state.value.error != null }
        assertEquals(ConnectError.Said("service unreachable"), vm.state.value.error)
        assertFalse(vm.state.value.busy)

        val ok = fake { _, _, _ -> ok("""{"url":"https://x.test"}""") }
        val noBrowser = McpConnectViewModel(ok.sessions(), FakeBrowser(opens = false), "linear").tracked()
        noBrowser.signIn()
        until("no browser") { noBrowser.state.value.error != null }
        assertEquals(ConnectError.NoBrowser, noBrowser.state.value.error)
        assertFalse(noBrowser.state.value.busy)
    }

    // ---- mail

    private val mailProviders = """{"providers":[{"id":"naver","name":"Naver"}],"accounts":[],"google_web":true}"""

    @Test fun aMailboxIsAddedAndTheListIsReadAgain() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/mail/providers" -> ok(mailProviders)
                "POST /api/mail/accounts" -> ok("{}")
                else -> null
            }
        }
        val vm = MailConnectViewModel(s.sessions(), FakeBrowser()).tracked()
        vm.load()
        until("providers") { vm.state.value.data != null }
        val added = AtomicInteger()
        vm.add("naver", " me@naver.com ", "app-pass") { added.incrementAndGet() }
        until("added") { added.get() == 1 && !vm.state.value.busy }
        assertTrue(s.saw("POST /api/mail/accounts"))
        until("read again") { count(s, "GET /api/mail/providers") >= 2 }
    }

    @Test fun aMailboxTheServerRefusesKeepsTheFormAndSaysWhy() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/mail/providers" -> ok(mailProviders)
                "POST /api/mail/accounts" -> fail(400, "login refused")
                else -> null
            }
        }
        val vm = MailConnectViewModel(s.sessions(), FakeBrowser()).tracked()
        val added = AtomicInteger()
        vm.add("naver", "me@naver.com", "wrong") { added.incrementAndGet() }
        until("refused") { vm.state.value.error != null && !vm.state.value.busy }
        assertEquals(ConnectError.Said("login refused"), vm.state.value.error)
        assertEquals(0, added.get())
    }

    @Test fun gmailsGoogleSignInOpensGooglesPage() {
        val s = fake { m, p, _ -> if ("$m $p" == "POST /api/google/web/start") ok("""{"url":"https://accounts.google.test/o"}""") else null }
        val browser = FakeBrowser()
        MailConnectViewModel(s.sessions(), browser).tracked().signInWithGoogle()
        until("google") { browser.opened.isNotEmpty() }
        assertEquals("https://accounts.google.test/o", browser.opened.single())
    }

    // ---- keys

    private class Picker(private val bytes: ByteArray?) : FilePicker {
        override suspend fun pickImage(maxBytes: Long): PickedImage? = null
        override suspend fun pickFile(types: List<String>): PickedFile? = bytes?.let { PickedFile("key.json", it.size.toLong(), "application/json") { ByteArrayInputStream(it) } }
        override suspend fun saveFile(name: String, type: String): SaveTarget? = null
    }

    private val keyForm = """{"id":"zoom","name":"Zoom","item":"zoom","checked":true,"fields":[{"id":"token","label":"Token","secret":true}]}"""

    @Test fun aKeyIsSavedAndTheFormIsReadAgain() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/keys/zoom" -> ok(keyForm)
                "POST /api/keys/zoom" -> ok("{}")
                else -> null
            }
        }
        val vm = KeyConnectViewModel(s.sessions(), "zoom", Picker(null)).tracked()
        vm.load()
        until("form") { vm.state.value.form != null }
        val saved = AtomicInteger()
        vm.save(mapOf("token" to "t0k3n")) { saved.incrementAndGet() }
        until("saved") { saved.get() == 1 && !vm.state.value.busy }
        until("read again") { count(s, "GET /api/keys/zoom") >= 2 }
    }

    @Test fun aKeyTheServerCannotUseIsSaid() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/keys/zoom" -> ok(keyForm)
                "POST /api/keys/zoom" -> fail(400, "the service did not accept it")
                else -> null
            }
        }
        val vm = KeyConnectViewModel(s.sessions(), "zoom", Picker(null)).tracked()
        vm.load()
        until("form") { vm.state.value.form != null }
        vm.save(mapOf("token" to "bad")) {}
        until("said") { vm.state.value.error != null && !vm.state.value.busy }
        assertEquals(ConnectError.Said("the service did not accept it"), vm.state.value.error)
    }

    @Test fun aPickedKeyFileGivesItsTextUnlessItIsTooLarge() {
        val s = fake { _, _, _ -> null }
        var text: String? = null
        val small = KeyConnectViewModel(s.sessions(), "google", Picker("""{"type":"service_account"}""".toByteArray())).tracked()
        small.pickKeyFile { text = it }
        until("text") { text != null }
        assertEquals("""{"type":"service_account"}""", text)

        val big = KeyConnectViewModel(s.sessions(), "google", Picker(ByteArray(70 * 1024) { 'a'.code.toByte() })).tracked()
        var bigText: String? = null
        big.pickKeyFile { bigText = it }
        until("unreadable") { big.state.value.error != null }
        assertEquals(ConnectError.FileUnreadable, big.state.value.error)
        assertNull(bigText)
    }

    // ---- browser sites

    @Test fun aSiteShowsWhetherTheVaultKeepsItsLoginAndAsksForOne() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/vault" -> ok("""{"items":[{"name":"shop","kind":"login","hosts":["shop.test"],"username_hint":"me"}]}""")
                "POST /api/vault/logins" -> ok("""{"name":"login-7"}""")
                else -> null
            }
        }
        val kept = BrowserConnectViewModel(s.sessions(), FakeBrowser(), "https://shop.test/", "Shop").tracked()
        kept.load()
        until("kept") { kept.state.value.kept != LoginKept.Loading }
        assertEquals(LoginKept.Some("me"), kept.state.value.kept)

        val none = BrowserConnectViewModel(s.sessions(), FakeBrowser(), "https://other.test/", "Other").tracked()
        none.load()
        until("none") { none.state.value.kept != LoginKept.Loading }
        assertEquals(LoginKept.None, none.state.value.kept)
        none.askForLogin()
        until("asking") { none.state.value.asking != null }
        assertEquals("login-7", none.state.value.asking)
    }

    @Test fun theSiteOpensInTheBrowserOrSaysNothingOpened() {
        val s = fake { _, _, _ -> null }
        val vm = BrowserConnectViewModel(s.sessions(), FakeBrowser(opens = false), "https://shop.test/", "Shop").tracked()
        vm.openSite()
        assertEquals(ConnectError.NoBrowser, vm.state.value.error)
    }

    // ---- CalDAV and Google Calendar

    @Test fun aCalendarIsAddedOrRefused() {
        var refuse = false
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/caldav" -> ok("""{"accounts":[{"name":"c1","address":"me@x.test","url":"https://x.test/dav"}]}""")
                "POST /api/caldav/accounts" -> if (refuse) fail(400, "wrong password") else ok("{}")
                else -> null
            }
        }
        val vm = CalDavConnectViewModel(s.sessions()).tracked()
        vm.load()
        until("accounts") { vm.state.value.accounts != null }
        assertEquals("me@x.test", vm.state.value.accounts?.single()?.address)
        val added = AtomicInteger()
        vm.add(" https://x.test/dav ", "me", "pw") { added.incrementAndGet() }
        until("added") { added.get() == 1 && !vm.state.value.busy }
        refuse = true
        vm.add("https://x.test/dav", "me", "bad") { added.incrementAndGet() }
        until("refused") { vm.state.value.error != null && !vm.state.value.busy }
        assertEquals(ConnectError.Said("wrong password"), vm.state.value.error)
        assertEquals(1, added.get())
    }

    @Test fun googleCalendarReadsItsAccountsAndSignsInOnGooglesPage() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/google/calendar" -> ok("""{"available":true,"accounts":[{"name":"g1","address":"me@gmail.test","can_write":true}]}""")
                "POST /api/google/web/start" -> ok("""{"url":"https://accounts.google.test/cal"}""")
                else -> null
            }
        }
        val browser = FakeBrowser()
        val vm = GoogleConnectViewModel(s.sessions(), browser).tracked()
        vm.load()
        until("state") { vm.state.value.google != null }
        assertEquals("me@gmail.test", vm.state.value.google?.accounts?.single()?.address)
        vm.signIn()
        until("page") { browser.opened.isNotEmpty() }
        assertTrue(vm.state.value.busy)
        vm.returned()
        assertFalse(vm.state.value.busy)
    }

    @Test fun googleCalendarThatCannotBeReadIsFlaggedAsFailed() {
        val s = fake { _, _, _ -> fail(500, "down") }
        val vm = GoogleConnectViewModel(s.sessions(), FakeBrowser()).tracked()
        vm.load()
        until("failed") { vm.state.value.failed }
        assertNull(vm.state.value.google)
    }

    // ---- the code typed on the service's page

    private val githubProviders = """[{"id":"github","item":"github","name":"GitHub","has_client_id":true,"scopes":[{"id":"repo","label":"Repos"},{"id":"gist","label":"Gists"}]}]"""
    private fun pendingFlow(status: String) = """{"id":"f1","provider":"github","status":"$status","user_code":"AB-12","verification_uri":"https://github.test/device","expires_in":600}"""

    @Test fun aCodeAttemptIsFollowedUntilTheServiceIsConnected() = runBlocking {
        val polls = AtomicInteger()
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth/providers" -> ok(githubProviders)
                "POST /api/oauth/github/start" -> ok(pendingFlow("pending"))
                "GET /api/oauth/flows/f1" -> ok(pendingFlow(if (polls.incrementAndGet() >= 2) "connected" else "pending"))
                else -> null
            }
        }
        val vm = DeviceConnectViewModel(s.sessions(), FakeBrowser(), "github", tickMs = 10).tracked()
        vm.load()
        until("provider") { vm.state.value.provider != null }
        assertEquals("the first permission is the default", "repo", vm.state.value.scopeId)

        val connected = AtomicInteger()
        val events = launch(Dispatchers.Default) { vm.connected.collect { connected.incrementAndGet() } }
        val look = launch(Dispatchers.Default) { vm.state.collect { } }
        Thread.sleep(100)
        vm.startCode()
        until("code shown") { vm.state.value.flow?.userCode == "AB-12" }
        until("connected") { connected.get() == 1 }
        assertEquals("connected", vm.state.value.flow?.status)
        look.cancelAndJoin(); events.cancelAndJoin()
    }

    @Test fun theCodePollStopsWhenNobodyIsLookingAndGoesOnWhenTheyAreBack() = runBlocking {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth/providers" -> ok(githubProviders)
                "POST /api/oauth/github/start" -> ok(pendingFlow("pending"))
                "GET /api/oauth/flows/f1" -> ok(pendingFlow("pending"))
                else -> null
            }
        }
        val vm = DeviceConnectViewModel(s.sessions(), FakeBrowser(), "github", tickMs = 10).tracked()
        vm.load()
        until("provider") { vm.state.value.provider != null }
        val look = launch(Dispatchers.Default) { vm.state.collect { } }
        Thread.sleep(100)
        vm.startCode()
        until("two polls") { count(s, "GET /api/oauth/flows/f1") >= 2 }
        look.cancelAndJoin()   // the page is left or the app goes to the background
        Thread.sleep(150)
        val after = count(s, "GET /api/oauth/flows/f1")
        Thread.sleep(400)
        assertEquals("no request while nobody collects", after, count(s, "GET /api/oauth/flows/f1"))

        val again = launch(Dispatchers.Default) { vm.state.collect { } }
        until("polling again") { count(s, "GET /api/oauth/flows/f1") > after }
        again.cancelAndJoin()
    }

    @Test fun aCodeAttemptTheServerForgotAndACancelledOneEndTheWait() = runBlocking {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth/providers" -> ok(githubProviders)
                "POST /api/oauth/github/start" -> ok(pendingFlow("pending"))
                "GET /api/oauth/flows/f1" -> fail(404, "unknown attempt")
                "DELETE /api/oauth/flows/f1" -> ok("{}")
                else -> null
            }
        }
        val vm = DeviceConnectViewModel(s.sessions(), FakeBrowser(), "github", tickMs = 10).tracked()
        vm.load()
        until("provider") { vm.state.value.provider != null }
        val look = launch(Dispatchers.Default) { vm.state.collect { } }
        Thread.sleep(100)
        vm.startCode()
        until("the attempt is gone") { vm.state.value.flow == null && s.saw("GET /api/oauth/flows/f1") }

        vm.startCode()
        until("a new code") { vm.state.value.flow != null || s.requests.count { it.startsWith("POST /api/oauth/github/start") } >= 2 }
        vm.cancelCode()
        until("cancelled") { s.saw("DELETE /api/oauth/flows/f1") }
        look.cancelAndJoin()
    }

    @Test fun theServiceStartingTheCodeFailsWithTheServersWords() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth/providers" -> ok(githubProviders)
                "POST /api/oauth/github/start" -> fail(400, "client id rejected")
                else -> null
            }
        }
        val vm = DeviceConnectViewModel(s.sessions(), FakeBrowser(), "github").tracked()
        vm.load()
        until("provider") { vm.state.value.provider != null }
        vm.startCode()
        until("error") { vm.state.value.error != null && !vm.state.value.busy }
        assertEquals(ConnectError.Said("client id rejected"), vm.state.value.error)
        assertNull(vm.state.value.flow)
    }

    // ---- the operator's OAuth app

    @Test fun anOwnerSeesTheOAuthAppAndSavesIt() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth-apps/github" -> ok("""{"client_id":"abc","has_secret":true,"callback":"https://x/cb"}""")
                "PUT /api/oauth-apps/github" -> ok("""{"client_id":"def","has_secret":true}""")
                else -> null
            }
        }
        val vm = OperatorSettingsViewModel(s.sessions(), "github").tracked()
        until("owner") { vm.state.value.owner == true }
        assertEquals("abc", vm.state.value.app?.clientId)
        val saved = AtomicInteger()
        vm.save(" def ", "secret") { saved.incrementAndGet() }
        until("saved") { vm.state.value.message == OperatorMessage.Saved }
        assertEquals("def", vm.state.value.app?.clientId)
        assertEquals(1, saved.get())
    }

    @Test fun aPairedPhoneIsNotShownTheOperatorSettingsAndASaveFailureIsSaid() {
        val phone = fake { _, _, _ -> fail(403, "owner only") }
        val vm = OperatorSettingsViewModel(phone.sessions(), "github").tracked()
        until("not owner") { vm.state.value.owner == false }

        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth-apps/google" -> ok("""{"client_id":"abc"}""")
                "PUT /api/oauth-apps/google" -> fail(400, "client id looks wrong")
                "PUT /api/oauth-apps/google/open" -> ok("""{"client_id":"abc","open_to_all":true}""")
                else -> null
            }
        }
        val owner = OperatorSettingsViewModel(s.sessions(), "google").tracked()
        until("owner") { owner.state.value.owner == true }
        owner.save("x", "") {}
        until("said") { owner.state.value.message != null }
        assertEquals(OperatorMessage.Said("client id looks wrong"), owner.state.value.message)
        owner.setOpenToAll(true)
        until("open") { owner.state.value.app?.openToAll == true && !owner.state.value.switchBusy }
    }

    // ---- asking to try Google sign-in early, and one connector's own page

    @Test fun aBetaRequestIsSentOrRefused() {
        var refuse = false
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/google/beta" -> ok("""{"status":"none","can_request":true}""")
                "POST /api/google/beta" -> if (refuse) fail(429, "too many requests") else ok("""{"status":"requested","email":"me@gmail.test"}""")
                else -> null
            }
        }
        val vm = BetaRequestViewModel(s.sessions(), "0.9.0").tracked()
        vm.load()
        until("state") { vm.state.value.beta != null }
        val sent = AtomicInteger()
        vm.send("me@gmail.test", "gmail") { sent.incrementAndGet() }
        until("sent") { sent.get() == 1 && !vm.state.value.sending }
        assertEquals("requested", vm.state.value.beta?.status)
        refuse = true
        vm.send("me@gmail.test", "gmail") { sent.incrementAndGet() }
        until("refused") { vm.state.value.error.isNotEmpty() && !vm.state.value.sending }
        assertEquals("too many requests", vm.state.value.error)
        assertEquals(1, sent.get())
    }

    @Test fun aConnectorsPageReadsThePermissionItWasGivenAndDisconnects() {
        val s = fake { m, p, _ ->
            when ("$m $p") {
                "GET /api/oauth/providers" -> ok("""[{"id":"github","connected":true,"granted_scope":"repo","scopes":[{"id":"repo","label":"Repos","detail":"read and write"}]}]""")
                "DELETE /api/credentials/github" -> ok("{}")
                else -> null
            }
        }
        val vm = ConnectorDetailViewModel(s.sessions()).tracked()
        vm.loadGranted("github")
        until("scope") { vm.state.value.granted != null }
        assertEquals("Repos", vm.state.value.granted?.label)
        val changed = AtomicInteger()
        vm.disconnect("github") { changed.incrementAndGet() }
        until("disconnected") { changed.get() == 1 }
        assertNotNull(s.saw("DELETE /api/credentials/github"))
    }
}
