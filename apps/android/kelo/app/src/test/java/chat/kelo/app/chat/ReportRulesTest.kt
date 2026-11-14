package chat.kelo.app.chat

import chat.kelo.app.my.OperatorReportRules
import chat.kelo.app.my.ReceivedReport
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRulesTest {
    private fun user(k: String, t: String) = ChatItem.Msg(k, mine = true, text = t)
    private fun reply(k: String, t: String) = ChatItem.Msg(k, mine = false, text = t)

    private val items = listOf(user("u1", "첫 질문"), reply("r1", "첫 답"), user("u2", "두 번째 질문"),
        ChatItem.Tool("t", "t1", "web_search", ToolState.OK), reply("r2", "**굵은** 답<message_break/>둘째 말풍선"))

    @Test fun theReplyIsTheWholeMessageWithoutMarkdownMarks() {
        assertEquals("굵은 답\n\n둘째 말풍선", ReportRules.replyText(items, "r2"))
        assertNull(ReportRules.replyText(items, "u2"))   // the user's own message is not reported
        assertNull(ReportRules.replyText(items, "nope"))
    }

    @Test fun theUserMessageBeforeIsTheOneItAnswered() {
        assertEquals("두 번째 질문", ReportRules.askedBefore(items, "r2"))
        assertEquals("첫 질문", ReportRules.askedBefore(items, "r1"))
        assertNull(ReportRules.askedBefore(listOf(reply("r", "hi")), "r"))
    }

    @Test fun theBodyHoldsOnlyWhatTheSheetSays() {
        val d = ReportDraft(ReportReason.PRIVACY, comment = "  전화번호가 나왔어요 ", includeAsked = false)
        val b = ReportRules.body(d, "답", "질문", "0.1.5")
        assertEquals(setOf("reason", "reply", "comment", "app_version"), b.keys)
        assertEquals("privacy", b["reason"]!!.jsonPrimitive.content)
        assertEquals("전화번호가 나왔어요", b["comment"]!!.jsonPrimitive.content)
        // the user's message only when ticked; no empty comment
        val ticked = ReportRules.body(d.copy(includeAsked = true, comment = " "), "답", "질문", "0.1.5")
        assertEquals(setOf("reason", "reply", "asked", "app_version"), ticked.keys)
        assertEquals("질문", ticked["asked"]!!.jsonPrimitive.content)
        // long texts are cut to what the server takes
        val long = ReportRules.body(d.copy(comment = "x".repeat(5000)), "y".repeat(20_000), null, "1")
        assertEquals(ReportRules.MAX_COMMENT, long["comment"]!!.jsonPrimitive.content.length)
        assertEquals(ReportRules.MAX_REPLY, long["reply"]!!.jsonPrimitive.content.length)
    }

    @Test fun aReasonMustBeChosen() {
        assertFalse(ReportRules.canSend(ReportDraft()))
        assertTrue(ReportRules.canSend(ReportDraft(ReportReason.OTHER)))
        assertEquals(ReportReason.HARMFUL, ReportReason.of("harmful"))
        assertNull(ReportReason.of("spam"))
    }

    @Test fun operatorListHelpers() {
        assertEquals("/api/operator/reports?handled=false", OperatorReportRules.path(false))
        assertEquals("/api/operator/reports", OperatorReportRules.path(true))
        assertEquals("anthropic / claude", OperatorReportRules.modelName(ReceivedReport(provider = "anthropic", model = "claude")))
        assertNull(OperatorReportRules.modelName(ReceivedReport()))
    }
}
