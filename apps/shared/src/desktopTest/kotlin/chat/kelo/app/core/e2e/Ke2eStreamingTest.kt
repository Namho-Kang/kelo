package chat.kelo.app.core.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Spec 5.2 (streamed request bodies, envelope 0x02), 5.8 (WebSocket message type byte), and cuts at every byte of an answer. */
class Ke2eStreamingTest {
    private val server = X25519KeyPair.generate()
    private val key = ServerKey(server.publicKey)

    private fun streamed(body: ByteArray, frames: MutableList<Long>? = null): ByteArray {
        val req = Ke2eRequest.sealStreamed(key, RequestHead.of("PUT", "/api/uploads/v.mp4", mapOf("Authorization" to "Bearer t")))
        val out = ByteArrayOutputStream()
        out.write(req.prefix)
        req.bodyWriter(out).copyFrom(ByteArrayInputStream(body)) { frames?.add(it) }
        return out.toByteArray()
    }

    @Test fun aStreamedBodyArrivesWholeInFramesOfAtMost64KiB() {
        for (size in listOf(0, 1, Ke2e.BODY_FRAME_MAX - 1, Ke2e.BODY_FRAME_MAX, Ke2e.BODY_FRAME_MAX + 1, 200_000)) {
            val body = ByteArray(size) { (it * 7).toByte() }
            val progress = mutableListOf<Long>()
            val outer = streamed(body, progress)
            assertEquals(Ke2e.VERSION_STREAMED, outer[0])
            val (opened, reader) = Ke2eRequest.openStreamed(ByteArrayInputStream(outer), server)
            assertEquals("PUT", opened.head.method)
            assertEquals("Bearer t", opened.head.headers["authorization"])
            assertArrayEquals("size $size", body, reader.readAll())
            assertEquals(size.toLong(), progress.last())
            assertEquals(maxOf(1, (size + Ke2e.BODY_FRAME_MAX - 1) / Ke2e.BODY_FRAME_MAX), progress.size)
        }
    }

    @Test fun aStreamedBodyCutOffOrExtendedIsAnError() {
        val outer = streamed(ByteArray(150_000) { 3 })
        for (cut in listOf(outer.size - 1, outer.size - 20, 200, 120)) {
            val e = assertThrows("cut $cut", Ke2eException::class.java) {
                val (_, r) = Ke2eRequest.openStreamed(ByteArrayInputStream(outer.copyOf(cut)), server)
                r.readAll()
            }
            assertTrue(e.kind == Ke2eException.Kind.TRUNCATED || e.kind == Ke2eException.Kind.MALFORMED)
        }
        assertThrows(Ke2eException::class.java) {
            Ke2eRequest.openStreamed(ByteArrayInputStream(outer + byteArrayOf(1)), server).second.readAll()
        }
    }

    @Test fun anotherServerSeesKeyChangedAndTheResponseUsesTheSameContext() {
        val req = Ke2eRequest.sealStreamed(key, RequestHead.of("PUT", "/api/uploads/a", emptyMap()))
        val out = ByteArrayOutputStream().also { it.write(req.prefix); req.bodyWriter(it).frame(byteArrayOf(1), last = true) }.toByteArray()
        val e = assertThrows(Ke2eException::class.java) { Ke2eRequest.openStreamed(ByteArrayInputStream(out), X25519KeyPair.generate()) }
        assertEquals(Ke2eException.Kind.KEY_CHANGED, e.kind)
        val (opened, reader) = Ke2eRequest.openStreamed(ByteArrayInputStream(out), server)
        assertArrayEquals(byteArrayOf(1), reader.readAll())
        val answer = ByteArrayOutputStream().also { o ->
            opened.responseWriter(o).apply { head(ResponseHead(200, mapOf("content-type" to "application/json"))); body("{}".toByteArray()) }
        }.toByteArray()
        val r = req.responseReader(ByteArrayInputStream(answer))
        assertEquals(200, r.head().status)
        assertArrayEquals("{}".toByteArray(), r.body.readBytes())
    }

    @Test fun webSocketMessagesCarryTheirType() {
        val sealed = Ke2eRequest.seal(key, RequestHead.of("GET", "/api/browser/sessions/1/live?ticket=x", emptyMap()))
        val opened = Ke2eRequest.open(sealed.outerBody, server)
        val phone = sealed.webSocket()
        val srv = opened.webSocket()
        val t = srv.openMessage(phone.sealText("{\"type\":\"tap\"}"))
        assertEquals("{\"type\":\"tap\"}", (t as WsMessage.Text).text)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2)
        assertArrayEquals(jpeg, (phone.openMessage(srv.sealBinary(jpeg)) as WsMessage.Binary).bytes)
        assertEquals("", (phone.openMessage(srv.sealText("")) as WsMessage.Text).text)
        assertThrows(Ke2eException::class.java) { phone.openMessage(srv.seal(ByteArray(0))) }
        assertThrows(Ke2eException::class.java) { phone.openMessage(srv.seal(byteArrayOf(3, 1))) }
    }

    @Test fun anAnswerCutAtAnyByteIsTruncation() {
        val sealed = Ke2eRequest.seal(key, RequestHead.of("GET", "/api/goals", emptyMap()))
        val opened = Ke2eRequest.open(sealed.outerBody, server)
        val answer = ByteArrayOutputStream().also { o ->
            opened.responseWriter(o).apply { head(ResponseHead(200, emptyMap())); body(ByteArray(70_000) { 9 }) }
        }.toByteArray()
        for (cut in 0 until answer.size step 997) {
            val e = assertThrows("cut $cut", Ke2eException::class.java) {
                val r = sealed.responseReader(ByteArrayInputStream(answer.copyOf(cut)))
                r.head(); r.body.readBytes()
            }
            assertEquals("cut $cut", Ke2eException.Kind.TRUNCATED, e.kind)
        }
        val r = sealed.responseReader(ByteArrayInputStream(answer))
        r.head()
        assertEquals(70_000, r.body.readBytes().size)
    }

    @Test fun aHugeLengthPrefixIsRefusedBeforeAllocating() {
        val sealed = Ke2eRequest.seal(key, RequestHead.of("GET", "/api/goals", emptyMap()))
        val e = assertThrows(Ke2eException::class.java) { sealed.responseReader(ByteArrayInputStream(byteArrayOf(0x7f, 0, 0, 0))).head() }
        assertEquals(Ke2eException.Kind.BAD_FRAME, e.kind)
    }
}
