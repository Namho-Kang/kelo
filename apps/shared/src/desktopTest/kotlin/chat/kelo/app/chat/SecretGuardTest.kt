package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's 409 `secret_in_message` answer and the offsets it gives (UTF-16, Kotlin's own String indices). */
class SecretGuardTest {
    private fun detail(s: String) = Json.parseToJsonElement(s).jsonObject

    private val key = "sk-ant-api03-" + "A".repeat(40)

    @Test fun parsesTheAnswer() {
        val f = SecretGuard.parse(detail("""{"code":"secret_in_message","spans":[{"start":12,"end":63,"kind":"api_key"}],
            "placeholder":"[hidden key]","target":{"type":"provider","id":"anthropic"},"label":"Anthropic"}"""))!!
        assertEquals(listOf(SecretSpan(12, 63, "api_key")), f.spans)
        assertEquals("[hidden key]", f.placeholder)
        assertEquals(SecretTarget("provider", "anthropic"), f.target)
        assertEquals("Anthropic", f.label)
    }

    @Test fun otherAnswersAndOddFieldsAreNotASecretSheet() {
        assertNull(SecretGuard.parse(null))
        assertNull(SecretGuard.parse(detail("""{"code":"something_else"}""")))
        assertNull(SecretGuard.of(ApiError(409, "conflict")))
        assertNull(SecretGuard.of(ApiError(400, "x", detailJson = detail("""{"code":"secret_in_message"}"""))))
        val f = SecretGuard.of(ApiError(409, "HTTP 409", detailJson = detail(
            """{"code":"secret_in_message","spans":[{"start":5,"end":2},{"start":"x"},{"start":30,"end":40}],"target":{"type":"spaceship"}}""",
        )))!!
        assertEquals(listOf(SecretSpan(30, 40, "token")), f.spans)   // bad spans dropped, a missing kind is a token
        assertEquals(SecretTarget(SecretTarget.VAULT, ""), f.target)                 // an unknown target is the Keychain
        assertEquals("", f.label)
    }

    @Test fun valuesAreCutByUtf16OffsetsAfterEmojiAndKorean() {
        val before = "내 키는 👩‍💻 여기: "
        val text = before + key + " 저장해줘"
        val start = before.length   // UTF-16 units: the emoji counts as several
        val values = SecretGuard.values(text, listOf(SecretSpan(start, start + key.length, "api_key")))
        assertEquals(listOf(key), values)
        // a span past the end is skipped, never an exception
        assertTrue(SecretGuard.values(text, listOf(SecretSpan(start, text.length + 5, "api_key"))).isEmpty())
    }

    @Test fun composerSpansFollowTheTrimAndSkipAttachmentLines() {
        val composer = "  \n한국어 🙂 " + key + "  "
        val sent = composer.trim() + "\n\n📎 report.pdf"
        val at = sent.indexOf(key)
        val spans = listOf(SecretSpan(at, at + key.length, "api_key"), SecretSpan(sent.length - 5, sent.length, "token"))
        val moved = SecretGuard.composerSpans(composer, spans)
        assertEquals(1, moved.size)
        assertEquals(key, composer.substring(moved[0].start, moved[0].end))
    }

    @Test fun replaceGoesFromTheLastSpanSoOffsetsHold() {
        val a = "ghp_" + "b".repeat(36)
        val text = "🙂 첫째 $key 둘째 $a 끝"
        val spans = listOf(SecretSpan(text.indexOf(key), text.indexOf(key) + key.length, "api_key"), SecretSpan(text.indexOf(a), text.indexOf(a) + a.length, "token"))
        assertEquals("🙂 첫째 [x] 둘째 [x] 끝", SecretGuard.replace(text, spans, "[x]"))
        assertEquals("🙂 첫째 (saved) 둘째 $a 끝", SecretGuard.replace(text, spans, "(saved)", value = key))
    }

    @Test fun usesStartWithTheServersTarget() {
        fun found(type: String, id: String, kind: String) = SecretFound(listOf(SecretSpan(0, 10, kind)), "", SecretTarget(type, id))
        assertEquals(SecretUse.AI_KEY, SecretGuard.uses(found("provider", "anthropic", "api_key")).first())
        assertEquals(SecretUse.CONNECTOR_KEY, SecretGuard.uses(found("connector", "slack", "token")).first())
        assertEquals(listOf(SecretUse.CARD), SecretGuard.uses(found("wallet", "", "card")))
        assertEquals(SecretUse.LOGIN, SecretGuard.uses(found("vault", "", "password")).first())
        assertEquals(SecretUse.SERVICE_KEY, SecretGuard.uses(found("vault", "", "token")).first())
        assertEquals("api.github.com", SecretGuard.hostGuess("GitHub"))
        assertEquals("api-github-com", SecretGuard.nameFor("api.github.com"))
    }

    @Test fun theHandOffKeepsTheValueOnlyBrieflyAndNeverPrintsIt() {
        var now = 0L
        val h = SecretHandoff(clock = { now }, ttlMs = 1000)
        h.offer(listOf("4111111111111111"))
        assertFalse(h.toString().contains("4111"))
        assertEquals("4111111111111111", h.peek())
        now = 2000
        assertNull(h.peek())                       // too old: gone
        h.offer(listOf("4111111111111111"))
        h.clear()
        assertNull(h.peek())                       // the form closed: gone
        h.offer(listOf("4111111111111111"))
        h.kept()
        assertNull(h.peek())
        assertTrue(h.takeSaved())
        assertFalse(h.takeSaved())
    }
}
