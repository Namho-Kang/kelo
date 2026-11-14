package chat.kelo.app.chat.vault

import chat.kelo.app.my.MyFakeServer
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The secure entries' view models against a local fake server: the calls that save a key, a login or forget a request. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretEntryViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }

    @After fun stop() { vms.clear(); server?.shutdown(); Dispatchers.resetMain() }

    private fun serve(answer: (String, String, String) -> okhttp3.mockwebserver.MockResponse?) = MyFakeServer(answer).also { server = it }

    @Test fun aKeyIsSavedTrimmedAndTheOutcomeIsHandedOn() {
        val s = serve { m, p, _ -> if (m == "PUT" && p == "/api/credentials/openai/secret") ok("""{"ok":true,"checked":true}""") else null }
        val vm = vms.make { SecretEntryViewModel(s.sessions()) }
        var outcome: SecretOutcome? = null
        vm.saveKey("openai", "  sk-123456789  ") { outcome = it }
        until("the outcome") { outcome != null }
        val saved = outcome as SecretOutcome.Saved
        assertEquals("ok", saved.result.checked)
        assertFalse(vm.state.value.busy)
        assertTrue(s.saw("PUT /api/credentials/openai/secret"))
    }

    @Test fun aRefusedKeyShowsTheServersWordsAndAGoneEntryFolds() {
        val s = serve { _, p, _ -> if (p.endsWith("/gone/secret")) fail(404, "gone") else fail(400, "too short") }
        val vm = vms.make { SecretEntryViewModel(s.sessions()) }
        var outcome: SecretOutcome? = null
        vm.saveKey("openai", "x") { outcome = it }
        until("the refusal") { outcome != null }
        assertEquals("too short", (outcome as SecretOutcome.Failed).problem.detail)
        assertEquals("too short", vm.state.value.problem?.detail)
        outcome = null
        vm.saveKey("gone", "x") { outcome = it }
        until("gone") { outcome != null }
        assertEquals(SecretOutcome.Gone, outcome)
    }

    @Test fun theSecureFormSavesALoginAndReportsAFailure() {
        val s = serve { m, p, _ -> if (m == "PUT" && p == "/api/vault/shop/login") ok("""{}""") else fail(500, "broke") }
        val vm = vms.make { SecureFormViewModel(s.sessions()) }
        val login = VaultItem(name = "shop", kind = "login")
        var saved = false
        vm.submit(login, " me ", "pw", VaultMode.NOW) { saved = true }
        until("saved") { saved }
        assertEquals("", vm.state.value.error)
        val key = VaultItem(name = "k", kind = "api")
        vm.submit(key, "", "12345678", VaultMode.NOW) { saved = false }
        until("the error") { vm.state.value.error.isNotEmpty() }
        assertEquals("broke", vm.state.value.error)
    }

    @Test fun theSecureCardLoadsItsItemAndForgetsTheRequest() {
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/vault" -> ok("""{"items":[{"name":"shop","label":"Shop","kind":"login","hosts":["shop.example"]}]}""")
                m == "DELETE" && p == "/api/vault/shop/draft" -> ok("""{}""")
                else -> null
            }
        }
        val vm = vms.make { SecureCardViewModel(s.sessions(), "shop") }
        until("the item") { vm.state.value.item != null }
        assertNotNull(vm.state.value.item)
        var done = false
        vm.cancel { done = true }
        until("cancelled") { done }
        assertTrue(s.saw("DELETE /api/vault/shop/draft"))
    }
}
