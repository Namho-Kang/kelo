package chat.kelo.app.core.e2e

import chat.kelo.app.chat.TurnEvent
import chat.kelo.app.chat.TurnPoll
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.PairingClient
import chat.kelo.app.core.PairingLinks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The app's own HTTP stack against the real Kelo server, through a proxy that records every byte (apps/android/tools/ke2e_local_test.sh
 * starts both and checks the record afterwards: no token, no text, no path in the clear). Skipped unless KELO_E2E_SERVER_INFO names
 * the server's info file, so a normal `./gradlew test` never needs a server.
 */
class Ke2eLocalServerTest {
    private val info: JsonObject? = System.getenv("KELO_E2E_SERVER_INFO")?.let { File(it) }?.takeIf { it.isFile }
        ?.let { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun apiFor(url: String, token: String, pin: KeyPin) = KeloApi(
        url, token, KeloApi.defaultClient.newBuilder().addInterceptor(Ke2eInterceptor(url, { pin })).build(), keyPin = pin,
    )

    @Test fun pairsAndTalksOnlyThroughE2e() = runBlocking {
        assumeTrue("KELO_E2E_SERVER_INFO is not set (run apps/android/tools/ke2e_local_test.sh)", info != null)
        val url = info!!["url"]!!.jsonPrimitive.content
        val owner = info["owner_token"]!!.jsonPrimitive.content
        val secretReply = info["secret_reply"]!!.jsonPrimitive.content

        // the owner's side asks for a pairing link (its key fetched trust-on-first-use, as for a typed key)
        val tofu = KeyPin(E2eInfoCalls.fetch(KeloApi.defaultClient, url) ?: error("no /api/e2e/info"), verified = false)
        val pairing = apiFor(url, owner, tofu).post<JsonObject>("/api/devices/pair", buildJsonObject { put("origin", url) })
        val link = PairingLinks.parse(pairing["link"]!!.jsonPrimitive.content) ?: error("not a pairing link")
        assertEquals(tofu.key.base64Url, link.serverKey)

        // the phone pairs with the link: the key comes from the QR (verified) and the pairing call itself is sealed
        val session = PairingClient(KeloApi.defaultClient).pair(link, "Pixel KE2E")
        val pin = session.pin ?: error("the session has no key")
        assertTrue(pin.verified)
        val api = apiFor(url, session.token, pin)

        api.get<JsonObject>("/api/profile")
        api.post<JsonElement>("/api/goals", buildJsonObject { put("title", "kelo-e2e-goal-7f3a") })

        // a file larger than one frame goes up in envelope 0x02 and comes back the same
        val file = ("%PDF-1.4\n% kelo-e2e-file-c4e2\n" + "x".repeat(200_000)).toByteArray()
        val stored = api.uploadStream("ke2e.pdf", file.size.toLong(), "application/pdf", { ByteArrayInputStream(file) })
        assertTrue(stored.isNotEmpty())
        val back = ByteArrayOutputStream().also { api.downloadTo("/api/uploads/ke2e.pdf/file", it) }.toByteArray()
        assertArrayEquals(file, back)

        // a reply, followed with short requests as the chat does
        val id = api.post<JsonObject>("/api/turns", buildJsonObject { put("text", "kelo-e2e-talk-91c2") })["id"]!!.jsonPrimitive.content
        val text = StringBuilder()
        var after = 0
        for (i in 0 until 40) {
            val poll = TurnPoll.parse(api.get<JsonElement>("/api/turns/${KeloApi.segment(id)}?after=$after&wait=5"))
            poll.events.filterIsInstance<TurnEvent.Text>().forEach { text.append(it.text) }
            after = poll.next
            if (poll.done) break
        }
        assertTrue(text.toString(), text.contains(secretReply))
    }
}
