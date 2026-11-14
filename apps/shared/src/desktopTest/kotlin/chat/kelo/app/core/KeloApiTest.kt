package chat.kelo.app.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class KeloApiTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun base() = server.url("/").toString()

    @Test fun sendsBearerAndDecodes() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":1,"title":"운동","category":"건강","note":"","status":"tracking","created_at":1}]"""))
        val goals = KeloApi(base(), "tok").get<List<Goal>>("/api/goals")
        assertEquals("운동", goals.single().title)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("/api/goals", req.path)
    }

    @Test fun postsJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":2,"title":"t"}"""))
        KeloApi(base(), "tok").post<JsonElement>("/api/goals", buildJsonObject { put("title", "t") })
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"title":"t"}""", req.body.readUtf8())
    }

    @Test fun aServerWithoutKe2eCannotBeJoined() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))   // GET /api/e2e/info: a server older than 0.9.0
        val link = PairingLink(base().trimEnd('/'), "0123456789abcdefXYZ")
        try { PairingClient().pair(link, "Pixel · Kelo"); fail() } catch (e: ApiError) { assertEquals(PairingClient.CODE_SERVER_OLD, e.code) }
        assertEquals("/api/e2e/info", server.takeRequest().path)
        assertEquals(1, server.requestCount)   // nothing else was sent, least of all the code in the clear
    }

    @Test fun errorCarriesDetailAnd401SignsOut() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid or missing bearer token"}"""))
        var signedOut = false
        val api = KeloApi(base(), "bad", onUnauthorized = { signedOut = true })
        try {
            api.get<JsonElement>("/api/profile"); fail()
        } catch (e: ApiError) {
            assertEquals(401, e.status)
            assertEquals("invalid or missing bearer token", e.detail)
        }
        assertTrue(signedOut)
    }

    @Test fun nonStringDetailFallsBackToStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"msg":"x"}]}"""))
        try {
            KeloApi(base(), "t").get<JsonElement>("/x"); fail()
        } catch (e: ApiError) {
            assertEquals("HTTP 422", e.detail)
        }
    }

    @Test fun aWebPageOrWrongShapeIsAnErrorNotACrash() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        server.enqueue(MockResponse().setBody("""{"unexpected":true}"""))
        for (i in 0..1) {
            try {
                KeloApi(base(), "t").get<List<Goal>>("/api/goals"); fail()
            } catch (e: ApiError) {
                assertTrue(e.isNetwork)
            }
        }
    }

    @Test fun unreachableIsANetworkError() = runBlocking {
        val url = base(); server.shutdown()
        try {
            KeloApi(url, "t").get<JsonElement>("/api/profile"); fail()
        } catch (e: ApiError) {
            assertTrue(e.isNetwork)
        }
    }

    @Test fun uploadsRawBytes() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"path":"uploads/a b.pdf","ok":true}"""))
        val path = KeloApi(base(), "tok").upload("a b.pdf", byteArrayOf(1, 2, 3), "application/pdf")
        assertEquals("uploads/a b.pdf", path)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/uploads/a%20b.pdf", req.path)
        assertEquals(3L, req.bodySize)
    }

    @Test fun uploadReportsProgressUpToTheWholeFile() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"path":"uploads/a.pdf","ok":true}"""))
        val seen = mutableListOf<Pair<Long, Long>>()
        val bytes = ByteArray(100_000) { (it % 7).toByte() }
        val path = KeloApi(base(), "tok").upload("a.pdf", bytes, "application/pdf") { sent, total -> synchronized(seen) { seen += sent to total } }
        assertEquals("uploads/a.pdf", path)
        val req = server.takeRequest()
        assertEquals(100_000L, req.bodySize)
        assertTrue(req.body.readByteArray().contentEquals(bytes))
        synchronized(seen) {
            assertEquals(0L to 100_000L, seen.first())
            assertEquals(100_000L to 100_000L, seen.last())
            assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
        }
    }
}
