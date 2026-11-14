package chat.kelo.app.chat

import chat.kelo.app.agent.PendingApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAsksTest {
    private fun pa(
        id: String, at: Double = 1.0, source: String = "worker", worker: String? = "w1", unattended: Boolean = true,
        canRemember: Boolean = true, canRememberSchedule: Boolean = false, kind: String = "", check: CheckInfo? = null,
    ) = PendingApproval(
        TurnEvent.ApprovalRequest(id, "browser_click", JsonObject(emptyMap()), "Book it", "Click", kind = kind, check = check),
        "shop.example.com", canRemember, at, unattended, 7L, canRememberSchedule, source, worker, "Find tickets",
    )

    @Test fun theServersNewFieldsAreReadAndAnOlderServerStillWorks() {
        val o = Json.parseToJsonElement(
            """{"type":"approval_request","approval_id":"a1","name":"send","source":"worker","worker_id":"w9","title":"Job","asked_at":5}""",
        ) as JsonObject
        val p = PendingApproval.parse(o)!!
        assertEquals("worker", p.source); assertEquals("w9", p.workerId); assertEquals("Job", p.title)
        val old = PendingApproval.parse(Json.parseToJsonElement("""{"type":"approval_request","approval_id":"a2","name":"send"}""") as JsonObject)!!
        assertEquals("", old.source); assertNull(old.workerId); assertEquals("", old.title)
    }

    @Test fun originSaysWhatAsked() {
        assertEquals(AskOrigin.JOB, ChatAsks.origin(pa("a")))
        assertEquals(AskOrigin.JOB, ChatAsks.origin(pa("a", worker = null)))                       // source alone
        assertEquals(AskOrigin.SCHEDULE, ChatAsks.origin(pa("a", source = "schedule:3", worker = null)))
        assertEquals(AskOrigin.AUTO, ChatAsks.origin(pa("a", source = "background", worker = null)))
        assertEquals(AskOrigin.AUTO, ChatAsks.origin(pa("a", source = "", worker = null)))         // an older server: only "unattended"
        assertEquals(AskOrigin.CHAT, ChatAsks.origin(pa("a", source = "chat", worker = null, unattended = false)))
    }

    @Test fun mergeAddsNewOnesDropsOnesAnsweredElsewhereAndKeepsTheOldestFirst() {
        val shown = ChatAsks.merge(emptyList(), listOf(pa("b", at = 2.0), pa("a", at = 1.0)), "k1")
        assertEquals(listOf("a", "b"), shown.map { it.id })
        // "a" was answered on the 작업 page: it leaves; "c" is new
        val next = ChatAsks.merge(shown, listOf(pa("b", at = 2.0), pa("c", at = 3.0)), "k1")
        assertEquals(listOf("b", "c"), next.map { it.id })
    }

    @Test fun anAnswerOnItsWayAndAnEndedLineStayUntilTheChatMovesOn() {
        var asks = ChatAsks.merge(emptyList(), listOf(pa("a"), pa("b", at = 2.0)), "k1")
        asks = ChatAsks.working(asks, "a", true)
        asks = ChatAsks.ended(asks, "b", AskEnd.DENIED, "k1")
        val gone = ChatAsks.merge(asks, emptyList(), "k1")
        assertEquals(listOf("a", "b"), gone.map { it.id })            // "a" in flight, "b" answered here
        // a new row in the chat: ended lines go
        assertTrue(ChatAsks.merge(ChatAsks.ended(gone, "a", AskEnd.ALLOWED, "k1"), emptyList(), "k2").isEmpty())
        // an ended one the server still lists (the answer just landed) keeps its line, not a fresh card
        val still = ChatAsks.merge(ChatAsks.ended(asks, "a", AskEnd.ALLOWED, "k1"), listOf(pa("a")), "k1")
        assertEquals(AskEnd.ALLOWED, still.first { it.id == "a" }.end)
    }

    @Test fun visibleLeavesOutTheFollowedRepliesOwnCardAndOldLines() {
        val items = listOf<ChatItem>(
            ChatItem.Msg("k1", mine = true, text = "hi"),
            ChatItem.Approval("k2", "a", "send", "", null),
        )
        val asks = listOf(ChatAsk(pa("a")), ChatAsk(pa("b")), ChatAsk(pa("c"), end = AskEnd.ALLOWED, endedAfter = "k1"),
            ChatAsk(pa("d"), end = AskEnd.DENIED, endedAfter = "k2"))
        assertEquals(listOf("b", "d"), ChatAsks.visible(asks, items).map { it.id })
    }

    @Test fun anAllowIsRememberedOnlyWhenTheCardOfferedIt() {
        assertEquals(AskEnd.ALWAYS, ChatAsks.allowEnd(pa("a"), rememberSite = true, rememberSchedule = false))
        assertEquals(AskEnd.ALLOWED, ChatAsks.allowEnd(pa("a", canRemember = false), rememberSite = true, rememberSchedule = false))
        assertEquals(AskEnd.KEPT_FOR_SCHEDULE, ChatAsks.allowEnd(pa("a", canRememberSchedule = true), false, true))
        assertEquals(AskEnd.ALLOWED, ChatAsks.allowEnd(pa("a"), false, true))
    }

    @Test fun progressedOutcomeAndAnswerEnd() {
        assertEquals(AskEnd.PROGRESSED, ChatAsks.resolvedEnd("progressed"))
        val v = pa("v", kind = PendingApproval.HUMAN_CHECK, check = CheckInfo(kind = "verification", step = "approve"))
        assertEquals(AskEnd.PROGRESSED, ChatAsks.answerEnd(v, approve = true, rememberSite = false, rememberSchedule = false))
        val person = pa("h", kind = PendingApproval.HUMAN_CHECK, check = CheckInfo(kind = "interstitial"))
        assertEquals(AskEnd.SOLVED, ChatAsks.answerEnd(person, approve = true, rememberSite = false, rememberSchedule = false))
    }

    @Test fun theListIsAskedForWhenThePollsCountsChangeAndSkippedWhenNothingWaits() {
        assertNull(ChatAsks.pollKey(null))
        assertEquals(2 to 5, ChatAsks.pollKey(AgentStatus(pendingApprovals = 2, unseenNotifications = 5)))
        assertTrue(ChatAsks.skipFetch(AgentStatus(pendingApprovals = 0), emptyList()))
        assertEquals(false, ChatAsks.skipFetch(AgentStatus(pendingApprovals = 1), emptyList()))
        assertEquals(false, ChatAsks.skipFetch(AgentStatus(pendingApprovals = 0), listOf(ChatAsk(pa("a")))))   // an open card: check it
        assertEquals(false, ChatAsks.skipFetch(null, emptyList()))
    }
}
