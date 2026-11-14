package chat.kelo.desktop

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.AppVersion
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.e2e.ServerKey
import chat.kelo.app.core.e2e.X25519KeyPair
import chat.kelo.desktop.core.DesktopVersion
import chat.kelo.desktop.core.LinkVerify
import chat.kelo.desktop.core.ReleaseInfo
import chat.kelo.desktop.core.UpdateRules
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The desktop's side of the min version contract (docs/versioning.md): where its two numbers come from, the header it sends through
 * the shared mechanism (chat.kelo.app.core.AppVersion, one header, also inside KE2E), and "verify with a link" on My → My devices.
 */
class VersionCheckTest {
    @Test fun versionsComeFromTheTrackedFiles() {
        val json = File("../../version.json").readText()
        assertTrue(json, json.contains("\"version\": \"${ReleaseInfo.VERSION}\""))
        assertTrue(json.contains("\"min_server\": \"${ReleaseInfo.MIN_SERVER}\""))
        val props = java.util.Properties().apply { File("version.properties").inputStream().use { load(it) } }
        assertEquals(props.getProperty("packageVersion"), ReleaseInfo.PACKAGE)
        // MSI and macOS: MAJOR >= 1, three plain numbers
        assertTrue(ReleaseInfo.PACKAGE, (UpdateRules.versionCode(ReleaseInfo.PACKAGE) ?: 0) >= 100_000_000L)
        assertEquals(2, ReleaseInfo.PACKAGE.count { it == '.' })
    }

    @Test fun theHeaderNamesReleaseAndPackageCode() {
        assertEquals("desktop/0.9.0+100000008", DesktopVersion.header("0.9.0", "1.0.8"))
        assertEquals("desktop/0.9.0", DesktopVersion.header("0.9.0", "dev"))
    }

    @Test fun oneHeaderGoesOutAndTheServersVersionIsRead() = runBlocking {
        DesktopVersion.install()
        assertEquals(ReleaseInfo.MIN_SERVER, AppVersion.minServer)
        val server = MockWebServer()
        server.start()
        try {
            AppVersion.reset()
            val api = KeloApi(server.url("/").toString(), "t")   // KeloApi.defaultClient carries the shared interceptor
            server.enqueue(MockResponse().setBody("{}").addHeader("Content-Type", "application/json").addHeader("X-Kelo-Server", "0.9.0+1"))
            api.get<JsonElement>("/api/profile")
            val sent = server.takeRequest()
            assertEquals(listOf(DesktopVersion.header()), sent.headers.values("X-Kelo-App"))   // exactly one
            assertFalse(AppVersion.serverTooOld(AppVersion.server.value))

            server.enqueue(MockResponse().setBody("{}").addHeader("Content-Type", "application/json"))   // older than 0.9.0: no header
            api.get<JsonElement>("/api/profile")
            server.takeRequest()
            assertTrue(AppVersion.serverTooOld(AppVersion.server.value))

            server.enqueue(MockResponse().setResponseCode(426).setBody("""{"code":"app_too_old","detail":"Update Kelo","min_app":"0.9.1"}"""))
            val e = runCatching { api.get<JsonElement>("/api/goals") }.exceptionOrNull() as ApiError
            assertTrue(e.isAppTooOld)
            assertEquals("app_too_old", e.code)
        } finally {
            AppVersion.reset()
            server.shutdown()
        }
    }

    @Test fun aPastedLinkVerifiesThePinnedKey() {
        val key = ServerKey(X25519KeyPair.generate().publicKey)
        val other = ServerKey(X25519KeyPair.generate().publicKey)
        val same = { k: ServerKey -> k == key }
        assertEquals(LinkVerify.Result.DONE, LinkVerify.check("https://me.kelo.chat/#pair=ABCD1234EFGH5678&k=${key.base64Url}", same))
        assertEquals(LinkVerify.Result.MISMATCH, LinkVerify.check("https://me.kelo.chat/#pair=ABCD1234EFGH5678&k=${other.base64Url}", same))
        assertEquals(LinkVerify.Result.NO_KEY, LinkVerify.check("https://me.kelo.chat/#pair=ABCD1234EFGH5678", same))
        assertEquals(LinkVerify.Result.NOT_A_LINK, LinkVerify.check("hello", same))
    }
}
