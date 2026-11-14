package chat.kelo.app.my

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing out from My takes this device off the server's list first, and signs out whatever the server answers. */
class SignOutForgetTest {
    @Test fun theCurrentDeviceIsDeletedThenTheSessionIsCleared() {
        val server = MyFakeServer { method, path, _ ->
            when ("$method $path") {
                "GET /api/devices" -> MyFakeServer.ok("""{"devices":[{"id":7,"name":"a"},{"id":9,"name":"b"}],"current":9}""")
                "DELETE /api/devices/9" -> MyFakeServer.ok("{}")
                else -> null
            }
        }
        try {
            val sessions = server.sessions()
            assertNotNull(sessions.api.value)
            runBlocking { sessions.signOutAndForget() }
            assertEquals(listOf("GET /api/devices", "DELETE /api/devices/9"), server.requests.toList())
            assertNull(sessions.api.value)
        } finally { server.shutdown() }
    }

    @Test fun aFailingServerStillSignsOut() {
        val server = MyFakeServer { _, _, _ -> MyFakeServer.fail(500, "broken") }
        try {
            val sessions = server.sessions()
            runBlocking { sessions.signOutAndForget() }
            assertTrue(server.saw("GET /api/devices"))
            assertNull(sessions.api.value)
        } finally { server.shutdown() }
    }
}
