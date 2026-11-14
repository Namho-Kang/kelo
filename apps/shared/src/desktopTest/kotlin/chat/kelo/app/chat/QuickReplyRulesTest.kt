package chat.kelo.app.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers to write under an agent message: which open a text box, when sending is allowed and what is sent (desktop runs it too). */
class QuickReplyRulesTest {
    private val form = ReplySet(listOf(
        ReplyQuestion("체중 (현재 → 목표)", listOf("직접 입력할게요", "말하고 싶지 않아요"), typed = listOf("직접 입력할게요"), hint = "현재 체중과 목표 체중"),
        ReplyQuestion("기간", listOf("1개월", "3개월", "6개월")),
        ReplyQuestion("식단 제한", listOf("없어요", "있어요 (직접 입력)", "말하고 싶지 않아요")),   // an older server: no "typed"
    ))

    @Test fun theServersMarkAndTheWordsBothOpenATextBox() {
        assertTrue(QuickReplyRules.isTyped(form.questions[0], "직접 입력할게요"))
        assertTrue(QuickReplyRules.isTyped(form.questions[2], "있어요 (직접 입력)"))
        assertFalse(QuickReplyRules.isTyped(form.questions[2], "없어요"))
        assertTrue(QuickReplyRules.isTyped(ReplyQuestion("Sport", listOf("Run", "Anything"), typed = listOf("Anything")), "Anything"))
        for (l in listOf("Other", "기타", "Other (please specify)", "Something else…", "その他", "其他", "Otro", "Свой вариант", "Tự nhập", "I'll type it"))
            assertTrue(l, QuickReplyRules.asksTyping(l))
        for (l in listOf("말하고 싶지 않아요", "없어요", "Rather not say", "Prefiero no decirlo", "Otra vez", "1개월", "Another time", "직접 할게요"))
            assertFalse(l, QuickReplyRules.asksTyping(l))
    }

    @Test fun aTypedAnswerIsSentInsteadOfItsLabelAndNeverEmpty() {
        val picks = mapOf(0 to "직접 입력할게요", 1 to "3개월", 2 to "있어요 (직접 입력)")
        assertEquals(setOf(0, 2), QuickReplyRules.writing(form, picks))
        assertFalse(QuickReplyRules.canSend(form, picks, emptyMap()))
        assertFalse(QuickReplyRules.canSend(form, picks, mapOf(0 to "80 → 70kg", 2 to "  ")))
        val typed = mapOf(0 to " 80kg → 70kg ", 2 to "유당불내증\n견과류")
        assertTrue(QuickReplyRules.canSend(form, picks, typed))
        assertEquals("체중 (현재 → 목표): 80kg → 70kg\n기간: 3개월\n식단 제한: 유당불내증 견과류", QuickReplyRules.compose(form, picks, typed))
        // the other answers stay as they were; a question left alone is left out
        assertEquals("기간: 1개월\n식단 제한: 없어요", QuickReplyRules.compose(form, mapOf(1 to "1개월", 2 to "없어요"), mapOf(0 to "ignored")))
        assertTrue(QuickReplyRules.canSend(form, mapOf(1 to "1개월"), emptyMap()))
        assertFalse(QuickReplyRules.canSend(form, emptyMap(), emptyMap()))
    }

    @Test fun aLoneQuestionSendsOnTapUnlessTheAnswerIsOneToWrite() {
        val one = ReplySet(listOf(ReplyQuestion("Diet", listOf("None", "Other"))))
        assertTrue(QuickReplyRules.sendsOnTap(one, one.questions[0], "None"))
        assertFalse(QuickReplyRules.sendsOnTap(one, one.questions[0], "Other"))
        assertFalse(QuickReplyRules.showsSend(one, emptyMap()))
        assertTrue(QuickReplyRules.showsSend(one, mapOf(0 to "Other")))
        assertEquals("gluten free", QuickReplyRules.compose(one, mapOf(0 to "Other"), mapOf(0 to "gluten free")))
        assertTrue(QuickReplyRules.showsSend(form, emptyMap()))
        assertFalse(QuickReplyRules.sendsOnTap(form, form.questions[1], "1개월"))
    }

    @Test fun theBoxHintComesFromTheServerElseTheQuestion() {
        assertEquals("현재 체중과 목표 체중", QuickReplyRules.placeholder(form.questions[0]))
        assertEquals("식단 제한", QuickReplyRules.placeholder(form.questions[2]))
        assertNull(QuickReplyRules.placeholder(ReplyQuestion("", listOf("a", "Other"))))
    }

    @Test fun readsTheNewFieldsAndAnOlderServer() {
        val json = Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString<ReplySet>("""{"questions":[{"question":"Q","options":["a","Other"],"typed":["Other"],"hint":"h"}],"goal_id":2}""")
        assertEquals(listOf("Other"), s.questions[0].typed)
        assertEquals("h", s.questions[0].hint)
        val old = json.decodeFromString<ReplySet>("""{"questions":[{"question":"Q","options":["a","b"]}]}""")
        assertEquals(emptyList<String>(), old.questions[0].typed)
    }

    @Test fun everyChoiceGetsAnOtherBoxUnlessItHasOneOrIsALoneButton() {
        val q = ReplyQuestion("When", listOf("Morning", "Evening"))
        assertEquals(listOf("Morning", "Evening", QuickReplyRules.OTHER), QuickReplyRules.choices(q))
        assertEquals(form.questions[0].options, QuickReplyRules.choices(form.questions[0]))      // its own "직접 입력할게요"
        assertEquals(listOf("Continue"), QuickReplyRules.choices(ReplyQuestion("", listOf("Continue"))))
        assertEquals(emptyList<String>(), QuickReplyRules.choices(ReplyQuestion("Day", listOf("a", "b"), kind = "date")))
        val one = ReplySet(listOf(q))
        assertTrue(QuickReplyRules.isTyped(q, QuickReplyRules.OTHER))
        assertFalse(QuickReplyRules.sendsOnTap(one, q, QuickReplyRules.OTHER))
        assertTrue(QuickReplyRules.showsSend(one, mapOf(0 to QuickReplyRules.OTHER)))
        assertFalse(QuickReplyRules.canSend(one, mapOf(0 to QuickReplyRules.OTHER), emptyMap()))
        assertEquals("Late night", QuickReplyRules.compose(one, mapOf(0 to QuickReplyRules.OTHER), mapOf(0 to " Late night ")))
        assertEquals(listOf(ReplyAnswer(0, "Late night")), QuickReplyRules.answers(one, mapOf(0 to QuickReplyRules.OTHER), mapOf(0 to "Late night")))
    }

    @Test fun fieldsAreSentInWordsWithTheirPlainValuesAlongside() {
        val set = ReplySet(listOf(
            ReplyQuestion("Which", listOf("x", "y")),
            ReplyQuestion("Days", kind = "weekdays_time"),
            ReplyQuestion("How many", kind = "number", min = 1.0, max = 5.0),
        ))
        assertFalse(QuickReplyRules.sendsAtOnce(ReplySet(listOf(set.questions[1]))))   // a lone field still has a send button
        assertTrue(QuickReplyRules.showsSend(ReplySet(listOf(set.questions[1])), emptyMap()))
        assertFalse(QuickReplyRules.canSend(set, emptyMap(), emptyMap(), mapOf(2 to "")))
        assertFalse(QuickReplyRules.canSend(set, emptyMap(), emptyMap(), mapOf(2 to "9")))          // out of range
        assertTrue(QuickReplyRules.canSend(set, emptyMap(), emptyMap(), mapOf(2 to "3")))
        val values = mapOf(1 to "wed,mon 12:00", 2 to "3")
        assertEquals(listOf(ReplyAnswer(0, "x"), ReplyAnswer(1, "mon,wed 12:00"), ReplyAnswer(2, "3")),
            QuickReplyRules.answers(set, mapOf(0 to "x"), emptyMap(), values))
        assertEquals("Which: x\nDays: <mon,wed 12:00>\nHow many: <3>",
            QuickReplyRules.compose(set, mapOf(0 to "x"), emptyMap(), values) { _, plain -> "<$plain>" })
    }

    @Test fun anOptionCanSendAWholeSentence() {
        val lone = ReplySet(listOf(ReplyQuestion("Pick one", listOf("A", "B"), says = listOf("Go with A, please", "Go with B"))))
        assertEquals("Go with A, please", QuickReplyRules.compose(lone, mapOf(0 to "A")))
        assertEquals(listOf(ReplyAnswer(0, "A")), QuickReplyRules.answers(lone, mapOf(0 to "A")))

        val two = ReplySet(listOf(
            ReplyQuestion("Choice", listOf("Plan A", "Plan B"), says = listOf("Let's go with Plan A", "")),
            ReplyQuestion("Payment", listOf("Card", "Transfer")),
        ))
        assertEquals("Let's go with Plan A\nPayment: Card", QuickReplyRules.compose(two, mapOf(0 to "Plan A", 1 to "Card")))
        assertEquals(listOf(ReplyAnswer(0, "Plan A"), ReplyAnswer(1, "Card")), QuickReplyRules.answers(two, mapOf(0 to "Plan A", 1 to "Card")))

        val withTyped = ReplySet(listOf(
            ReplyQuestion("Plan", listOf("Plan A", "Other"), says = listOf("Plan A sentence", "Other sentence"), typed = listOf("Other")),
        ))
        assertEquals("custom plan", QuickReplyRules.compose(withTyped, mapOf(0 to "Other"), typed = mapOf(0 to "custom plan")))
        assertEquals(listOf(ReplyAnswer(0, "custom plan")), QuickReplyRules.answers(withTyped, mapOf(0 to "Other"), typed = mapOf(0 to "custom plan")))

        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<ReplySet>("""{"questions":[{"question":"Q","options":["a","b"]}]}""")
        assertEquals(emptyList<String>(), decoded.questions[0].says)
        assertEquals("a", QuickReplyRules.compose(decoded, mapOf(0 to "a")))
    }
}
