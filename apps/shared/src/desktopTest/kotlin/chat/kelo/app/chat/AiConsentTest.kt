package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiConsentTest {
    private fun err(status: Int, body: String) =
        ApiError(status, "x", detailJson = Json.parseToJsonElement(body).jsonObject)

    @Test fun reads_the_428_answer() {
        val need = AiConsent.of(err(428, """{"code":"ai_consent_required","company":"Google","privacy_url":"https://kelo.chat/privacy"}"""))
        assertEquals(AiConsentNeed("Google", "https://kelo.chat/privacy"), need)
    }

    @Test fun a_missing_company_or_url_still_asks() {
        assertEquals(AiConsentNeed("", AiConsent.PRIVACY_URL), AiConsent.of(err(428, """{"code":"ai_consent_required","company":null}""")))
    }

    @Test fun other_answers_are_not_the_question() {
        assertNull(AiConsent.of(err(428, """{"code":"consent_required"}""")))
        assertNull(AiConsent.of(err(409, """{"code":"ai_consent_required"}""")))
        assertNull(AiConsent.of(IllegalStateException("x")))
    }

    @Test fun where_chats_go() {
        assertEquals(ChatsGo.To("Google"), AiConsent.chatsGo("google", "Google"))
        assertEquals(ChatsGo.To("llm.example.com"), AiConsent.chatsGo("custom", " llm.example.com "))
        assertEquals(ChatsGo.Local, AiConsent.chatsGo("custom", null))
        assertEquals(ChatsGo.Local, AiConsent.chatsGo("lmstudio", ""))
        assertNull(AiConsent.chatsGo("anthropic", null))   // an older server that does not say
        assertNull(AiConsent.chatsGo("", "Anthropic"))      // the server's default: no provider chosen here
    }

    @Test fun never_prints_the_text() {
        val a = ConsentAsk(AiConsentNeed("OpenAI", AiConsent.PRIVACY_URL), "my secret plans", emptyList(), true, false, null)
        assertEquals("ConsentAsk(OpenAI)", a.toString())
    }
}
