package chat.kelo.app.core.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The vectors every platform must pass: docs/security/e2e-vectors.json (layout: docs/security/e2e-vectors.md; made by the server's
 * tests/e2e_vectors_gen.py). `-Dkelo.e2e.vectors=/path` points elsewhere. Skipped when the file is not there (an Android checkout
 * without docs/, say), so it never fails for a missing file; it fails for any value that does not match.
 */
class Ke2eVectorsFileTest {
    /** The repository's copy, looked for upwards from the working directory (apps/desktop/ or apps/android/kelo/app/), or the property. */
    private val file: File = System.getProperty("kelo.e2e.vectors")?.let(::File)
        ?: generateSequence(File("").absoluteFile) { it.parentFile }.take(6)
            .map { File(it, "docs/security/e2e-vectors.json") }.firstOrNull { it.isFile }
        ?: File("docs/security/e2e-vectors.json")
    private lateinit var v: JsonObject

    @Before fun load() {
        assumeTrue("docs/security/e2e-vectors.json not found", file.isFile)
        v = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("vectors file version", 1, v["version"]!!.jsonPrimitive.int)
    }

    private fun JsonObject.s(k: String): String = this[k]!!.jsonPrimitive.content
    private fun JsonObject.h(k: String): ByteArray = hex(s(k))
    private fun JsonObject.arr(k: String): List<JsonObject> = this[k]!!.jsonArray.map { it.jsonObject }
    private fun server() = v["server"]!!.jsonObject
    private fun serverKeys() = server().let { X25519KeyPair(it.h("sk"), it.h("pk")) }
    private fun request(name: String) = v.arr("requests").first { it.s("name") == name }

    private fun expect(kind: Ke2eException.Kind, what: String, block: () -> Unit) {
        try {
            block()
            fail("$what: expected $kind")
        } catch (e: Ke2eException) {
            assertEquals("$what (${e.message})", kind, e.kind)
        }
    }

    @Test fun labelsAreOurs() {
        val l = v["labels"]!!.jsonObject
        assertEquals(Ke2e.INFO_PREFIX, l.s("info_prefix"))
        assertEquals(Ke2e.AAD_REQUEST, l.s("aad_request"))
        assertEquals(Ke2e.EXPORT_RESPONSE, l.s("response"))
        assertEquals(Ke2e.AAD_RESPONSE, l.s("response"))
        assertEquals(Ke2e.EXPORT_WS_C2S, l.s("ws_c2s"))
        assertEquals(Ke2e.EXPORT_WS_S2C, l.s("ws_s2c"))
        assertEquals(Ke2e.AAD_WS, l.s("aad_ws"))
        assertEquals(Ke2e.VERSION.toInt(), l["envelope_version"]!!.jsonPrimitive.int)
        assertEquals(Ke2e.BODY_FRAME_MAX, l["max_frame_plaintext"]!!.jsonPrimitive.int)
        val suite = v["suite"]!!.jsonObject
        assertEquals(listOf(0, 32, 1, 3), listOf("mode", "kem_id", "kdf_id", "aead_id").map { suite[it]!!.jsonPrimitive.int })
    }

    @Test fun serverKeyAndWhatComesFromIt() {
        val s = server()
        val derived = X25519KeyPair.derive(s.h("ikm"))
        assertEquals(s.s("pk"), hexOf(derived.publicKey))
        assertEquals(s.s("pk"), hexOf(X25519KeyPair.fromPrivate(s.h("sk")).publicKey))
        val key = ServerKey(s.h("pk"))
        assertEquals(s.s("kid"), key.kid)
        assertEquals(s.s("pk_b64url"), key.base64Url)
        assertEquals(s.s("fingerprint"), key.fingerprint)
        assertEquals(s.s("info"), hexOf(Ke2e.info(key.kid)))
        assertEquals(s.s("info_text"), Ke2e.info(key.kid).toString(Charsets.US_ASCII))
        val info = s["e2e_info"]!!.jsonObject
        assertEquals(key, ServerKey.fromInfo(info["v"]!!.jsonPrimitive.int, info.s("pk"), info.s("kid"), info.s("fingerprint")))
        assertEquals(key.base64Url, chat.kelo.app.core.PairingLinks.parse(s.s("pair_link"))?.serverKey)
    }

    @Test fun requestsSealAndOpenExactly() {
        val pk = ServerKey(server().h("pk"))
        for (r in v.arr("requests")) {
            val name = r.s("name")
            // our encoder writes the same head bytes (compact, the spec's key order)
            val head = RequestHead.parse(r.s("head_json").toByteArray())
            assertEquals(name, r.s("head_json"), head.toJsonBytes().toString(Charsets.UTF_8))
            val inner = Ke2eRequest.inner(head.toJsonBytes(), r.s("body_text").toByteArray())
            assertEquals("$name inner", r.s("inner"), hexOf(inner))
            assertEquals("$name aad", r.s("aad"), hexOf(Ke2e.ascii(Ke2e.AAD_REQUEST)))

            // sending side with the fixed ephemeral key
            val eph = X25519KeyPair.fromPrivate(r.h("sk_e"))
            assertEquals("$name enc = pkE", r.s("enc"), hexOf(eph.publicKey))
            assertEquals("$name ikm_e", r.s("enc"), hexOf(X25519KeyPair.derive(r.h("ikm_e")).publicKey))
            val sealed = Ke2eRequest.seal(pk, head, r.s("body_text").toByteArray(), eph)
            assertEquals("$name enc", r.s("enc"), hexOf(sealed.context.enc))
            assertEquals("$name ct", r.s("ct"), hexOf(sealed.outerBody.copyOfRange(1 + 8 + 32, sealed.outerBody.size)))
            assertEquals("$name envelope", r.s("envelope"), hexOf(sealed.outerBody))
            assertEquals("$name response_key", r.s("response_key"), hexOf(sealed.context.responseKey()))
            assertEquals("$name ws c2s", r.s("ws_c2s_key"), hexOf(sealed.context.wsKeys().first))
            assertEquals("$name ws s2c", r.s("ws_s2c_key"), hexOf(sealed.context.wsKeys().second))

            // receiving side
            val opened = Ke2eRequest.open(r.h("envelope"), serverKeys())
            assertEquals(head, opened.head)
            assertEquals(r.s("body_text"), opened.body.toString(Charsets.UTF_8))
            assertEquals("$name response_key (receiver)", r.s("response_key"), hexOf(opened.context.responseKey()))
            assertEquals("$name ws c2s (receiver)", r.s("ws_c2s_key"), hexOf(opened.context.wsKeys().first))
        }
    }

    @Test fun responsesReadAndWriteExactly() {
        for (r in v.arr("responses")) {
            val name = r.s("name")
            val key = r.h("key")
            assertEquals("$name key is its request's", request(r.s("request")).s("response_key"), r.s("key"))
            val frames = r.arr("frames")
            frames.forEachIndexed { i, f ->
                assertEquals("$name nonce $i", f.s("nonce"), hexOf(FrameCipher.nonce(f["counter"]!!.jsonPrimitive.int.toLong())))
                assertEquals("$name aad $i", f.s("aad"), hexOf(Ke2e.ascii(Ke2e.AAD_RESPONSE) + f["flag"]!!.jsonPrimitive.int.toByte()))
            }
            assertEquals("$name stream", r.s("stream"), frames.joinToString("") { it.s("frame") })

            // reading: frame 0 is the head, then each frame's plaintext in order, and nothing after the last
            val reader = ResponseReader(ByteArrayInputStream(r.h("stream")), key)
            assertEquals(ResponseHead.parse(frames[0].h("pt")), reader.head())
            for (f in frames.drop(1)) assertArrayEquals("$name frame ${f.s("counter")}", f.h("pt"), reader.nextFrame())
            assertNull(reader.nextFrame())
            // the body as one stream
            val again = ResponseReader(ByteArrayInputStream(r.h("stream")), key)
            again.head()
            assertEquals(frames.drop(1).joinToString("") { it.s("pt") }, hexOf(again.body.readBytes()))

            // writing the same plaintexts gives the same bytes (deterministic AEAD)
            val out = ByteArrayOutputStream()
            val w = ResponseWriter(out, key)
            frames.forEach { w.frame(it.h("pt"), last = it["flag"]!!.jsonPrimitive.int == 1) }
            assertEquals("$name written", r.s("stream"), hexOf(out.toByteArray()))
        }
    }

    @Test fun webSocketFirstMessages() {
        val ws = v["websocket"]!!.jsonObject
        assertEquals(ws.s("aad"), hexOf(Ke2e.ascii(Ke2e.AAD_WS)))
        val r = request(ws.s("request"))
        val c2s = ws["c2s_first"]!!.jsonObject
        val s2c = ws["s2c_first"]!!.jsonObject
        val app = WsChannel(sendKey = r.h("ws_c2s_key"), receiveKey = r.h("ws_s2c_key"))
        assertEquals(c2s.s("message"), hexOf(app.seal(c2s.h("pt"))))
        assertArrayEquals(s2c.h("pt"), app.open(s2c.h("message")))
        val srv = WsChannel(sendKey = r.h("ws_s2c_key"), receiveKey = r.h("ws_c2s_key"))
        assertArrayEquals(c2s.h("pt"), srv.open(c2s.h("message")))
        assertEquals(s2c.s("message"), hexOf(srv.seal(s2c.h("pt"))))
    }

    @Test fun streamedRequestsSealAndOpenExactly() {
        val pk = ServerKey(server().h("pk"))
        val list = v["stream_requests"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        assumeTrue("no stream_requests in this vectors file", list.isNotEmpty())
        for (r in list) {
            val name = r.s("name")
            val req = Ke2eRequest.sealStreamed(pk, RequestHead.parse(r.s("head_json").toByteArray()), X25519KeyPair.fromPrivate(r.h("sk_e")))
            assertEquals("$name head", r.s("head"), hexOf(req.prefix))
            assertEquals("$name body key", r.s("body_key"), hexOf(req.context.requestBodyKey()))
            assertEquals("$name response key", r.s("response_key"), hexOf(req.context.responseKey()))
            // the vector cuts the body into the frames it lists; write the same pieces
            val out = ByteArrayOutputStream().also { it.write(req.prefix) }
            val w = req.bodyWriter(out)
            val frames = r.arr("body_frames")
            frames.forEachIndexed { i, f -> w.frame(f.h("pt"), last = i == frames.lastIndex) }
            assertEquals("$name envelope", r.s("envelope"), hexOf(out.toByteArray()))
            val (opened, reader) = Ke2eRequest.openStreamed(ByteArrayInputStream(r.h("envelope")), serverKeys())
            assertEquals("$name method", "PUT", opened.head.method)
            assertEquals("$name body", r.s("body_text"), reader.readAll().toString(Charsets.UTF_8))
        }
    }

    @Test fun webSocketMessagesCarryTheirTypeByte() {
        val ws = v["websocket"]!!.jsonObject
        val r = request(ws.s("request"))
        val srv = WsChannel(sendKey = r.h("ws_s2c_key"), receiveKey = r.h("ws_c2s_key"))
        val c2s = ws["c2s_first"]!!.jsonObject
        assertEquals(c2s.s("payload_text"), (srv.openMessage(c2s.h("message")) as WsMessage.Text).text)
        val app = WsChannel(sendKey = r.h("ws_c2s_key"), receiveKey = r.h("ws_s2c_key"))
        assertEquals(c2s.s("message"), hexOf(app.sealText(c2s.s("payload_text"))))
    }

    @Test fun negativeCases() {
        var seen = 0
        for (n in v.arr("negative")) {
            val name = n.s("name")
            val kind = when (n.s("expect")) {
                "bad_frame" -> Ke2eException.Kind.BAD_FRAME
                "truncated" -> Ke2eException.Kind.TRUNCATED
                "unknown_kid" -> Ke2eException.Kind.KEY_CHANGED
                "bad_envelope" -> null   // MALFORMED (bad version, too short) or BAD_FRAME (does not open)
                else -> continue          // replayed / stale_timestamp: the server's clock and nonce cache, not the app's
            }
            when (n.s("kind")) {
                "response" -> expect(kind!!, name) {
                    val r = ResponseReader(ByteArrayInputStream(n.h("stream")), n.h("key"))
                    r.head()
                    while (r.nextFrame() != null) Unit
                }
                "ws_c2s" -> expect(kind!!, name) { WsChannel(sendKey = ByteArray(32), receiveKey = n.h("key")).openMessage(n.h("message")) }
                "request" -> {
                    if (kind != null) {
                        expect(kind, name) { Ke2eRequest.open(n.h("envelope"), serverKeys()) }
                    } else {
                        val env = n.h("envelope")
                        val e = runCatching {
                            if (env[0] == Ke2e.VERSION_STREAMED && n.s("name").startsWith("stream_")) {
                                Ke2eRequest.openStreamed(ByteArrayInputStream(env), serverKeys()).second.readAll()
                            } else {
                                Ke2eRequest.open(env, serverKeys())
                            }
                        }.exceptionOrNull()
                        assertNotNull("$name must fail", e as? Ke2eException)
                        assert((e as Ke2eException).kind in setOf(Ke2eException.Kind.MALFORMED, Ke2eException.Kind.BAD_FRAME, Ke2eException.Kind.TRUNCATED)) { name }
                    }
                }
                else -> fail("$name: unknown kind ${n.s("kind")}")
            }
            seen++
        }
        assert(seen >= 8) { "only $seen negative cases checked" }
    }

    @Suppress("unused") private fun JsonObject.has(k: String) = this[k] is JsonPrimitive || this[k] is JsonArray
}
