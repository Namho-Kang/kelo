package chat.kelo.app.core.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The KE2E envelope, response frames and WebSocket channel (docs/security/e2e-and-phone-key.md 1.2–1.4). */
class Ke2eEnvelopeTest {
    private val server = X25519KeyPair.derive(ByteArray(32) { it.toByte() })
    private val key = ServerKey(server.publicKey)

    private fun expect(kind: Ke2eException.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: Ke2eException) {
            assertEquals(e.message, kind, e.kind)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- keys

    @Test fun kidAndFingerprintComeFromSha256OfThePublicKey() {
        val d = MessageDigest.getInstance("SHA-256").digest(server.publicKey)
        assertEquals(hexOf(d.copyOf(8)), key.kid)
        assertArrayEquals(d.copyOf(8), key.kidBytes)
        assertTrue(key.kid.matches(Regex("[0-9a-f]{16}")))
        assertTrue(key.fingerprint, key.fingerprint.matches(Regex("([A-Z2-7]{4}-){5}[A-Z2-7]{4}")))
        assertEquals(Base32.encode(d.copyOf(15)), key.fingerprint.replace("-", ""))
        assertEquals("kelo-e2e v1 ${key.kid}", Ke2e.info(key.kid).toString(Charsets.US_ASCII))
    }

    @Test fun base32IsRfc4648() {
        // RFC 4648 section 10, without the padding
        val cases = mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        for ((plain, b32) in cases) assertEquals(b32, Base32.encode(plain.toByteArray()))
    }

    @Test fun serverKeyRoundTripsAndChecksInfo() {
        assertEquals(key, ServerKey.fromBase64Url(key.base64Url))
        assertFalse(key.base64Url.contains('='))
        assertEquals(key, ServerKey.fromInfo(1, key.base64Url, key.kid, key.fingerprint))
        assertNull(ServerKey.fromInfo(2, key.base64Url, null, null))
        assertNull(ServerKey.fromInfo(1, key.base64Url, "0000000000000000", null))
        assertNull(ServerKey.fromInfo(1, key.base64Url, null, "AAAA-BBBB"))
        assertNull(ServerKey.fromInfo(1, "short", null, null))
        expect(Ke2eException.Kind.MALFORMED) { ServerKey(ByteArray(31)) }
    }

    @Test fun pinsSurviveStorage() {
        for (p in listOf(KeyPin(key, true), KeyPin(key, false))) assertEquals(p, KeyPin.decode(p.encode()))
        assertNull(KeyPin.decode("v2:verified:" + key.base64Url))
        assertNull(KeyPin.decode("v1:maybe:" + key.base64Url))
    }

    // ---------------------------------------------------------------------------------------------------------------- request

    @Test fun requestRoundTripsAndKeepsOnlyTheAllowedHeaders() {
        val head = RequestHead.of(
            "post", "/api/talk?x=1",
            linkedMapOf(
                "Authorization" to "Bearer tok-SECRET", "Content-Type" to "application/json", "Accept-Language" to "ko",
                "X-Kelo-Client" to "desktop", "Cookie" to "c=1", "Host" to "h", "User-Agent" to "ua",
            ),
            nowSeconds = 1_731_200_000,
        )
        assertEquals(setOf("authorization", "content-type", "accept-language", "x-kelo-client"), head.headers.keys)
        assertEquals("POST", head.method)
        assertEquals(22, head.nonce.length)   // 16 bytes, base64url without padding
        val body = """{"message":"hello there"}""".toByteArray()
        val sealed = Ke2eRequest.seal(key, head, body)
        val outer = sealed.outerBody
        assertEquals(Ke2e.VERSION, outer[0])
        assertArrayEquals(key.kidBytes, outer.copyOfRange(1, 9))
        assertArrayEquals(sealed.context.enc, outer.copyOfRange(9, 41))
        // nothing readable crosses the wire
        val wire = String(outer, Charsets.ISO_8859_1)
        for (s in listOf("Bearer", "tok-SECRET", "/api/talk", "hello there", "authorization")) assertFalse(s, wire.contains(s))
        // 1 + 8 + 32 + (4 + head + body) + 16
        assertEquals(1 + 8 + 32 + 4 + head.toJsonBytes().size + body.size + 16, outer.size)

        val opened = Ke2eRequest.open(outer, server)
        assertEquals(head, opened.head)
        assertArrayEquals(body, opened.body)
        // both sides export the same secrets
        assertArrayEquals(sealed.context.responseKey(), opened.context.responseKey())
        assertNotEquals(hexOf(sealed.context.responseKey()), hexOf(sealed.context.wsKeys().first))
        assertNotEquals(hexOf(sealed.context.wsKeys().first), hexOf(sealed.context.wsKeys().second))
        // a fresh ephemeral key every time
        assertNotEquals(hexOf(outer.copyOfRange(9, 41)), hexOf(Ke2eRequest.seal(key, head, body).outerBody.copyOfRange(9, 41)))
    }

    @Test fun headJsonIsInTheSpecsOrder() {
        val h = RequestHead("GET", "/api/goals", linkedMapOf("authorization" to "Bearer t"), 5, "bm9uY2U")
        assertEquals("""{"m":"GET","p":"/api/goals","h":{"authorization":"Bearer t"},"ts":5,"n":"bm9uY2U"}""", h.toJsonBytes().toString(Charsets.UTF_8))
        assertEquals(h, RequestHead.parse(h.toJsonBytes()))
        // the server may write JSON with spaces (Python's json.dumps): the head is parsed, not compared as bytes
        assertEquals(h, RequestHead.parse("""{"m": "GET", "p": "/api/goals", "h": {"authorization": "Bearer t"}, "ts": 5, "n": "bm9uY2U"}""".toByteArray()))
        assertFalse(h.toString().contains("Bearer"))
    }

    @Test fun anotherServersKidIsKeyChangedAndTamperingDoesNotOpen() {
        val head = RequestHead.of("GET", "/api/profile", mapOf("authorization" to "Bearer t"))
        val other = X25519KeyPair.generate()
        val toOther = Ke2eRequest.seal(ServerKey(other.publicKey), head).outerBody
        expect(Ke2eException.Kind.KEY_CHANGED) { Ke2eRequest.open(toOther, server) }

        val outer = Ke2eRequest.seal(key, head).outerBody
        for (i in listOf(9, 40, 41, outer.size - 1)) {   // enc, ciphertext, tag
            val bad = outer.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            expect(Ke2eException.Kind.BAD_FRAME) { Ke2eRequest.open(bad, server) }
        }
        expect(Ke2eException.Kind.MALFORMED) { Ke2eRequest.open(outer.copyOf(40), server) }
        expect(Ke2eException.Kind.MALFORMED) { Ke2eRequest.open(byteArrayOf(2) + outer.copyOfRange(1, outer.size), server) }
    }

    @Test fun lowOrderServerKeyIsRefused() {
        expect(Ke2eException.Kind.MALFORMED) { Ke2eRequest.seal(ServerKey(ByteArray(32)), RequestHead.of("GET", "/", emptyMap())) }
    }

    @Test fun onlyTheSpecsPathsStayPlain() {
        for (p in listOf("/healthz", "/api/e2e/info", "/download/latest.json", "/broker/x")) assertTrue(p, Ke2e.staysPlain(p))
        for (p in listOf("/api/pair", "/api/profile", "/api/e2e/infox", "/api/turns/1?wait=20", "/")) assertFalse(p, Ke2e.staysPlain(p))
    }

    // ---------------------------------------------------------------------------------------------------------------- response

    private fun exchange(): Pair<SealedRequest, OpenedRequest> {
        val sealed = Ke2eRequest.seal(key, RequestHead.of("GET", "/api/uploads/a/file", mapOf("authorization" to "Bearer t")))
        return sealed to Ke2eRequest.open(sealed.outerBody, server)
    }

    /** The raw frames (length prefix included) of a written response, to cut and reorder. */
    private fun frames(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val n = Ke2eRequest.readU32(bytes, at).toInt()
            out += bytes.copyOfRange(at, at + 4 + n)
            at += 4 + n
        }
        return out
    }

    @Test fun aLargeBodyComesBackInFramesOfAtMost64KiB() {
        val (sealed, opened) = exchange()
        val body = ByteArray(200 * 1024 + 7) { (it * 31).toByte() }
        val wire = ByteArrayOutputStream()
        opened.responseWriter(wire).apply {
            head(ResponseHead(200, mapOf("content-type" to "application/octet-stream", "content-length" to body.size.toString())))
            body(body)
        }
        val fs = frames(wire.toByteArray())
        assertEquals(1 + 4, fs.size)   // head + 64+64+64+8.x KiB
        assertTrue(fs.drop(1).all { it.size <= 4 + Ke2e.BODY_FRAME_MAX + Ke2e.TAG_BYTES })

        val r = sealed.responseReader(ByteArrayInputStream(wire.toByteArray()))
        assertEquals(200, r.head().status)
        assertEquals("application/octet-stream", r.head().headers["content-type"])
        assertArrayEquals(body, r.body.readBytes())
        assertTrue(r.finished)
    }

    @Test fun eventsArriveOneFrameEach() {
        val (sealed, opened) = exchange()
        val wire = ByteArrayOutputStream()
        val w = opened.responseWriter(wire)
        w.head(ResponseHead(200, mapOf("content-type" to "text/event-stream")))
        val events = listOf("data: {\"t\":\"a\"}\n\n", "data: {\"t\":\"b\"}\n\n", "")
        events.forEachIndexed { i, e -> w.frame(e.toByteArray(), last = i == events.lastIndex) }
        val r = sealed.responseReader(ByteArrayInputStream(wire.toByteArray()))
        assertEquals("text/event-stream", r.head().headers["content-type"])
        for (e in events) assertEquals(e, r.nextFrame()!!.toString(Charsets.UTF_8))
        assertNull(r.nextFrame())
    }

    @Test fun anAnswerWithoutBodyIsOneLastFrame() {
        val (sealed, opened) = exchange()
        val wire = ByteArrayOutputStream()
        opened.responseWriter(wire).head(ResponseHead(423, mapOf("content-type" to "application/json")), last = true)
        val r = sealed.responseReader(ByteArrayInputStream(wire.toByteArray()))
        assertEquals(423, r.head().status)
        assertEquals(0, r.body.readBytes().size)
    }

    private fun written(): Pair<SealedRequest, List<ByteArray>> {
        val (sealed, opened) = exchange()
        val wire = ByteArrayOutputStream()
        opened.responseWriter(wire).apply { head(ResponseHead(200, emptyMap())); body(ByteArray(3 * Ke2e.BODY_FRAME_MAX) { 7 }) }
        return sealed to frames(wire.toByteArray())
    }

    private fun read(sealed: SealedRequest, parts: List<ByteArray>) {
        val r = sealed.responseReader(ByteArrayInputStream(parts.fold(ByteArray(0)) { a, b -> a + b }))
        r.head()
        r.body.readBytes()
    }

    @Test fun truncationCounterGapsReorderingAndTrailingBytesAreErrors() {
        val (sealed, fs) = written()
        read(sealed, fs)   // the untouched stream is fine
        expect(Ke2eException.Kind.TRUNCATED) { read(sealed, fs.dropLast(1)) }                                   // no last frame
        expect(Ke2eException.Kind.TRUNCATED) { read(sealed, fs.dropLast(1) + fs.last().copyOf(fs.last().size - 3)) }  // cut inside
        expect(Ke2eException.Kind.TRUNCATED) { read(sealed, emptyList()) }                                        // nothing at all
        expect(Ke2eException.Kind.BAD_FRAME) { read(sealed, fs.filterIndexed { i, _ -> i != 2 }) }               // counter gap
        expect(Ke2eException.Kind.BAD_FRAME) { read(sealed, listOf(fs[0], fs[2], fs[1]) + fs.drop(3)) }          // reordered
        expect(Ke2eException.Kind.BAD_FRAME) { read(sealed, fs + byteArrayOf(0)) }                                // after the last
        expect(Ke2eException.Kind.BAD_FRAME) { read(sealed, fs.take(1) + listOf(byteArrayOf(0x7f, 0, 0, 0)) + fs.drop(1)) }  // too large
        val flipped = fs[1].copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        expect(Ke2eException.Kind.BAD_FRAME) { read(sealed, listOf(fs[0], flipped) + fs.drop(2)) }
        // another request's key cannot read it
        val (other, _) = exchange()
        expect(Ke2eException.Kind.BAD_FRAME) { read(other, fs) }
    }

    @Test fun frameCipherIsPlainChaCha20Poly1305WithTheCountingNonce() {
        // checked against the JDK's own ChaCha20-Poly1305 (an independent implementation)
        val k = ByteArray(32) { (it + 1).toByte() }
        val aad = "kelo-e2e v1 res".toByteArray() + 0
        val c = FrameCipher(k)
        repeat(3) { c.seal(aad, ByteArray(0)) }
        val ours = c.seal(aad, "hello".toByteArray())
        val jdk = Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "ChaCha20"), IvParameterSpec(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3)))
            updateAAD(aad)
        }.doFinal("hello".toByteArray())
        assertArrayEquals(jdk, ours)
        assertEquals("00000000" + "0102030405060708", hexOf(FrameCipher.nonce(0x0102030405060708)))
    }

    // ---------------------------------------------------------------------------------------------------------------- WebSocket

    @Test fun webSocketBothWaysWithTheirOwnCounters() {
        val sealed = Ke2eRequest.seal(key, RequestHead.of("GET", "/api/browser/sessions/t1/ws", mapOf("authorization" to "Bearer t")))
        val opened = Ke2eRequest.open(sealed.outerBody, server)
        assertEquals("/api/browser/sessions/t1/ws", opened.head.p())
        val app = sealed.webSocket()
        val srv = opened.webSocket()
        val m1 = app.seal("tap 1".toByteArray())
        val m2 = app.seal("tap 2".toByteArray())
        val s1 = srv.seal("frame 1".toByteArray())
        assertEquals("tap 1", srv.open(m1).toString(Charsets.UTF_8))
        assertEquals("frame 1", app.open(s1).toString(Charsets.UTF_8))
        expect(Ke2eException.Kind.BAD_FRAME) { srv.open(m1) }   // a replayed message does not open (the counter moved on)
        assertEquals("tap 2", srv.open(m2).toString(Charsets.UTF_8))
        expect(Ke2eException.Kind.BAD_FRAME) { app.open(app.seal("x".toByteArray())) }   // own direction's key is not the other's
        // no length prefix: a message is nonce-less ciphertext + tag
        assertEquals("tap 1".length + Ke2e.TAG_BYTES, m1.size)
    }

    private fun RequestHead.p() = path
}
