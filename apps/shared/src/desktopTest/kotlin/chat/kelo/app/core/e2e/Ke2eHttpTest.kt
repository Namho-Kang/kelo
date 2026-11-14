package chat.kelo.app.core.e2e

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.AppVersion
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The KE2E transport against a stand-in server (MockWebServer + the core's receiver side): every call goes out as POST /e2e with
 * nothing in the clear, answers come back as ordinary ones, and the error cases of the spec (key changed, a plaintext answer from a
 * pinned server, the clock) behave as section 5 says.
 */
class Ke2eHttpTest {
    private lateinit var server: MockWebServer
    private val keys = X25519KeyPair.generate()
    private val pin = KeyPin(ServerKey(keys.publicKey), verified = true)
    /** What the stand-in server opened: (outer request, inner head, inner body) */
    private val seen = CopyOnWriteArrayList<Triple<RecordedRequest, RequestHead, ByteArray>>()
    /** How the stand-in answers an opened request */
    private var reply: (RequestHead, ByteArray) -> Pair<ResponseHead, ByteArray> = { _, _ ->
        ResponseHead(200, mapOf("content-type" to "application/json", "x-kelo-server" to "0.9.0+1")) to """{"ok":true}""".toByteArray()
    }
    /** A plain answer instead (null: answer through KE2E) */
    private var plain: MockResponse? = null

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                plain?.let { return it }
                if (request.path == Ke2e.INFO_PATH) return MockResponse().setBody(info())
                assertEquals("/e2e", request.path)
                val body = request.body.readByteArray()
                val (opened, inner) = if (body[0] == Ke2e.VERSION_STREAMED) {
                    val (o, r) = Ke2eRequest.openStreamed(ByteArrayInputStream(body), keys)
                    o to r.readAll()
                } else {
                    Ke2eRequest.open(body, keys).let { it to it.body }
                }
                seen += Triple(request, opened.head, inner)
                val (head, out) = reply(opened.head, inner)
                val buf = ByteArrayOutputStream()
                opened.responseWriter(buf).apply { head(head); body(out) }
                return MockResponse().setHeader("Content-Type", Ke2e.CONTENT_TYPE).setBody(Buffer().write(buf.toByteArray()))
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown(); AppVersion.header = ""; AppVersion.reset() }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun info() = """{"v":1,"kid":"${pin.key.kid}","pk":"${pin.key.base64Url}","fingerprint":"${pin.key.fingerprint}"}"""

    private fun api(clock: Ke2eClock = Ke2eClock(), onKeyChanged: () -> Unit = {}, failures: MutableList<ApiError> = mutableListOf()): KeloApi {
        val http = KeloApi.defaultClient.newBuilder().addInterceptor(Ke2eInterceptor(base(), { pin }, clock, onKeyChanged)).build()
        return KeloApi(base(), "device-token-secret", http, keyPin = pin, onFailure = { failures += it })
    }

    @Test fun aCallGoesOutSealedAndComesBackPlain() = runBlocking {
        AppVersion.header = "android/0.9.0+404700"
        val out = api().post<JsonObject>("/api/goals?x=1", buildJsonObject { put("title", "secret goal") })
        assertEquals("true", out["ok"]!!.jsonPrimitive.content)
        val (outer, head, body) = seen.single()
        assertNull(outer.getHeader("Authorization"))
        assertNull(outer.getHeader("X-Kelo-App"))
        assertEquals(Ke2e.CONTENT_TYPE, outer.getHeader("Content-Type"))
        assertEquals("POST", head.method)
        assertEquals("/api/goals?x=1", head.path)
        assertEquals("Bearer device-token-secret", head.headers["authorization"])
        assertEquals("android/0.9.0+404700", head.headers["x-kelo-app"])
        assertTrue(head.headers["content-type"]!!.startsWith("application/json"))
        assertEquals("""{"title":"secret goal"}""", body.toString(Charsets.UTF_8))
        // the inner answer's headers reach the version check
        assertEquals("0.9.0+1", AppVersion.server.value?.version)
    }

    @Test fun theRealStatusAndCodeComeFromTheSealedHead() = runBlocking {
        reply = { _, _ -> ResponseHead(423, mapOf("content-type" to "application/json")) to """{"detail":"Kelo is locked","code":"locked"}""".toByteArray() }
        val failures = mutableListOf<ApiError>()
        try { api(failures = failures).get<JsonElement>("/api/vault"); fail() } catch (e: ApiError) {
            assertEquals(423, e.status); assertEquals("locked", e.code); assertEquals("Kelo is locked", e.detail)
        }
        assertEquals("locked", failures.single().code)
    }

    @Test fun aLargeOneShotUploadIsStreamedInFrames() = runBlocking {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        var progress = 0L
        reply = { _, body -> ResponseHead(200, mapOf("content-type" to "application/json")) to """{"path":"in/${body.size}"}""".toByteArray() }
        val path = api().uploadStream("v.mp4", data.size.toLong(), "video/mp4", { ByteArrayInputStream(data) }) { sent, _ -> progress = sent }
        assertEquals("in/300000", path)
        assertEquals(300_000L, progress)
        val (outer, head, body) = seen.single()
        assertEquals("PUT", head.method)
        assertEquals("/api/uploads/v.mp4", head.path)
        assertEquals("video/mp4", head.headers["content-type"])
        assertArrayEquals(data, body)
    }

    @Test fun downloadsKeepRangeAndPartialAnswers() = runBlocking {
        reply = { h, _ ->
            assertEquals("bytes=0-3", h.headers["range"])
            ResponseHead(206, mapOf("content-type" to "video/mp4", "content-range" to "bytes 0-3/10", "content-length" to "4")) to byteArrayOf(1, 2, 3, 4)
        }
        val http = api().client
        val res = http.newCall(api().requestBuilder("/api/uploads/v.mp4/file").header("Range", "bytes=0-3").build()).execute()
        assertEquals(206, res.code)
        assertEquals("bytes 0-3/10", res.header("Content-Range"))
        assertEquals(4L, res.body!!.contentLength())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), res.body!!.bytes())
    }

    @Test fun aChangedKeyIsReportedNotAnswered() = runBlocking {
        plain = MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
            .setBody("""{"detail":"pair again","code":"e2e_key_changed"}""")
        var changed = false
        try { api(onKeyChanged = { changed = true }).get<JsonElement>("/api/profile"); fail() } catch (e: ApiError) {
            assertEquals(409, e.status); assertTrue(e.isKeyChanged); assertFalse(e.isTransient)
        }
        assertTrue(changed)
    }

    @Test fun aPlaintextAnswerFromAPinnedServerIsNeverHandedOn() = runBlocking {
        plain = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"agent_name":"leaked?"}""")
        try { api().get<JsonElement>("/api/profile"); fail() } catch (e: ApiError) {
            assertTrue(e.isE2eUnsupported); assertFalse(e.isTransient)
        }
        plain = MockResponse().setResponseCode(404)
        try { api().get<JsonElement>("/api/profile"); fail() } catch (e: ApiError) { assertTrue(e.isE2eUnsupported) }
    }

    @Test fun aClockAnswerCorrectsTheClockAndTriesOnce() = runBlocking {
        val clock = Ke2eClock { 1_000_000_000_000L }   // the phone thinks it is 2001
        reply = { h, _ ->
            if (h.ts < 1_700_000_000L) ResponseHead(400, mapOf("content-type" to "application/json")) to """{"code":"e2e_clock","now":1731200000}""".toByteArray()
            else ResponseHead(200, mapOf("content-type" to "application/json")) to """{"ok":true}""".toByteArray()
        }
        api(clock).get<JsonObject>("/api/profile")
        assertEquals(2, seen.size)
        assertEquals(1731200000L, seen[1].second.ts)
        // a replay answer is tried once more too; a second refusal is the caller's
        seen.clear()
        reply = { _, _ -> ResponseHead(400, mapOf("content-type" to "application/json")) to """{"code":"e2e_replay"}""".toByteArray() }
        try { api(clock).get<JsonObject>("/api/profile"); fail() } catch (e: ApiError) { assertEquals("e2e_replay", e.code) }
        assertEquals(2, seen.size)
    }

    @Test fun plainOnPurposePathsAndUnpinnedSessionsPassThrough() = runBlocking {
        val key = E2eInfoCalls.fetch(OkHttpClient(), base())
        assertEquals(pin.key, key)
        // a server that never offered KE2E: no pin, calls go as they are
        plain = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":1}""")
        KeloApi(base(), "t").get<JsonElement>("/api/profile")
        assertTrue(seen.isEmpty())
        plain = MockResponse().setResponseCode(404)
        assertNull(E2eInfoCalls.fetch(OkHttpClient(), base()))
    }

    @Test fun theWebSocketIsSealedWithItsTypeByteAndHead() {
        val ready = CountDownLatch(1)
        val got = CountDownLatch(2)
        val closed = CountDownLatch(1)
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteString>()
        var serverPath = ""
        var serverChannel: WsChannel? = null
        plain = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals(Ke2e.WS_PATH, request.path)
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val ch = serverChannel
                        if (ch == null) {
                            val opened = Ke2eRequest.open(bytes.toByteArray(), keys)
                            serverPath = opened.head.path
                            serverChannel = opened.webSocket().also { c ->
                                webSocket.send(c.sealText("""{"s":101,"h":{}}""").toByteString())
                            }
                            return
                        }
                        val m = ch.openMessage(bytes.toByteArray())
                        if (m is WsMessage.Text && m.text == "ping") {
                            webSocket.send(ch.sealText("""{"type":"frame"}""").toByteString())
                            webSocket.send(ch.sealBinary(byteArrayOf(-1, -40, 1)).toByteString())
                        }
                    }
                })
            }
        }
        val socket = api().newWebSocket("/api/browser/sessions/t1/live?ticket=abc", object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("ping"); ready.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) { texts += text; got.countDown() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { binaries += bytes; got.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
        })
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        assertTrue(got.await(10, TimeUnit.SECONDS))
        socket.close(1000, null)
        assertTrue(closed.await(10, TimeUnit.SECONDS))   // both sides closed before the stand-in shuts down
        assertEquals("/api/browser/sessions/t1/live?ticket=abc", serverPath)
        assertEquals(listOf("""{"type":"frame"}"""), texts)
        assertArrayEquals(byteArrayOf(-1, -40, 1), binaries.single().toByteArray())
    }
}
