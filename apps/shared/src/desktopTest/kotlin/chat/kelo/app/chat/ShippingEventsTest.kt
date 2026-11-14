package chat.kelo.app.chat

import chat.kelo.app.wallet.OrderItem
import chat.kelo.app.wallet.OrderShipping
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-chat address question (shipping_form) and the order parts of the final payment approval. */
class ShippingEventsTest {
    private fun parse(json: String) = TurnEvent.parse(Json.parseToJsonElement(json))
    private var n = 0
    private val key = { "k${++n}" }
    private fun run(vararg evs: TurnEvent, start: List<ChatItem> = emptyList()) = evs.fold(start) { items, ev -> ChatReducer.apply(items, ev, key) }

    private val form = """{"type":"shipping_form","id":"call_5","form_id":"sf_7c1e2a","site":"shop.example.com","what":"블루 머그 2개",
        "country":"KR","prefill":{"name":"홍길동","phone":"010-1234-5678","postal_code":"","address_line1":"서울특별시 강남구 테헤란로 152","address_line2":""},
        "label":"집","save":true,"ask_payment":true,"expires_at":1762650600}"""

    private val approval = """{"type":"approval_request","approval_id":"ap_123","id":"call_9","name":"confirm_purchase",
        "arguments":{"summary":"블루 머그 2개","amount":24000,"currency":"KRW","site":"shop.example.com","card":"생활비 카드"},
        "detail":"d","lines":[],"label":"결제 확인 요청","ask":"결제할까요?","site":"shop.example.com","expires_at":1762650300,
        "payment":{"site":"shop.example.com","summary":"블루 머그 2개","amount":24000,"currency":"KRW","amount_text":"24,000원",
          "items":[{"name":"블루 머그","options":"색상: 네이비 · 용량: 350ml","quantity":2,"price_text":"24,000원"}],
          "shipping":{"source":"profile","label":"집","name":"홍길동","phone":"+821012345678","formatted":"서울특별시 강남구 테헤란로 152\n10층 1004호\n06236","country":"KR"},
          "coupon":"WELCOME10","order":{"what":"블루 머그 2개"}},
        "card":{"id":"card_1","label":"생활비 카드","brand":"visa","last4":"4242","auto_pay":false,"needs_card":true,"reason":"not_auto","reason_text":"이 카드는 자동 결제가 꺼져 있어요"}}"""

    @Test fun shippingFormEventParses() {
        val e = parse(form) as TurnEvent.ShippingForm
        assertEquals("sf_7c1e2a", e.ask.formId)
        assertEquals("call_5", e.ask.id)
        assertEquals("shop.example.com", e.ask.site)
        assertEquals("블루 머그 2개", e.ask.what)
        assertEquals("KR", e.ask.country)
        assertEquals("홍길동", e.ask.prefill["name"])
        assertTrue(e.ask.askPayment)
    }

    @Test fun approvalCarriesItemsShippingAndCoupon() {
        val a = parse(approval) as TurnEvent.ApprovalRequest
        val p = a.payment!!
        assertEquals(listOf(OrderItem("블루 머그", "색상: 네이비 · 용량: 350ml", 2, "24,000원")), p.items)
        assertEquals(OrderShipping("profile", "집", "홍길동", "+821012345678", "서울특별시 강남구 테헤란로 152\n10층 1004호\n06236", "KR"), p.shipping)
        assertEquals("WELCOME10", p.coupon)
        assertEquals("블루 머그 2개", p.orderWhat)
        assertEquals("24,000원", p.amountText)
        assertEquals("4242", a.card!!.card.last4)
        // the chat's card keeps them
        val item = run(a).single() as ChatItem.Approval
        assertEquals(p, item.payment)
        // an older server: no order parts at all
        val old = parse("""{"type":"approval_request","approval_id":"a","name":"confirm_purchase","arguments":{},
            "payment":{"site":"s","summary":"책","amount":1,"currency":"KRW","amount_text":"1원"}}""") as TurnEvent.ApprovalRequest
        assertTrue(old.payment!!.items.isEmpty())
        assertNull(old.payment!!.shipping)
        assertEquals("", old.payment!!.coupon)
        assertEquals(PaymentInfo("s", "책", 1.0, "KRW", "1원"), old.payment)
        // shipping null and an empty coupon from a new server
        val none = parse("""{"type":"approval_request","approval_id":"a","name":"confirm_purchase","arguments":{},
            "payment":{"site":"s","summary":"e-book","amount":1,"currency":"USD","amount_text":"1.00 USD","items":[],"shipping":null,"coupon":"","order":null}}""") as TurnEvent.ApprovalRequest
        assertNull(none.payment!!.shipping)
        assertEquals("", none.payment!!.orderWhat)
    }

    @Test fun paymentAutoMayCarryTheOrder() {
        val e = parse("""{"type":"payment_auto","id":"t","card":{"id":"c","label":"생활비 카드","brand":"visa","last4":"4242"},
            "payment":{"site":"shop.example.com","amount_text":"24,000원","summary":"머그",
              "shipping":{"source":"profile","label":"집","name":"홍길동","phone":"","formatted":"서울\n06236","country":"KR"}}}""") as TurnEvent.PaymentAuto
        assertEquals("집", e.payment!!.shipping!!.label)
        val row = run(e).single() as ChatItem.PaymentAuto
        assertEquals("shop.example.com", row.site)
        assertEquals("집", row.order!!.shipping!!.label)
        // without one, as before
        val plain = parse("""{"type":"payment_auto","id":"t","payment":{"site":"s","amount_text":"1원"}}""") as TurnEvent.PaymentAuto
        assertNull(plain.payment!!.shipping)
    }

    @Test fun formRowIsAddedOnceAndKeepsItsState() {
        val e = parse(form) as TurnEvent.ShippingForm
        val items = run(e)
        val row = items.single() as ChatItem.ShippingForm
        assertTrue(row.open)
        // sent here, then the same event again (a resend): it stays sent, in the same row
        val sent = ChatReducer.settleShipping(items, "sf_7c1e2a", ShippingFormState.DONE, "집", "서울특별시 강남구 테헤란로 152, 06236")
        val again = run(e, start = sent)
        assertEquals(1, again.size)
        val done = again.single() as ChatItem.ShippingForm
        assertEquals(ShippingFormState.DONE, done.state)
        assertEquals("집", done.doneLabel)
        assertEquals(row.key, done.key)
        // only an open form changes
        assertEquals(ShippingFormState.DONE, (ChatReducer.settleShipping(sent, "sf_7c1e2a", ShippingFormState.CANCELLED)[0] as ChatItem.ShippingForm).state)
    }

    @Test fun theReplyEndingExpiresAnOpenForm() {
        val items = run(parse(form))
        val ended = ChatReducer.endTurn(items)
        assertEquals(ShippingFormState.EXPIRED, (ended.single() as ChatItem.ShippingForm).state)
        // an answered one stays answered
        val sent = ChatReducer.settleShipping(items, "sf_7c1e2a", ShippingFormState.CANCELLED)
        assertSame(sent, ChatReducer.endTurn(sent))
    }

    @Test fun theToolFinishingElsewhereClosesTheForm() {
        val started = TurnEvent.ToolStarted("call_5", "ask_shipping", kotlinx.serialization.json.JsonObject(emptyMap()), "배송지 묻는 중")
        val items = run(started, parse(form), TurnEvent.ToolResult("call_5", "ask_shipping", "주소를 받았어요", isError = false, approved = null))
        val f = items.filterIsInstance<ChatItem.ShippingForm>().single()
        assertEquals(ShippingFormState.CLOSED, f.state)
        // this phone's own answer, whose 200 came after the tool's result: it still folds to what was sent
        val mine = ChatReducer.settleShipping(items, "sf_7c1e2a", ShippingFormState.DONE, "집", "서울")
        assertEquals(ShippingFormState.DONE, mine.filterIsInstance<ChatItem.ShippingForm>().single().state)
        assertEquals(ShippingFormState.CANCELLED, ChatReducer.settleShipping(items, "sf_7c1e2a", ShippingFormState.CANCELLED).filterIsInstance<ChatItem.ShippingForm>().single().state)
        // a 404 does not turn a closed form into an expired one
        assertEquals(ShippingFormState.CLOSED, ChatReducer.settleShipping(items, "sf_7c1e2a", ShippingFormState.EXPIRED).filterIsInstance<ChatItem.ShippingForm>().single().state)
        // another tool's result leaves it open
        val other = run(parse(form), TurnEvent.ToolResult("call_7", "browser_open", "", isError = false, approved = null))
        assertTrue(other.filterIsInstance<ChatItem.ShippingForm>().single().open)
    }

    @Test fun goneFormFoldsAsExpired() {
        val items = run(parse(form))
        val gone = ChatReducer.settleShipping(items, "sf_7c1e2a", ShippingFormState.EXPIRED)
        assertEquals(ShippingFormState.EXPIRED, (gone.single() as ChatItem.ShippingForm).state)
        // another form id: nothing changes
        assertSame(items[0], ChatReducer.settleShipping(items, "sf_other", ShippingFormState.EXPIRED)[0])
    }
}
