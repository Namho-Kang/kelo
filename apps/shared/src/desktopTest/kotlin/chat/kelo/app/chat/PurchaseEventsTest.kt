package chat.kelo.app.chat

import chat.kelo.app.wallet.CardSendError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseEventsTest {
    private fun parse(json: String) = TurnEvent.parse(Json.parseToJsonElement(json))
    private var n = 0
    private val key = { "k${++n}" }
    private fun run(vararg evs: TurnEvent, start: List<ChatItem> = emptyList()) = evs.fold(start) { items, ev -> ChatReducer.apply(items, ev, key) }

    @Test fun toolEventsCarryLabelAndHidden() {
        val s = parse("""{"type":"tool_started","id":"t1","name":"browser_open","arguments":{},"label":"사이트 여는 중","hidden":false}""") as TurnEvent.ToolStarted
        assertEquals("사이트 여는 중", s.label)
        assertFalse(s.hidden)
        val h = parse("""{"type":"tool_result","id":"t2","name":"read_skill","output":"x","is_error":false,"approved":null,"label":"안내서 확인","hidden":true}""") as TurnEvent.ToolResult
        assertTrue(h.hidden)
        assertEquals("안내서 확인", h.label)
        // an older server without the fields
        val old = parse("""{"type":"tool_started","id":"t3","name":"x","arguments":{}}""") as TurnEvent.ToolStarted
        assertEquals("", old.label)
        assertFalse(old.hidden)
    }

    @Test fun approvalWithPaymentAndCard() {
        val a = parse("""{"type":"approval_request","approval_id":"ap","id":"call1","name":"confirm_purchase","arguments":{},"detail":"d","label":"결제 확인 요청",
            "payment":{"site":"shop.example.com","summary":"티셔츠 1장","amount":24000,"currency":"KRW","amount_text":"24,000원"},
            "card":{"id":"card_1","label":"Visa ••4242","brand":"visa","last4":"4242","auto_pay":true,"needs_card":true,"reason":"first_site","reason_text":"처음 결제하는 사이트예요"}}""") as TurnEvent.ApprovalRequest
        assertEquals("결제 확인 요청", a.label)
        assertEquals(PaymentInfo("shop.example.com", "티셔츠 1장", 24000.0, "KRW", "24,000원"), a.payment)
        val c = a.card!!
        assertEquals("Visa ••4242", c.card.label)
        assertEquals("card_1", c.card.id)
        assertTrue(c.autoPay)
        assertTrue(c.needsCard)
        assertEquals("first_site", c.reason)
        assertEquals("처음 결제하는 사이트예요", c.reasonText)
        val plain = parse("""{"type":"approval_request","approval_id":"ap2","name":"email_send","arguments":{"to":"a"}}""") as TurnEvent.ApprovalRequest
        assertNull(plain.payment)
        assertNull(plain.card)
    }

    @Test fun browserCardNeededAndPaymentAuto() {
        val b = parse("""{"type":"browser","id":"t9","title":"Checkout","url":"https://shop.example.com/c","host":"shop.example.com","status":"구매 진행 중",
            "image":"data:image/jpeg;base64,AQID","width":720,"height":1280,"bytes":3,"at":1762500000.5}""") as TurnEvent.Browser
        assertEquals("구매 진행 중", b.status)
        assertEquals("data:image/jpeg;base64,AQID", b.image)
        assertEquals(720, b.width)
        assertEquals(1762500000.5, b.at, 0.0)
        val noImage = parse("""{"type":"browser","id":"t9","title":"","url":"","host":"h","status":"사이트 보는 중","image":null}""") as TurnEvent.Browser
        assertNull(noImage.image)
        val notData = parse("""{"type":"browser","id":"t9","image":"https://x/y.jpg"}""") as TurnEvent.Browser
        assertNull(notData.image)

        val cn = parse("""{"type":"card_needed","id":"call1","approval_id":"ap","card":{"id":"card_1","label":"Visa ••4242","brand":"visa","last4":"4242"},
            "site":"shop.example.com","amount":24000,"currency":"KRW","amount_text":"24,000원","expires_at":1762500300}""") as TurnEvent.CardNeeded
        assertEquals("ap", cn.approvalId)
        assertEquals(CardBrief("card_1", "Visa ••4242", "visa", "4242"), cn.card)
        assertEquals(1762500300L, cn.expiresAt)
        assertEquals("24,000원", cn.amountText)

        val pa = parse("""{"type":"payment_auto","id":"call2","card":{"id":"card_2","label":"Mastercard ••4444","brand":"mastercard","last4":"4444"},
            "payment":{"id":7,"at":1.0,"kind":"auto","site":"example.com","host":"pay.example.com","summary":"커피","amount":4500,"currency":"KRW","amount_text":"4,500원"}}""") as TurnEvent.PaymentAuto
        assertEquals("example.com", pa.site)
        assertEquals("4,500원", pa.amountText)
        assertEquals("Mastercard ••4444", pa.card!!.label)
    }

    @Test fun hiddenToolsShowNothingAndLabelsReplaceNames() {
        val items = run(
            TurnEvent.ToolStarted("1", "read_skill", JsonObject(emptyMap()), "안내서 확인", hidden = true),
            TurnEvent.ToolResult("1", "read_skill", "", false, null, "안내서 확인", hidden = true),
            TurnEvent.ToolStarted("2", "browser_open", JsonObject(emptyMap()), "사이트 여는 중"),
        )
        assertEquals(1, items.size)
        val chip = items[0] as ChatItem.Tool
        assertEquals("사이트 여는 중", chip.shown)
        assertEquals("x", ChatItem.Tool("k", "1", "x", ToolState.OK).shown)
        // the status line: the label, and a hidden call reads as thinking
        assertEquals("사이트 여는 중", ChatReducer.phase(null, TurnEvent.ToolStarted("2", "browser_open", JsonObject(emptyMap()), "사이트 여는 중"), 1)!!.label)
        assertEquals(PhaseKind.THINKING, ChatReducer.phase(null, TurnEvent.ToolStarted("1", "read_skill", JsonObject(emptyMap()), "x", hidden = true), 1)!!.kind)
    }

    @Test fun oneBrowserCardPerReplyUpdatesInPlace() {
        val first = TurnEvent.Browser("t1", "A", "u", "a.com", "사이트 보는 중", "data:image/jpeg;base64,AQ==", 720, 400)
        val items = run(
            first,
            TurnEvent.Text("잠시만요"),
            TurnEvent.Browser("t1", "A2", "u", "a.com", "구매 진행 중", null),
            TurnEvent.Browser("t2", "B", "u", "b.com", "사이트 보는 중", null),   // the next step, another site, the same tab
        )
        val cards = items.filterIsInstance<ChatItem.Browser>()
        assertEquals(1, cards.size)
        assertEquals("k1", cards[0].key)            // same row, same key
        assertEquals("사이트 보는 중", cards[0].status)
        assertEquals("b.com", cards[0].host)
        assertEquals("t2", cards[0].id)
        assertEquals(listOf("a.com", "b.com"), cards[0].hosts)
        assertEquals("data:image/jpeg;base64,AQ==", cards[0].image)   // a card without a picture keeps the last one
        assertEquals(720, cards[0].width)
        assertTrue(items[0] is ChatItem.Browser)
    }

    @Test fun browserCardsOpenTheTabTheirPicturesCameFrom() {
        // a job's card: "보기" opens the job's tab, never this phone's own (which may be closed or show something else)
        val ev = parse("""{"type":"browser","id":"t1","tab":"task:w1a2b3","title":"체험단","url":"https://www.musinsa.com/","host":"www.musinsa.com","status":"사이트 보는 중","image":"data:image/jpeg;base64,AQ==","width":720,"height":400,"at":1}""") as TurnEvent.Browser
        assertEquals("task:w1a2b3", ev.tab)
        val items = run(ev, TurnEvent.Browser("t1", "체험단", "u", "www.musinsa.com", "페이지 조작 중", null))
        val b = items.single() as ChatItem.Browser
        assertEquals("task:w1a2b3", b.tab)                       // an update without the field keeps it
        val target = chat.kelo.app.notify.OpenTarget.Tab.ofCard(b) as chat.kelo.app.notify.OpenTarget.Tab
        assertEquals("task:w1a2b3", target.id)
        assertEquals("data:image/jpeg;base64,AQ==", target.picture)   // shown if that tab has closed meanwhile
        assertFalse(target.control)
        // an older server names no tab: this phone's own, as before
        val old = parse("""{"type":"browser","id":"t2","title":"A","url":"u","host":"a.com","status":"s","image":null}""") as TurnEvent.Browser
        assertEquals("", old.tab)
        val oldCard = run(old).single() as ChatItem.Browser
        assertSame(chat.kelo.app.notify.OpenTarget.OwnTab, chat.kelo.app.notify.OpenTarget.Tab.ofCard(oldCard))
        // the tab key is not part of what a notification can carry: tab:<id> still decodes to the plain tab
        assertEquals(chat.kelo.app.notify.OpenTarget.Tab("task:w1a2b3"), chat.kelo.app.notify.OpenTarget.decode("tab:task:w1a2b3"))
    }

    private val card = ApprovalCardInfo(CardBrief("card_1", "Visa ••4242", "visa", "4242"), autoPay = false, needsCard = true, reason = "not_auto", reasonText = "r")
    private val pay = PaymentInfo("shop.example.com", "티셔츠", 24000.0, "KRW", "24,000원")
    private fun ask() = TurnEvent.ApprovalRequest("ap", "confirm_purchase", JsonObject(emptyMap()), null, "결제 확인 요청", pay, card)
    private fun needed() = TurnEvent.CardNeeded("c1", "ap", card.card, "shop.example.com", 24000.0, "KRW", "24,000원", 1000)

    @Test fun approvalKeepsPaymentAndFoldsWhenAllowed() {
        val items = run(ask())
        val a = items.single() as ChatItem.Approval
        assertEquals(pay, a.payment)
        assertTrue(a.needsCard)
        assertEquals("결제 확인 요청", a.shown)
        val sent = ChatReducer.cardSend(items, "ap", CardSend.Sent)
        val allowed = ChatReducer.answer(sent, "ap", true).single() as ChatItem.Approval
        assertEquals(true, allowed.answer)
        assertFalse(allowed.open)
        assertEquals(CardSend.Sent, allowed.send)
    }

    @Test fun cardNeededIsSkippedWhenThisPhoneAlreadySentIt() {
        val sent = ChatReducer.cardSend(run(ask()), "ap", CardSend.Sent)
        assertSame(sent, ChatReducer.apply(sent, needed(), key))
        // not sent here (allowed on another phone, or the send failed): a row with the countdown, updated in place
        val items = run(ask(), needed(), needed().copy(expiresAt = 2000))
        val rows = items.filterIsInstance<ChatItem.CardNeeded>()
        assertEquals(1, rows.size)
        assertEquals(2000L, rows[0].expiresAt)
        val failed = ChatReducer.cardSend(items, "ap", CardSend.Failed(CardSendError.CANCELLED))
        assertEquals(CardSend.Failed(CardSendError.CANCELLED), failed.filterIsInstance<ChatItem.CardNeeded>()[0].send)
        assertEquals(CardSend.Failed(CardSendError.CANCELLED), failed.filterIsInstance<ChatItem.Approval>()[0].send)
    }

    @Test fun theEndOfAReplyEndsWaitingCards() {
        val items = ChatReducer.endTurn(run(ask(), needed()))
        assertTrue((items[0] as ChatItem.Approval).expired)
        assertTrue((items[1] as ChatItem.CardNeeded).ended)
        val done = ChatReducer.cardSend(run(needed()), "ap", CardSend.Sent)
        assertSame(done, ChatReducer.endTurn(done))
    }

    @Test fun paymentAutoAddsARow() {
        val items = run(TurnEvent.PaymentAuto("c", CardBrief("card_2", "MC ••4444", "mastercard", "4444"), "example.com", "4,500원", "커피"))
        assertEquals(ChatItem.PaymentAuto("k1", "example.com", "4,500원", "MC ••4444"), items.single())
    }
}
