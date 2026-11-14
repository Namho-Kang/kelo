package chat.kelo.app.chat

import chat.kelo.app.agent.LiveRules
import chat.kelo.app.agent.PendingApproval
import chat.kelo.app.notify.OpenTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A human check handed to the user: the card's data, how it folds, the live view taking the tab over, and approval titles. */
class HandoverTest {
    private val list = Json.parseToJsonElement(
        """{"items":[{"type":"approval_request","kind":"human_check","approval_id":"h1","name":"browser_check","label":"Human check",
           "ask":"shop.example.com wants to confirm a person is there","site":"shop.example.com","tab":"task:ab12","asked_at":10,
           "unattended":true,"source":"worker","worker_id":"w1","title":"Find tickets","check":{"vendor":"cloudflare"}},
           {"type":"approval_request","approval_id":"a1","name":"browser_login","label":"Signing in","ask":"Sign in?","asked_at":5}],
           "resolved":[{"approval_id":"h0","kind":"human_check","outcome":"solved"},{"approval_id":"","outcome":"x"}]}""",
    )

    @Test fun aHumanCheckIsReadFromThePendingList() {
        val items = PendingApproval.parseList(list)
        val h = items.first { it.id == "h1" }
        assertTrue(h.isHumanCheck); assertEquals("task:ab12", h.tab); assertEquals("shop.example.com", h.site)
        val a = items.first { it.id == "a1" }
        assertFalse(a.isHumanCheck); assertEquals("owner", a.tab); assertEquals("Sign in?", a.event.ask)
        assertEquals(mapOf("h0" to "solved"), PendingApproval.parseResolved(list))
        assertEquals(emptyMap<String, String>(), PendingApproval.parseResolved(Json.parseToJsonElement("""{"items":[]}""")))   // an older server
    }

    @Test fun theStreamsHandOverEventIsKnown() {
        val ev = TurnEvent.parse(Json.parseToJsonElement("""{"type":"human_check","approval_id":"h1","state":"solved","site":"s.example","tab":"owner"}"""))
        assertEquals(TurnEvent.HumanCheck("h1", "solved", "s.example", "owner"), ev)
    }

    @Test fun aSignInVerificationIsToldApartFromAPersonCheck() {
        val items = PendingApproval.parseList(Json.parseToJsonElement(
            """{"items":[{"type":"approval_request","kind":"human_check","approval_id":"v1","name":"browser_check","site":"accounts.kakao.com",
               "tab":"owner","check":{"vendor":"kakao","name":"휴대폰에서 로그인을 승인해 주세요","kind":"verification"}},
               {"type":"approval_request","kind":"human_check","approval_id":"h1","name":"browser_check","site":"shop.example.com",
               "check":{"vendor":"cloudflare","name":"Cloudflare","kind":"interstitial"}},
               {"type":"approval_request","kind":"human_check","approval_id":"h2","name":"browser_check"}]}""",
        ))
        val v = items.first { it.id == "v1" }
        assertTrue(v.isHumanCheck); assertTrue(v.isVerification)
        assertEquals("휴대폰에서 로그인을 승인해 주세요", v.event.check?.name)
        assertFalse(items.first { it.id == "h1" }.isVerification)
        assertFalse(items.first { it.id == "h2" }.isVerification)   // an older server sends no check
        // a plain approval with a "check" object is still no human check
        val a = PendingApproval.parseList(Json.parseToJsonElement(
            """{"items":[{"type":"approval_request","approval_id":"a1","name":"browser_login","check":{"kind":"verification"}}]}""")).single()
        assertFalse(a.isVerification)
        // the stream's human_check event carries it too
        val ev = TurnEvent.parse(Json.parseToJsonElement(
            """{"type":"human_check","approval_id":"v1","state":"waiting","site":"accounts.kakao.com","tab":"owner",
               "check":{"name":"로그인 인증 코드를 화면에 입력해 주세요","kind":"verification"}}""")) as TurnEvent.HumanCheck
        assertTrue(ev.check!!.isVerification)
    }

    @Test fun aSignInVerificationApproveStepIsToldApart() {
        val check = CheckInfo.parse(Json.parseToJsonElement("""{"kind":"verification","step":"approve"}"""))!!
        assertTrue(check.isApproveStep)
        val codeCheck = CheckInfo.parse(Json.parseToJsonElement("""{"kind":"verification","step":"code"}"""))!!
        assertFalse(codeCheck.isApproveStep)
        val otherCheck = CheckInfo.parse(Json.parseToJsonElement("""{"kind":"","step":"approve"}"""))!!
        assertFalse(otherCheck.isApproveStep)
    }

    @Test fun progressedOutcomeAndAnswerEnd() {
        assertEquals(AskEnd.PROGRESSED, ChatAsks.resolvedEnd("progressed"))
        val v = PendingApproval(
            TurnEvent.ApprovalRequest(
                "v1", "browser_check", JsonObject(emptyMap()), "Confirm sign-in", "Sign-in",
                kind = "human_check", check = CheckInfo(vendor = "kakao", name = "Approve on phone", kind = "verification", step = "approve"),
            ),
            "accounts.kakao.com", false, 1.0, false, 7L, false, "chat", null, "Sign in",
        )
        assertEquals(AskEnd.PROGRESSED, ChatAsks.answerEnd(v, approve = true, rememberSite = false, rememberSchedule = false))
        val person = PendingApproval(
            TurnEvent.ApprovalRequest(
                "h1", "browser_check", JsonObject(emptyMap()), "Human check", "Check",
                kind = "human_check", check = CheckInfo(vendor = "cloudflare", name = "Cloudflare", kind = "interstitial"),
            ),
            "shop.example.com", false, 1.0, false, 7L, false, "chat", null, "Check",
        )
        assertEquals(AskEnd.SOLVED, ChatAsks.answerEnd(person, approve = true, rememberSite = false, rememberSchedule = false))
    }

    @Test fun aCardSolvedInTheLiveViewFoldsToSolvedAndASkipToSkipped() {
        val pending = PendingApproval.parseList(list)
        var asks = ChatAsks.merge(emptyList(), pending, "k1")
        assertEquals(listOf("a1", "h1"), asks.map { it.id })
        // the server ended it by itself (the check left the page): the card folds to its outcome and stays until the chat moves on
        asks = ChatAsks.merge(asks, pending.filter { it.id != "h1" }, "k1", mapOf("h1" to "solved"))
        assertEquals(AskEnd.SOLVED, asks.first { it.id == "h1" }.end)
        assertTrue(ChatAsks.merge(asks, emptyList(), "k2").none { it.id == "h1" })
        // ran out, or the history was cleared: a plain "gone" line; nothing known: it just leaves
        val fresh = ChatAsks.merge(emptyList(), pending, "k1")
        assertEquals(AskEnd.GONE, ChatAsks.merge(fresh, emptyList(), "k1", mapOf("h1" to "expired")).first { it.id == "h1" }.end)
        assertTrue(ChatAsks.merge(fresh, emptyList(), "k1").isEmpty())
        // answered here
        val h = pending.first { it.id == "h1" }
        assertEquals(AskEnd.SKIPPED, ChatAsks.answerEnd(h, approve = false, rememberSite = false, rememberSchedule = false))
        assertEquals(AskEnd.DENIED, ChatAsks.answerEnd(pending.first { it.id == "a1" }, false, false, false))
        assertEquals(AskEnd.ALLOWED, ChatAsks.answerEnd(pending.first { it.id == "a1" }, true, false, false))
    }

    @Test fun theLiveViewTakesTheTabOverOnceWhenAskedTo() {
        assertTrue(LiveRules.autoTake(asked = true, done = false, hasFrame = true, controlling = false, someoneElse = false, busy = false))
        assertFalse(LiveRules.autoTake(true, done = true, hasFrame = true, controlling = false, someoneElse = false, busy = false))   // once only
        assertFalse(LiveRules.autoTake(true, false, hasFrame = false, controlling = false, someoneElse = false, busy = false))       // no picture yet
        assertFalse(LiveRules.autoTake(true, false, true, controlling = false, someoneElse = true, busy = false))                    // another phone holds it
        assertFalse(LiveRules.autoTake(asked = false, done = false, hasFrame = true, controlling = false, someoneElse = false, busy = false))
        assertEquals(OpenTarget.Tab("t"), OpenTarget.decode(OpenTarget.Tab("t", control = true).encode()))   // a notification never takes control
    }

    @Test fun anApprovalTitleIsTheServersQuestionWhenItSendsOne() {
        assertEquals("로그인할까요?", ChatAsks.question(" 로그인할까요? "))
        assertNull(ChatAsks.question(""))
        val ev = TurnEvent.parse(Json.parseToJsonElement("""{"type":"approval_request","approval_id":"a","name":"browser_click","ask":"버튼을 누를까요?"}"""))
        assertEquals("버튼을 누를까요?", (ev as TurnEvent.ApprovalRequest).ask)
        val old = TurnEvent.parse(Json.parseToJsonElement("""{"type":"approval_request","approval_id":"a","name":"browser_click"}""")) as TurnEvent.ApprovalRequest
        assertEquals("", old.ask); assertEquals("", old.kind)
        val item = ChatReducer.apply(emptyList(), ev) { "k" }.single() as ChatItem.Approval
        assertEquals("버튼을 누를까요?", item.ask)
    }
}
