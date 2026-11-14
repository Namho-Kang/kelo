package chat.kelo.app.upload

import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UploadLimitsSourceTest {
    private val server = MockWebServer().apply { start() }
    private val api = KeloApi(server.url("/").toString(), "tok")
    @AfterTest fun stop() { runCatching { server.shutdown() } }

    @Test fun theAnswerIsAskedOnceAndKept() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"single_max":99000000,"part_size":90000000,"image_max":15000000}"""))
        val src = UploadLimitsSource(api)
        val want = UploadLimits(99_000_000, 90_000_000, 15_000_000)
        assertEquals(want, src.get())
        assertEquals(want, src.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun anOldServerGivesLegacyAndIsNotAskedAgain() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        val src = UploadLimitsSource(api)
        assertEquals(UploadLimits.LEGACY, src.get())
        assertEquals(UploadLimits.LEGACY, src.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun anUnreadableAnswerGivesLegacyAndIsKept() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        val src = UploadLimitsSource(api)
        assertEquals(UploadLimits.LEGACY, src.get())
        assertEquals(UploadLimits.LEGACY, src.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun aNetworkErrorGivesLegacyForThisUploadOnly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("""{"single_max":null,"part_size":90000000,"image_max":15000000}"""))
        val src = UploadLimitsSource(api)
        assertEquals(UploadLimits.LEGACY, src.get())
        assertEquals(UploadLimits(null, 90_000_000, 15_000_000), src.get())   // asked again
        assertEquals(2, server.requestCount)
    }

    @Test fun aServerErrorIsNotKeptEither() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"single_max":99000000,"part_size":90000000,"image_max":15000000}"""))
        val src = UploadLimitsSource(api)
        assertEquals(UploadLimits.LEGACY, src.get())
        assertEquals(90_000_000L, src.get().partSize)
    }
}
