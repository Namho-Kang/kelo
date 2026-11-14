package chat.kelo.desktop

import chat.kelo.app.core.AppVersion
import chat.kelo.app.core.PairingLinks
import chat.kelo.app.core.Profile
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.app.start.KeySignIn
import chat.kelo.desktop.core.LinkVerify
import chat.kelo.desktop.core.StoreKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The desktop app's own wiring (AppContainer: version header, Sessions with KE2E, PairingClient with the store's pins, "verify with a
 * link") against the real Kelo server behind a recording proxy (apps/desktop/tools/ke2e_local_test.sh starts both and checks the record:
 * nothing but ciphertext crossed). Skipped unless KELO_E2E_SERVER_INFO names the server's info file.
 */
class DesktopKe2eLocalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val info: JsonObject? = System.getenv("KELO_E2E_SERVER_INFO")?.let { File(it) }?.takeIf { it.isFile }
        ?.let { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun app(): AppContainer {
        val dir = tmp.newFolder()
        return AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir))   // KE2E required
    }

    @Test fun signsInPairsVerifiesAndTalksOnlyThroughE2e() = runBlocking {
        assumeTrue("KELO_E2E_SERVER_INFO is not set (run apps/desktop/tools/ke2e_local_test.sh)", info != null)
        val url = info!!["url"]!!.jsonPrimitive.content
        val owner = info["owner_token"]!!.jsonPrimitive.content

        // the owner's computer signs in with the access key typed in: the key is pinned trust-on-first-use (not verified)
        val first = app()
        val (session, profile) = KeySignIn.connect(first.pairing, url, owner, "Desktop KE2E")
        assertNotNull(profile)
        first.sessions.signIn(session)
        val pin = first.sessions.pin ?: error("no key pinned")
        assertFalse(pin.verified)
        first.sessions.requireApi().get<Profile>("/api/profile")
        assertFalse("the server's version is read inside KE2E", AppVersion.serverTooOld(AppVersion.server.value))
        assertNotNull(AppVersion.server.value?.version)

        // it makes a link for another computer; pasting that link on My → My devices verifies its own key
        val pairing = first.sessions.requireApi().post<JsonObject>("/api/devices/pair", buildJsonObject { put("origin", url) })
        val linkText = pairing["link"]!!.jsonPrimitive.content
        assertEquals(LinkVerify.Result.DONE, LinkVerify.check(linkText, first.sessions::verifyWith))
        assertTrue(first.sessions.pin!!.verified)

        // the second computer pairs with the link: the key from the link (verified), the pairing call itself sealed
        val second = app()
        val link = PairingLinks.parse(linkText) ?: error("not a pairing link")
        val joined = second.pairing.pair(link, "Desktop KE2E 2")
        second.sessions.signIn(joined)
        assertTrue(second.sessions.pin!!.verified)
        second.sessions.requireApi().post<JsonElement>("/api/goals", buildJsonObject { put("title", "kelo-e2e-goal-7f3a") })
        assertFalse(second.sessions.keyChanged.value)
        assertFalse(second.sessions.serverOld.value)
    }
}
