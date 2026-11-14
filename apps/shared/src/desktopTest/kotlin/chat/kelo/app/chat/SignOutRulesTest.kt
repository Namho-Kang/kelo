package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun offeredOnlyWhenSignedInNow() {
        assertTrue(SignOutRules.offered(true))
        assertFalse(SignOutRules.offered(false))
        assertFalse(SignOutRules.offered(null))
    }

    @Test
    fun theSavedMainModelIsNotSignedOut() {
        assertTrue(SignOutRules.isMain("openai-codex", "openai-codex"))
        assertFalse(SignOutRules.isMain("openai-codex", "antigravity-oauth"))
        assertFalse(SignOutRules.isMain("openai-codex", ""))
    }

    @Test
    fun answersMapToWhatTheUserSees() {
        assertEquals(SignOutRules.Outcome.DONE, SignOutRules.outcome(SignOutResult(ok = true, removed = true), null))
        assertEquals(SignOutRules.Outcome.DONE_FALLBACK_CLEARED, SignOutRules.outcome(SignOutResult(ok = true, fallbackCleared = true), null))
        assertEquals(SignOutRules.Outcome.DONE_ENV_LEFT, SignOutRules.outcome(SignOutResult(ok = true, stillSignedIn = true), null))
        assertEquals(SignOutRules.Outcome.MAIN_MODEL, SignOutRules.outcome(null, ApiError(409, "main_model_uses_login")))
        assertEquals(SignOutRules.Outcome.DONE, SignOutRules.outcome(null, ApiError(404, "not signed in")))
        assertEquals(SignOutRules.Outcome.FAILED, SignOutRules.outcome(null, ApiError(502, "sidecar")))
        assertTrue(SignOutRules.done(SignOutRules.Outcome.DONE_FALLBACK_CLEARED))
        assertFalse(SignOutRules.done(SignOutRules.Outcome.MAIN_MODEL))
        assertFalse(SignOutRules.done(SignOutRules.Outcome.FAILED))
    }

    @Test
    fun providerRowsReadTheSignInState() {
        val rows = json.decodeFromString<List<ProviderInfo>>(
            """[{"name":"openai-codex","signed_in":true,"account":"me@example.com"},{"name":"openai"}]""",
        )
        assertEquals(true, rows[0].signedIn)
        assertEquals("me@example.com", rows[0].account)
        assertNull(rows[1].signedIn)
        val answer = json.decodeFromString<SignOutResult>("""{"ok":true,"name":"x","removed":true,"still_signed_in":false,"fallback_cleared":true}""")
        assertTrue(answer.fallbackCleared && answer.removed && !answer.stillSignedIn)
    }
}
