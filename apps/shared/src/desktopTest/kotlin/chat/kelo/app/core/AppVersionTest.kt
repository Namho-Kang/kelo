package chat.kelo.app.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** docs/versioning.md: X-Kelo-App on every call, X-Kelo-Server compared with min_server, 426 app_too_old as one ApiError kind. */
class AppVersionTest {
    @After fun reset() { AppVersion.header = ""; AppVersion.minServer = ""; AppVersion.reset() }

    @Test fun versionsCompareByNumber() {
        assertTrue(AppVersion.compare("0.9.0+1", "0.9.0") == 0)
        assertTrue(AppVersion.compare("0.10.0", "0.9.9") > 0)
        assertTrue(AppVersion.compare("0.8.12", "0.9.0") < 0)
        assertTrue(AppVersion.compare("1.0", "1.0.0") == 0)
        assertTrue(AppVersion.compare("0.9.1-beta.1", "0.9.1") == 0)
        assertEquals("0.9.0", AppVersion.versionOf("0.9.0+1"))
    }

    @Test fun anOlderOrSilentServerIsTooOldOnlyWhenTheAnswerIsSurelyItsOwn() {
        AppVersion.minServer = "0.9.0"
        assertFalse(AppVersion.serverTooOld(null))                                     // nothing seen yet
        assertFalse(AppVersion.serverTooOld(AppVersion.ServerSeen("0.9.0+1")))
        assertTrue(AppVersion.serverTooOld(AppVersion.ServerSeen("0.8.3+7")))
        assertTrue(AppVersion.serverTooOld(AppVersion.ServerSeen(null)))               // no header: older than 0.9.0
        assertFalse(AppVersion.serverTooOld(AppVersion.ServerSeen(null), min = ""))
        // a tunnel's error page (5xx, HTML) says nothing about the server
        AppVersion.observe(502, null, "text/html")
        assertNull(AppVersion.server.value)
        AppVersion.observe(200, null, "application/json")
        assertEquals(AppVersion.ServerSeen(null), AppVersion.server.value)
        AppVersion.observe(200, "0.9.1+2", "application/json")
        assertEquals("0.9.1+2", AppVersion.server.value?.version)
    }

    @Test fun everyCallSaysWhichAppItIsAnd426IsAppTooOld() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            AppVersion.header = "android/0.9.0+404700"
            server.enqueue(MockResponse().setHeader("X-Kelo-Server", "0.9.0+1").setHeader("Content-Type", "application/json").setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(426).setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"update the app","code":"app_too_old","min_app":"0.9.1"}"""))
            val failures = mutableListOf<ApiError>()
            val api = KeloApi(server.url("/").toString(), "t", onFailure = { failures += it })
            api.get<JsonElement>("/api/profile")
            assertEquals("android/0.9.0+404700", server.takeRequest().getHeader("X-Kelo-App"))
            assertEquals("0.9.0+1", AppVersion.server.value?.version)
            try { api.get<JsonElement>("/api/goals"); fail() } catch (e: ApiError) {
                assertTrue(e.isAppTooOld); assertEquals("app_too_old", e.code); assertFalse(e.isTransient)
            }
            assertTrue(failures.single().isAppTooOld)
        } finally {
            server.shutdown()
        }
    }

    @Test fun theCodeIsReadAtTheTopLevelAndInsideAnObjectDetail() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(423).setBody("""{"detail":"locked","code":"locked"}"""))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"secret_in_message","spans":[]}}"""))
            val api = KeloApi(server.url("/").toString(), "t")
            try { api.get<JsonElement>("/a"); fail() } catch (e: ApiError) { assertEquals("locked", e.code) }
            try { api.get<JsonElement>("/b"); fail() } catch (e: ApiError) { assertEquals("secret_in_message", e.code) }
        } finally {
            server.shutdown()
        }
    }
}
