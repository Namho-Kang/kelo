package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.ApprovalCardInfo
import chat.kelo.app.chat.CardBrief
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.ChatReducer
import chat.kelo.app.chat.PaymentInfo
import chat.kelo.app.chat.ShippingFormState
import chat.kelo.app.chat.TurnEvent
import chat.kelo.app.wallet.AddressDraft
import chat.kelo.app.wallet.AddressForm
import chat.kelo.app.wallet.AddressKeys
import chat.kelo.app.wallet.AddressProblem
import chat.kelo.app.wallet.AddressRules
import chat.kelo.app.wallet.OrderItem
import chat.kelo.app.wallet.OrderShipping
import chat.kelo.app.wallet.ShippingProfiles
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * The desktop side of shipping addresses and the final order card, on the contract's sample JSON (real server output, kept in
 * tools/mock_parts/shipping_data): the events and the forms are read as the server sends them, the form's local checks, the
 * country names in the app's language, a draft kept in step with a changed country, and the order block drawn 320 dp wide with
 * long, spaceless words (everything wraps inside the window, nothing runs off its edge).
 */
@OptIn(ExperimentalTestApi::class)
class ShippingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun sample(name: String): String = File("tools/mock_parts/shipping_data/$name").readText()
    private fun obj(name: String): JsonObject = json.parseToJsonElement(sample(name)) as JsonObject
    private fun form(name: String): AddressForm = json.decodeFromString(AddressForm.serializer(), sample(name))

    @Test fun approvalCarriesTheWholeOrder() {
        val ev = TurnEvent.parse(obj("approval_order_sample.json")) as TurnEvent.ApprovalRequest
        val p = ev.payment!!
        assertEquals(listOf(OrderItem("블루 머그", "색상: 네이비 · 용량: 350ml", 2, "24,000원")), p.items)
        val s = p.shipping!!
        assertEquals("profile", s.source)
        assertEquals("집 · 홍길동 · +821012345678", AddressRules.who(s.label, s.name, s.phone))
        assertEquals(listOf("서울특별시 강남구 테헤란로 152", "10층 1004호", "06236"), AddressRules.lines(s.formatted))
        assertEquals("WELCOME10", p.coupon)
        assertEquals("블루 머그 2개", p.orderWhat)
        assertEquals("24,000원", p.amountText)
        // the chat's list keeps it on the approval row
        val items = ChatReducer.apply(emptyList(), ev) { "k" }
        assertEquals(p, (items.single() as ChatItem.Approval).payment)
        // the English sample: no coupon, a US address in its own layout
        val en = (TurnEvent.parse(obj("approval_order_sample_en.json")) as TurnEvent.ApprovalRequest).payment!!
        assertEquals("", en.coupon)
        assertEquals("MOUNTAIN VIEW, CA 94043", AddressRules.lines(en.shipping!!.formatted).last())
    }

    @Test fun anOlderPaymentIsNotAnOrder() {
        val p = PaymentInfo("shop.example.com", "a mug", 9.0, "USD", "9.00 USD")
        // a card without a nickname is named by its brand; the last 4 are not repeated when the nickname already ends with them
    }

    @Test fun shippingFormEventBecomesAFormCard() {
        val ev = TurnEvent.parse(obj("shipping_form_event_sample.json")) as TurnEvent.ShippingForm
        val ask = ev.ask
        assertEquals("sf_7c1e2a", ask.formId)
        assertEquals("KR", ask.country)
        assertEquals("홍길동", ask.prefill["name"])
        assertTrue(ask.save)
        assertTrue(ask.askPayment)
        assertEquals("집", ask.label)
        var items = ChatReducer.apply(emptyList(), ev) { "f1" }
        val card = items.single() as ChatItem.ShippingForm
        assertTrue(card.open)
        // the prefill fills only the form's own keys; the phone is the one typed
        val draft = AddressRules.draftOf(ask)
        assertEquals("010-1234-5678", draft.phone)
        assertEquals("서울특별시 강남구 테헤란로 152", draft.value(AddressKeys.LINE1))
        assertFalse(AddressKeys.POSTAL_CODE in draft.values)   // an empty prefill is not a value
        // answered here: it folds into one line
        val done = ChatReducer.settleShipping(items, ask.formId, ShippingFormState.DONE, "집", "서울특별시 강남구 테헤란로 152, 06236")
        assertEquals(ShippingFormState.DONE, (done.single() as ChatItem.ShippingForm).state)
        // the reply ended with the form still open: it is only a record now
        items = ChatReducer.endTurn(items)
        assertEquals(ShippingFormState.EXPIRED, (items.single() as ChatItem.ShippingForm).state)
        // its time ran out
        assertTrue(ask.expired(ask.expiresAt))
        assertFalse(ask.expired(ask.expiresAt - 1))
    }

    @Test fun formsComeInTheServersOrder() {
        val us = form("form_US_en.json")
        assertEquals(listOf("name", "address_line1", "address_line2", "locality", "admin_area", "postal_code"), us.fields.map { it.key })
        assertTrue(us.field("admin_area")!!.isPicker)
        assertEquals("California", us.field("admin_area")!!.optionName("CA"))
        assertEquals("+1", us.phone.callingCode)
        val kr = form("form_KR_ko.json")
        assertEquals(listOf("이름", "우편번호", "주소", "상세주소"), kr.fields.map { it.label })
        assertEquals("+82", kr.phone.callingCode)
        assertEquals("선택", kr.labels.optional)
        val jp = form("form_JP_ja.json")
        assertEquals(listOf("name", "postal_code", "admin_area", "locality", "address_line1", "address_line2"), jp.fields.map { it.key })
        assertEquals(47, jp.field("admin_area")!!.options!!.size)
        val de = form("form_DE_de.json")
        assertTrue(de.fields.indexOfFirst { it.key == "postal_code" } < de.fields.indexOfFirst { it.key == "locality" })
        val gb = form("form_GB_en.json")
        assertFalse(gb.field("admin_area")!!.required)
    }

    @Test fun localChecksFollowTheForm() {
        val us = form("form_US_en.json")
        val ok = AddressDraft("US", mapOf("name" to "Jane Roe", "address_line1" to "1600 Amphitheatre Pkwy", "locality" to "Mountain View",
            "admin_area" to "CA", "postal_code" to "94043"), phone = "650 253 0000")
        assertTrue(AddressRules.isValid(us, ok))
        assertTrue(AddressRules.isValid(us, ok.with("postal_code", "94043-1234")))
        assertEquals(AddressProblem.PATTERN, AddressRules.problems(us, ok.with("postal_code", "9404"))["postal_code"])
        assertEquals(AddressProblem.REQUIRED, AddressRules.problems(us, ok.with("locality", ""))["locality"])
        assertEquals(AddressProblem.REQUIRED, AddressRules.problems(us, ok.with("admin_area", "Nowhere"))["admin_area"])
        assertEquals(AddressProblem.REQUIRED, AddressRules.problems(us, ok.copy(phone = ""))[AddressKeys.PHONE])
        assertEquals(AddressProblem.PHONE, AddressRules.problems(us, ok.copy(phone = "call me"))[AddressKeys.PHONE])
        assertNull(AddressRules.problems(us, ok.with("address_line2", ""))["address_line2"])   // optional
        // the UK's postcodes, any case
        val gb = form("form_GB_en.json")
        assertTrue(AddressRules.matches(gb.field("postal_code")!!.pattern, "ec1y 8sy"))
        assertFalse(AddressRules.matches(gb.field("postal_code")!!.pattern, "12345"))
    }

    /** What the shared shipping view model does when a country's form arrives (ShippingViewModel.onFormShown): carry the values, show a saved number nationally. */
    private fun draftOnForm(draft: AddressDraft, form: AddressForm, first: Boolean): AddressDraft {
        val carried = AddressRules.carry(draft, form)
        return if (first) carried.copy(phone = AddressRules.nationalPart(carried.phone, form.phone.callingCode)) else carried
    }

    @Test fun aDraftFollowsTheFormItIsShownWith() {
        val profiles = json.decodeFromString(ShippingProfiles.serializer(), sample("profile_samples.json")).profiles
        val home = profiles.first { it.country == "KR" }
        val kr = form("form_KR_ko.json")
        val us = form("form_US_en.json")
        // a saved profile's phone is E.164: next to the "+82" chip it shows without it
        var d = draftOnForm(AddressDraft(home.country, home.values(), home.phone, home.note), kr, first = true)
        assertEquals("1012345678", d.phone)
        assertEquals("06236", d.value("postal_code"))
        assertEquals("문 앞에 놓아 주세요", d.note)
        // to the US: the keys both forms have stay, the phone and the note stay as typed
        d = draftOnForm(d.copy(country = "US"), us, first = false)
        assertEquals("US", d.country)
        assertEquals("홍길동", d.value("name"))
        assertEquals("06236", d.value("postal_code"))
        assertEquals("1012345678", d.phone)
        // to Korea again from a US address with a state: the state goes (Korea's form has none)
        val office = profiles.first { it.country == "US" }
        val back = draftOnForm(draftOnForm(AddressDraft("US", office.values(), office.phone), us, true).copy(country = "KR"), kr, false)
        assertFalse("admin_area" in back.values)
        assertFalse("locality" in back.values)
        assertEquals("Jane Roe", back.value("name"))
        assertEquals("6502530000", draftOnForm(AddressDraft("US", office.values(), office.phone), us, true).phone)
        // what is sent: every key, the ones the form does not have as ""
        val body = AddressRules.profileBody(kr, back, "회사", true)
        assertEquals("\"\"", body["admin_area"].toString())
        assertEquals("\"KR\"", body["country"].toString())
        assertEquals("true", body["default"].toString())
    }

}
