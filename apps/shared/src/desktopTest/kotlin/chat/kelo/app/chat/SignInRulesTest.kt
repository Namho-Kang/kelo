package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun offeredForSignInProvidersThatAreNotSignedIn() {
        assertTrue(SignInRules.offered(signin = true, signedIn = false))
        assertTrue(SignInRules.offered(signin = true, signedIn = null))      // unknown: the user can still try
        assertFalse(SignInRules.offered(signin = true, signedIn = true))
        assertFalse(SignInRules.offered(signin = false, signedIn = false))   // an API-key provider, or an older server
    }

    @Test
    fun theServersAnswersParse() {
        val device = json.decodeFromString<SignInSession>(
            """{"session":"s1","provider":"openai-codex","kind":"device_code","url":"https://auth.openai.com/codex/device",""" +
                """"user_code":"ABCD-1234","expires_in":900,"state":"pending"}""",
        )
        assertTrue(SignInRules.isDeviceCode(device))
        assertEquals("ABCD-1234", device.userCode)
        assertEquals(900L, device.expiresIn)
        val paste = json.decodeFromString<SignInSession>("""{"session":"s2","kind":"url_code","url":"https://claude.com/x","expires_in":600,"state":"pending"}""")
        assertFalse(SignInRules.isDeviceCode(paste))
        assertNull(paste.userCode)
        val done = json.decodeFromString<SignInSession>("""{"session":"s2","kind":"url_code","url":"https://x","state":"done","account":"me@x.y"}""")
        assertTrue(SignInRules.finished(done))
        assertEquals("me@x.y", done.account)
        assertTrue(SignInRules.ended(done.copy(state = "expired")))
        assertTrue(SignInRules.ended(done.copy(state = "failed")))
        assertFalse(SignInRules.ended(paste))
    }

    @Test
    fun aPasteIsOneLineWithoutSpaces() {
        assertTrue(SignInRules.pasteReady("  abc#def "))
        assertTrue(SignInRules.pasteReady("https://antigravity.google/oauth-callback?code=4/0A&scope=x"))
        assertFalse(SignInRules.pasteReady(""))
        assertFalse(SignInRules.pasteReady("two words"))
        assertFalse(SignInRules.pasteReady("x".repeat(5000)))
    }

    @Test
    fun errorsMapToWhatTheUserSees() {
        assertEquals(SignInRules.Problem.CONSENT, SignInRules.problem(ApiError(428, "consent_required:openai-codex")))
        val already = ApiError(409, "already_signed_in: Already signed in. Sign out first to use another account")
        assertEquals(SignInRules.Problem.ALREADY, SignInRules.problem(already))
        assertEquals("Already signed in. Sign out first to use another account", SignInRules.readable(already))   // no machine word
        assertEquals(SignInRules.Problem.OFF, SignInRules.problem(ApiError(503, "The Claude container on the server is off…")))
        assertEquals(SignInRules.Problem.FAILED, SignInRules.problem(ApiError(400, "The code was not accepted.")))
        assertEquals("", SignInRules.readable(ApiError(ApiError.NO_RESPONSE, "network")))
    }
}
