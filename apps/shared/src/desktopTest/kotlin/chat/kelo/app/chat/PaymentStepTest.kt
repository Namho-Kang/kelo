package chat.kelo.app.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentStepTest {
    private fun ev(s: String) = TurnEvent.parse(Json.parseToJsonElement(s))

    private val app = """{"type":"payment_step","step_id":"ps_1","kind":"app","state":"waiting","site":"mugshop.example",
        "order":{"site":"mugshop.example","summary":"블루 머그 x2","amount_text":"24,000원","pay_with":"카드사 앱",
        "items":[{"name":"블루 머그","quantity":2}]},"code":"482913","code_shown":"482 913","qr":"data:image/png;base64,AAAA",
        "expires_at":1000,"seconds_total":180,"instruction":"카드사 앱에서 승인해 주세요","can_resend":true,"needs_code":false,
        "taps":0,"can_clear":false,"tab":"owner","opened_at":5.0}"""

    @Test fun parsesTheStepEvent() {
        val s = (ev(app) as TurnEvent.PaymentStepChanged).step
        assertEquals("ps_1", s.stepId)
        assertEquals("482 913", PaymentStepRules.codeText(s))
        assertEquals(listOf("블루 머그 × 2"), s.items)
        assertEquals("24,000원", s.amountText)
        assertTrue(s.open && !s.isPad && s.canResend)
        assertTrue(ev("""{"type":"payment_step"}""") is TurnEvent.Unknown)
    }

    @Test fun countdownExpiryAndProgress() {
        val s = (ev(app) as TurnEvent.PaymentStepChanged).step
        assertEquals(180L, PaymentStepRules.secondsLeft(s, 820))
        assertEquals(0L, PaymentStepRules.secondsLeft(s, 2000))
        assertEquals(0.5f, PaymentStepRules.progress(s, 910)!!, 0.01f)
        assertFalse(PaymentStepRules.expired(s, 999))
        assertTrue(PaymentStepRules.expired(s, 1000))
        assertTrue(PaymentStepRules.expired(s.copy(state = "expired"), 0))
        assertNull(PaymentStepRules.secondsLeft(s.copy(expiresAt = null), 0))
        assertEquals("123 456", PaymentStepRules.codeText(s.copy(codeShown = null, code = "123456")))
    }

    @Test fun upsertKeepsOneRowPerStepAndNeverReopens() {
        val s = (ev(app) as TurnEvent.PaymentStepChanged).step
        var list = PaymentStepRules.upsert(emptyList(), s)
        list = PaymentStepRules.upsert(list, s.copy(code = "111222"))
        assertEquals(1, list.size)
        assertEquals("111222", list[0].code)
        list = PaymentStepRules.upsert(list, s.copy(state = "approved"))
        list = PaymentStepRules.upsert(list, s)                       // an older copy from a load
        assertEquals("approved", list[0].state)
        val other = s.copy(stepId = "ps_2", openedAt = 9.0)
        list = PaymentStepRules.refresh(list, listOf(other))
        assertEquals(listOf("ps_1", "ps_2"), PaymentStepRules.visible(list).map { it.stepId })
    }

    @Test fun padTapsAreFractionsInsideThePicture() {
        assertEquals(0.5f to 0.25f, PaymentStepRules.tapFraction(100f, 50f, 200, 200))
        assertNull(PaymentStepRules.tapFraction(201f, 50f, 200, 200))
        assertNull(PaymentStepRules.tapFraction(10f, 10f, 0, 0))
        assertEquals("●●●", PaymentStepRules.dots(3))
        assertEquals(8, PaymentStepRules.dots(12).length)
    }

    @Test fun pinNeededAndApprovalPin() {
        val n = (ev("""{"type":"pin_needed","slot_id":"ap1","site":"mugshop.example","amount_text":"24,000원",
            "pin":{"id":"pin_1","label":"생활","site":"mugshop.example","digits":6},"expires_at":99,"urgent":true}""") as TurnEvent.PinNeeded).need
        assertEquals("ap1", n.slotId)
        assertEquals(6, n.digits)
        assertTrue(n.urgent)
        val a = ev("""{"type":"approval_request","approval_id":"ap1","name":"confirm_purchase","arguments":{},
            "pin":{"id":"pin_1","label":"생활","site":"mugshop.example","digits":6,"needs_pin":true}}""") as TurnEvent.ApprovalRequest
        assertEquals("pin_1", a.pin!!.id)
        val b = ev("""{"type":"approval_request","approval_id":"ap2","name":"confirm_purchase","arguments":{}}""") as TurnEvent.ApprovalRequest
        assertNull(b.pin)
        val items = ChatReducer.apply(emptyList(), a) { "k" }
        assertEquals("pin_1", (items[0] as ChatItem.Approval).pin!!.id)
    }
}
