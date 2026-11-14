package chat.kelo.app.start

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.PairResult
import chat.kelo.app.core.PairingClient
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Session
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * "Connect with an access key" on the connect screen (core/KeySignIn, as the phone's ConnectViewModel.connectWithKey): a real key
 * signs in with GET /api/profile; a multi-use review or pairing code typed in its place is refused there (401) and then accepted
 * through POST /api/pair, exactly as a pasted link; text that is not a code, or a code the server refuses, says the key is wrong.
 */
class KeySignInTest {
    private val server = MockWebServer()
    /** The two calls in the clear against the stand-in server (the real PairingClient always seals: see the KE2E test below) */
    private suspend fun connect(url: String, key: String, name: String) = KeySignIn.connect(url, key, name,
        check = { u, k -> Session(u, k) to KeloApi(u, k, OkHttpClient()).get<Profile>("/api/profile") },
        pair = { l, n -> Session(l.serverUrl, KeloApi(l.serverUrl, "", OkHttpClient()).post<PairResult>("/api/pair",
            buildJsonObject { put("code", l.code); put("name", n) }).token) })
    private val pairBodies = mutableListOf<String>()

    private val code = "ABCD-EFGH-JKMN-PQRS"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/profile" && request.getHeader("Authorization") == "Bearer the-access-key" ->
                    MockResponse().setBody("""{"agent_name":"Kelo","user_name":"Cheese"}""")
                request.path == "/api/profile" -> MockResponse().setResponseCode(401).setBody("""{"detail":"unauthorized"}""")
                request.path == "/api/pair" -> {
                    val body = request.body.readUtf8()
                    pairBodies += body
                    if (body.contains(code)) MockResponse().setBody("""{"token":"device-token-1"}""")
                    else MockResponse().setResponseCode(400).setBody("""{"detail":"This sign-in code is not valid."}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private val url get() = server.url("/").toString().trimEnd('/')

    @After fun stop() = server.shutdown()

    @Test fun anAccessKeySignsInWithoutAPairing() = runBlocking {
        val (session, profile) = connect(url, "  the-access-key ", "Desktop · Kelo")
        assertEquals("the-access-key", session.token)
        assertNotNull(profile)
        assertTrue("no pairing for a real key", pairBodies.isEmpty())
    }

    @Test fun aReviewCodeInTheKeyFieldPairsLikeALink() = runBlocking {
        val (session, profile) = connect(url, "  $code ", "Desktop · Kelo")
        assertEquals("device-token-1", session.token)
        assertEquals(url, session.serverUrl)
        assertNull("the profile is read with the new token by the caller", profile)
        assertEquals(1, pairBodies.size)
        assertTrue(pairBodies[0].contains("\"code\":\"$code\""))
        assertTrue(pairBodies[0].contains("Desktop · Kelo"))
    }

    @Test fun aRefusedCodeSaysTheKeyIsWrong() = runBlocking {
        try {
            connect(url, "WXYZ-WXYZ-WXYZ-WXYZ", "Desktop · Kelo")
            fail("a refused code does not sign in")
        } catch (e: ApiError) {
            assertTrue("the key's own 401 is what the screen shows", e.isUnauthorized)
        }
        assertEquals(1, pairBodies.size)
    }

    @Test fun textThatIsNotACodeIsNotSentAsOne() = runBlocking {
        for (text in listOf("short", "ABCD\tEFGH JKMN PQRS")) {
            try {
                connect(url, text, "Desktop · Kelo")
                fail("$text does not sign in")
            } catch (e: ApiError) {
                assertTrue(e.isUnauthorized)
            }
        }
        assertTrue("never tried as a code", pairBodies.isEmpty())
    }

    @Test fun aCodeTypedWithSpacesIsSentWithoutThem() = runBlocking {
        try {
            connect(url, " ABCD EFGH JKMN PQRS ", "Desktop · Kelo")
        } catch (_: ApiError) {
            // whether the fake accepts it does not matter here: only what was sent
        }
        assertEquals(1, pairBodies.size)
        assertTrue(pairBodies.single().contains("ABCDEFGHJKMNPQRS"))
    }

    @Test fun theRealClientNeverSignsInToAServerWithoutKe2e() = runBlocking {
        // the stand-in has no GET /api/e2e/info: too old for this app (spec 5.18), and nothing else was asked in the clear
        try {
            KeySignIn.connect(PairingClient(OkHttpClient()), url, "the-access-key", "Desktop · Kelo")
            fail("a server without KE2E must be refused")
        } catch (e: ApiError) {
            assertEquals(PairingClient.CODE_SERVER_OLD, e.code)
        }
        assertTrue(pairBodies.isEmpty())
        val asked = (1..server.requestCount).map { server.takeRequest().path }
        assertEquals(listOf("/api/e2e/info"), asked)
    }
}
