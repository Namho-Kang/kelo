package chat.kelo.app.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnEventParseTest {
    @Test fun parsesAPoll() {
        val poll = TurnPoll.parse(Json.parseToJsonElement("""
            {"events":[
              {"type":"thinking"},
              {"type":"text","text":"hi"},
              {"type":"tool_started","id":"t","name":"connect_service","arguments":{"service":"github"}},
              {"type":"tool_result","id":"t","name":"connect_service","output":"ok","is_error":false,"approved":null},
              {"type":"tool_result","id":"u","name":"x","output":"no","is_error":true,"approved":false},
              {"type":"approval_request","approval_id":"a1","name":"send","arguments":{"k":1}},
              {"type":"done","input_tokens":3,"output_tokens":4,"steps":2},
              {"type":"something_new"}
            ],"next":8,"done":true}
        """))
        assertEquals(8, poll.next)
        assertTrue(poll.done)
        val e = poll.events
        assertEquals(TurnEvent.Thinking, e[0])
        assertEquals(TurnEvent.Text("hi"), e[1])
        assertEquals("github", (e[2] as TurnEvent.ToolStarted).service)
        assertNull((e[3] as TurnEvent.ToolResult).approved)
        assertEquals(false, (e[4] as TurnEvent.ToolResult).approved)
        assertTrue((e[4] as TurnEvent.ToolResult).isError)
        assertNull((e[5] as TurnEvent.ApprovalRequest).detail)
        assertEquals(TurnEvent.Done(3, 4, 2), e[6])
        assertEquals(TurnEvent.Unknown("something_new"), e[7])
    }

    @Test fun parsesApprovalRequestWithSiteAndCanRemember() {
        val ev = TurnEvent.parse(Json.parseToJsonElement("""
            {
              "type": "approval_request",
              "approval_id": "a1",
              "name": "browser_login",
              "site": "accounts.kakao.com",
              "can_remember": true
            }
        """))
        val req = ev as TurnEvent.ApprovalRequest
        assertEquals("accounts.kakao.com", req.site)
        assertEquals(true, req.canRemember)

        val items = ChatReducer.apply(emptyList(), ev) { "k1" }
        val item = items.single() as ChatItem.Approval
        assertEquals("accounts.kakao.com", item.site)
        assertEquals(true, item.canRemember)

        // one with a payment object becomes canRemember = false
        val payEv = TurnEvent.parse(Json.parseToJsonElement("""
            {
              "type": "approval_request",
              "approval_id": "p1",
              "name": "confirm_purchase",
              "site": "accounts.kakao.com",
              "can_remember": true,
              "payment": {
                "site": "accounts.kakao.com",
                "summary": "ticket",
                "amount": 1000.0,
                "currency": "KRW",
                "amount_text": "1,000원"
              }
            }
        """))
        val payItems = ChatReducer.apply(emptyList(), payEv) { "k2" }
        val payItem = payItems.single() as ChatItem.Approval
        assertEquals(false, payItem.canRemember)
    }
}
