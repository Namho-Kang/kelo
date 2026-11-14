package chat.kelo.app.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonCardTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun storedMessageWithComparisonDecodesAndToChatItemKeepsIt() {
        val raw = """
        {
            "id": 101,
            "role": "assistant",
            "content": "비교 결과입니다.",
            "created_at": 1730000000.0,
            "comparison": {
                "title": "요금 비교",
                "basis": "월 요금 기준 · 3년 약정",
                "rows": ["월 요금", "사은품"],
                "options": [
                    {"name": "KT", "values": ["38,500원", "45만원"], "says": "KT로 진행해줘", "recommended": true},
                    {"name": "SK", "values": ["37,400원", "40만원"], "says": "", "recommended": false}
                ],
                "note": "결합 할인 확인",
                "open": true
            }
        }
        """
        val m = json.decodeFromString<StoredMessage>(raw)
        val card = m.comparison
        assertNotNull(card)
        assertEquals("요금 비교", card!!.title)
        assertEquals("월 요금 기준 · 3년 약정", card.basis)
        assertEquals(listOf("월 요금", "사은품"), card.rows)
        assertEquals(2, card.options.size)
        assertEquals("KT", card.options[0].name)
        assertTrue(card.options[0].recommended)
        assertEquals("KT로 진행해줘", card.options[0].says)
        assertEquals("SK", card.options[1].name)
        assertEquals(false, card.options[1].recommended)
        assertEquals("결합 할인 확인", card.note)
        assertTrue(card.open)

        val item = toChatItem(m)
        assertEquals(card, item.comparison)
    }

    @Test
    fun comparisonOptionWithBlankSaysFallsBackToSentence() {
        val withSays = ComparisonOption(name = "KT", values = listOf("38,500원"), says = "KT로 진행할게", recommended = true)
        assertEquals("KT로 진행할게", ComparisonRules.choice(withSays) { "Go with $it" })

        val blankSays = ComparisonOption(name = "SK", values = listOf("37,400원"), says = "", recommended = false)
        assertEquals("Go with SK", ComparisonRules.choice(blankSays) { "Go with $it" })

        val spaceSays = ComparisonOption(name = "LG", values = listOf("36,000원"), says = "   ", recommended = false)
        assertEquals("Go with LG", ComparisonRules.choice(spaceSays) { "Go with $it" })
    }

    @Test
    fun turnEventComparisonParses() {
        val ev = TurnEvent.parse(Json.parseToJsonElement("""{"type":"comparison"}"""))
        assertEquals(TurnEvent.Comparison, ev)
    }
}
