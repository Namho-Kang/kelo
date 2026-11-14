package chat.kelo.app.my

import chat.kelo.app.chat.ModelChoice
import chat.kelo.app.chat.ProviderInfo
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import chat.kelo.app.platform.FilePicker
import chat.kelo.app.platform.LocationAccess
import chat.kelo.app.platform.PhoneLocation
import chat.kelo.app.platform.PickedFile
import chat.kelo.app.platform.PickedImage
import chat.kelo.app.platform.SaveTarget
import chat.kelo.app.platform.SettingsPage
import chat.kelo.app.platform.SystemSettings
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The My pages' view models against a local fake server: one happy path and one failure for each, with the state they expose and the
 * calls they make. Nothing here reaches a real server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After fun stop() { server?.shutdown(); vms.clear(); Dispatchers.resetMain() }

    private fun serve(answer: (String, String, String) -> okhttp3.mockwebserver.MockResponse?): MyFakeServer = MyFakeServer(answer).also { server = it }

    private val texts = ModelTexts(listFailed = "list failed", installed = "installed", chooseModel = "choose a model", urlProblems = emptyMap())

    // ---- model

    @Test fun theModelPageSavesTheChoiceAndHandsBackTheProfile() {
        val s = serve { m, p, _ ->
            when {
                m == "PUT" && p == "/api/profile" -> ok("""{"provider":"anthropic","model":"opus"}""")
                m == "GET" && p.startsWith("/api/providers") -> ok("[]")
                else -> null
            }
        }
        val vm = vms.make { ModelSettingsViewModel(s.sessions(), Profile(provider = "anthropic", model = "opus"), texts) }
        var saved: Profile? = null
        vm.save { saved = it }
        until("the saved profile") { saved != null }
        assertEquals("opus", saved!!.model)
        assertTrue(s.saw("PUT /api/profile"))
        assertFalse(vm.state.value.saving)
    }

    @Test fun aRefusedModelSaveKeepsThePageOpenWithTheReason() {
        val s = serve { m, p, _ -> if (m == "PUT" && p == "/api/profile") fail(400, "no such model") else if (p.startsWith("/api/providers")) ok("[]") else null }
        val vm = vms.make { ModelSettingsViewModel(s.sessions(), Profile(provider = "anthropic", model = "x"), texts) }
        var saved = false
        vm.save { saved = true }
        until("the error") { vm.state.value.error.isNotEmpty() }
        assertEquals("no such model", vm.state.value.error)
        assertFalse(saved)
    }

    @Test fun aBackgroundProviderWithoutAModelIsNotSent() {
        val s = serve { _, p, _ -> if (p.startsWith("/api/providers")) ok("[]") else null }
        val vm = vms.make { ModelSettingsViewModel(s.sessions(), Profile(), texts) }
        vm.pickBackground(ModelChoice("openai", ""))
        vm.save {}
        assertEquals("choose a model", vm.state.value.error)
        assertFalse(s.saw("PUT /api/profile"))
    }

    @Test fun addingAnEndpointSendsNameAndBaseUrl() {
        var postBody: String? = null
        val s = serve { m, p, b ->
            when {
                m == "POST" && p == "/api/providers/custom-endpoints" -> {
                    postBody = b
                    ok("""{"id":"custom-home-ollama","name":"Home Ollama","base_url":"http://192.168.0.10:11434/v1","transport":"chat_completions"}""")
                }
                m == "GET" && p.startsWith("/api/providers") -> ok("""[{"name":"custom-home-ollama","display_name":"Home Ollama","user_endpoint":true}]""")
                m == "GET" && p.startsWith("/api/models") -> ok("""{"provider":"custom-home-ollama","models":["llama3"]}""")
                else -> null
            }
        }
        val vm = vms.make { ModelSettingsViewModel(s.sessions(), Profile(), texts) }
        vm.addEndpoint("Home Ollama", "http://192.168.0.10:11434/v1")
        until("endpoint added") { postBody != null }
        assertTrue(postBody!!.contains("Home Ollama"))
        assertTrue(postBody!!.contains("http://192.168.0.10:11434/v1"))
        until("selected new provider") { vm.state.value.provider == "custom-home-ollama" }
    }

    @Test fun deletingAnInUseEndpointShowsTheInUseMessage() {
        val s = serve { m, p, _ ->
            when {
                m == "DELETE" && p == "/api/providers/custom-endpoints/custom-home-ollama" ->
                    okhttp3.mockwebserver.MockResponse().setResponseCode(409).setBody("""{"code":"in_use","detail":{"code":"in_use"}}""")
                m == "GET" && p.startsWith("/api/providers") -> ok("[]")
                else -> null
            }
        }
        val t = ModelTexts(
            listFailed = "list failed", installed = "installed", chooseModel = "choose a model",
            urlProblems = emptyMap(), endpointInUse = "This is the endpoint of the model in use.",
        )
        val vm = vms.make { ModelSettingsViewModel(s.sessions(), Profile(provider = "custom-home-ollama"), t) }
        vm.deleteEndpoint("custom-home-ollama")
        until("in use message") { vm.state.value.endpointMessage.isNotEmpty() }
        assertEquals("This is the endpoint of the model in use.", vm.state.value.endpointMessage)
    }

    @Test fun providerInfoDecodesUserEndpoint() {
        val json = """{"name":"custom-ollama","display_name":"Ollama","user_endpoint":true}"""
        val info = KeloApi.json.decodeFromString<ProviderInfo>(json)
        assertTrue(info.userEndpoint)
        val defaultInfo = KeloApi.json.decodeFromString<ProviderInfo>("""{"name":"anthropic"}""")
        assertFalse(defaultInfo.userEndpoint)
    }

    // ---- notifications and briefing

    @Test fun theNotifyPageLoadsItsBlocksAndTellsAMissingOneApart() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/proactive" -> ok("""{"enabled":true,"daily_total":4}""")
                m == "GET" && p == "/api/checklist" -> fail(404, "gone")
                else -> null
            }
        }
        val vm = vms.make { NotifySettingsViewModel(s.sessions()) }
        until("the blocks") { vm.state.value.proactive != null && vm.state.value.checklistMissing }
        assertEquals(4, vm.state.value.proactive!!.dailyTotal)
        assertNull(vm.state.value.checklist)
    }

    @Test fun aRefusedNotifyChangeIsAToastNotAnErrorBlock() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/proactive" -> ok("""{"enabled":false}""")
                m == "PUT" && p == "/api/proactive" -> fail(400, "too many")
                else -> null
            }
        }
        val vm = vms.make { NotifySettingsViewModel(s.sessions()) }
        until("loaded") { vm.state.value.proactive != null }
        vm.putProactive(buildJsonObject { put("enabled", true) })
        assertEquals("too many", runBlocking { vm.notices.first() })
        assertFalse(vm.state.value.proactive!!.enabled)
    }

    @Test fun theBriefingLoadsAndAnOlderServerWithoutOneIsMarkedMissing() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/briefing" -> ok("""{"enabled":true,"time_mode":"fixed"}""")
                m == "GET" && p == "/api/briefing/sources" -> ok("""{"calendar":{"accounts":[{"id":"a","name":"Cal","on":true}]}}""")
                else -> null
            }
        }
        val vm = vms.make { BriefingSettingsViewModel(s.sessions(), null, SystemSettings.None) }
        until("settings and sources") { vm.state.value.settings != null && vm.state.value.sources != null }
        assertEquals("fixed", vm.state.value.settings!!.timeMode)
        assertTrue(vm.state.value.granted)   // a device without a location permission never asks

        val old = serve { _, _, _ -> null }
        val vm2 = vms.make { BriefingSettingsViewModel(old.sessions(), null, SystemSettings.None) }
        until("missing") { vm2.state.value.missing }
    }

    @Test fun aRefusedSourceSwitchIsAToastAndTheSwitchIsShownAtOnce() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/briefing/sources" -> ok("""{"mail":{"accounts":[{"id":"m1","on":true}]}}""")
                m == "PUT" && p == "/api/briefing/sources" -> fail(500, "down")
                else -> null
            }
        }
        val vm = vms.make { BriefingSettingsViewModel(s.sessions(), null, SystemSettings.None) }
        until("sources") { vm.state.value.sources != null }
        vm.flipSource("mail", "m1", false)
        assertEquals("down", runBlocking { vm.notices.first() })
    }

    @Test fun briefingLocationServicesOffOpensLocationSettings() {
        val s = serve { _, _, _ -> null }
        val settings = object : SystemSettings {
            var opened: SettingsPage? = null
            override fun canOpen(page: SettingsPage) = true
            override fun open(page: SettingsPage) { opened = page }
        }
        val loc = object : PhoneLocation {
            override fun servicesOn() = false
            override fun granted() = false
            override fun blocked() = false
            override suspend fun request() = LocationAccess.DENIED
            override suspend fun send(api: chat.kelo.app.core.KeloApi, force: Boolean) {}
            override suspend fun read(): kotlinx.serialization.json.JsonObject? = null
        }
        val vm = vms.make { BriefingSettingsViewModel(s.sessions(), loc, settings) }
        vm.allowPlace()
        assertEquals(SettingsPage.LOCATION, settings.opened)
        assertFalse(vm.state.value.servicesOn)
    }

    // ---- operator

    @Test fun theOperatorPageLoadsItsCards() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/operator/firebase" -> ok("""{"enabled":true,"project_id":"p1"}""")
                m == "GET" && p == "/api/operator/cloudflare" -> ok("""{"zone_name":"kelo.example","max_names":50}""")
                m == "GET" && p == "/api/operator/beta" -> ok("""{"open":2}""")
                m == "GET" && p.startsWith("/api/operator/reports") -> ok("""{"open":1,"receiving":true}""")
                else -> null
            }
        }
        val vm = vms.make { OperatorViewModel(s.sessions(), NoFiles) }
        until("all cards") { val u = vm.state.value; u.firebase.config != null && u.cloudflare.config != null && u.beta.data != null && u.reports.data != null }
        assertEquals("p1", vm.state.value.firebase.config!!.projectId)
        assertEquals("kelo.example", vm.state.value.cloudflare.form.zoneName)
    }

    @Test fun aBadCloudflareFormIsRefusedBeforeAnythingIsSent() {
        val s = serve { m, p, _ -> if (m == "GET" && p == "/api/operator/cloudflare") ok("""{}""") else null }
        val vm = vms.make { OperatorViewModel(s.sessions(), NoFiles) }
        until("cloudflare") { vm.state.value.cloudflare.config != null }
        vm.editCloudflare(vm.state.value.cloudflare.form.copy(accountId = "not-hex"))
        vm.saveCloudflare()
        assertTrue(vm.state.value.cloudflare.problem is OperatorProblem.App)
        assertFalse(s.saw("PUT /api/operator/cloudflare"))
    }

    @Test fun aServerRefusalOnTheCloudflareSaveShowsItsOwnWords() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/operator/cloudflare" -> ok("""{}""")
                m == "PUT" && p == "/api/operator/cloudflare" -> fail(400, "bad token")
                else -> null
            }
        }
        val vm = vms.make { OperatorViewModel(s.sessions(), NoFiles) }
        until("cloudflare") { vm.state.value.cloudflare.config != null }
        vm.saveCloudflare()
        until("the refusal") { vm.state.value.cloudflare.problem != null && !vm.state.value.cloudflare.busy }
        assertEquals("bad token", (vm.state.value.cloudflare.problem as OperatorProblem.Server).text)
        assertFalse(vm.state.value.cloudflare.busy)
    }

    // ---- vault

    @Test fun theVaultLoadsAndTheLockSwitches() {
        var locked = false
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/vault" -> ok("""{"locked":$locked,"items":[]}""")
                m == "PUT" && p == "/api/vault/lock" -> { locked = true; ok("{}") }
                m == "GET" && p == "/api/providers" -> ok("[]")
                m == "GET" && p == "/api/secret-requests" -> ok("""{"items":[]}""")
                else -> null
            }
        }
        val vm = vms.make { VaultViewModel(s.sessions()) }
        until("the vault") { vm.state.value.info != null }
        assertFalse(vm.state.value.info!!.locked)
        vm.toggleLock()
        until("locked") { vm.state.value.info?.locked == true }
    }

    @Test fun aRefusedNewLoginShowsTheReasonOnItsPage() {
        val s = serve { m, p, _ ->
            when {
                m == "POST" && p == "/api/vault/logins" -> fail(400, "not a site")
                m == "GET" && p == "/api/vault" -> ok("""{"items":[]}""")
                else -> null
            }
        }
        val vm = vms.make { VaultViewModel(s.sessions()) }
        var made: String? = null
        vm.addLogin("x", "y") { made = it }
        until("the reason") { vm.state.value.addLoginError.isNotEmpty() }
        assertEquals("not a site", vm.state.value.addLoginError)
        assertNull(made)
        vm.clearMessages()
        assertEquals("", vm.state.value.addLoginError)
    }

    // ---- devices

    @Test fun theDevicesPageStartsAPairingWithItsLink() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/devices" -> ok("""{"devices":[{"id":1,"name":"Pixel"}],"current":1}""")
                m == "POST" && p == "/api/devices/pair" -> ok("""{"code":"c","expires_in":600,"link":"https://example.org/#pair=c"}""")
                else -> null
            }
        }
        val vm = vms.make { DevicesViewModel(s.sessions()) }
        until("devices") { vm.state.value.devices.size == 1 }
        vm.add()
        until("pairing") { vm.state.value.pairing != null }
        assertEquals("https://example.org/#pair=c", vm.state.value.pairing!!.link)
        vm.closePairing()
        assertNull(vm.state.value.pairing)
    }

    @Test fun aPairingWithoutALinkAndARefusedOneAreToldApart() {
        var body = """{"code":"c","expires_in":600}"""
        var refuse = false
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/devices" -> ok("""{"devices":[]}""")
                m == "POST" && p == "/api/devices/pair" -> if (refuse) fail(403, "owner only") else ok(body)
                else -> null
            }
        }
        val vm = vms.make { DevicesViewModel(s.sessions()) }
        vm.add()
        until("no link") { vm.state.value.noLinkError }
        refuse = true
        vm.add()
        until("refused") { vm.state.value.error == "owner only" }
        assertFalse(vm.state.value.noLinkError)
    }

    // ---- documents

    private object NoFiles : FilePicker {
        override suspend fun pickImage(maxBytes: Long): PickedImage? = null
        override suspend fun pickFile(types: List<String>): PickedFile? = null
        override suspend fun saveFile(name: String, type: String): SaveTarget? = null
    }

    private class OneFile(private val file: PickedFile?) : FilePicker {
        override suspend fun pickImage(maxBytes: Long): PickedImage? = null
        override suspend fun pickFile(types: List<String>): PickedFile? = file
        override suspend fun saveFile(name: String, type: String): SaveTarget? = null
    }

    @Test fun aPickedFileGoesUpAndTheListIsLoadedAgain() {
        var uploaded = false
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/uploads" -> ok(if (uploaded) """[{"path":"uploads/a.pdf","bytes":3}]""" else "[]")
                m == "PUT" && p.startsWith("/api/uploads/") -> { uploaded = true; ok("""{"path":"uploads/a.pdf"}""") }
                else -> null
            }
        }
        val vm = vms.make { DocumentsViewModel(s.sessions(), OneFile(PickedFile("a.pdf", 3, "application/pdf") { "abc".byteInputStream() })) }
        until("empty list") { vm.state.value.docs?.isEmpty() == true }
        vm.upload(listOf("application/pdf"))
        until("uploaded") { vm.state.value.note == DocsNote.Uploaded && vm.state.value.docs?.size == 1 }
        assertFalse(vm.state.value.uploading)
    }

    @Test fun aFileOverWhatAnOldServerTakesIsNeverSent() {
        val s = serve { m, p, _ -> if (m == "GET" && p == "/api/uploads") ok("[]") else null }
        val vm = vms.make { DocumentsViewModel(s.sessions(), OneFile(PickedFile("big.mov", 99_000_001L, "video/quicktime") { "x".byteInputStream() })) }
        until("list") { vm.state.value.docs != null }
        vm.upload(listOf("video/*"))
        until("the note") { vm.state.value.note == DocsNote.TooLarge(99_000_000L) }
        assertFalse(s.requests.any { it.startsWith("PUT") })
        assertNotNull(vm.state.value.docs)
    }
}
