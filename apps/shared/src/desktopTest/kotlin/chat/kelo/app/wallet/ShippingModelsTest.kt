package chat.kelo.app.wallet

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address form, shipping profiles and the in-chat question as the server sends them (samples from the server's own output). */
class ShippingModelsTest {
    private fun <T> read(s: kotlinx.serialization.KSerializer<T>, json: String): T = KeloApi.json.decodeFromString(s, json)

    private val usForm = """{"country":"US","country_name":"United States","lang":"en","fields":[
        {"key":"name","label":"Full name","required":true,"autocomplete":"name"},
        {"key":"address_line1","label":"Address line 1","required":true,"autocomplete":"address-line1"},
        {"key":"address_line2","label":"Address line 2 (apt, suite, unit)","required":false,"autocomplete":"address-line2"},
        {"key":"locality","label":"City","required":true,"autocomplete":"address-level2"},
        {"key":"admin_area","label":"State","required":true,"autocomplete":"address-level1","options":[{"key":"AL","name":"Alabama"},{"key":"CA","name":"California"},{"key":"NY","name":"New York"}]},
        {"key":"postal_code","label":"ZIP code","required":true,"autocomplete":"postal-code","pattern":"^(?:(\\d{5})(?:[ \\-](\\d{4}))?)$","example":"95014","numeric":true}],
        "phone":{"label":"Phone number","calling_code":"+1","required":true},"note":{"label":"Delivery note","required":false},
        "labels":{"country":"Country","nickname":"Nickname","optional":"optional"},"format":"%N%n%O%n%A%n%C, %S %Z"}"""

    private val krForm = """{"country":"KR","country_name":"South Korea","lang":"ko","fields":[
        {"key":"name","label":"이름","required":true,"autocomplete":"name"},
        {"key":"postal_code","label":"우편번호","required":true,"autocomplete":"postal-code","pattern":"^(?:\\d{5})$","example":"03051","numeric":true},
        {"key":"address_line1","label":"주소","required":true,"autocomplete":"address-line1"},
        {"key":"address_line2","label":"상세주소","required":false,"autocomplete":"address-line2"}],
        "phone":{"label":"전화번호","calling_code":"+82","required":true},"note":{"label":"배송 메모","required":false},
        "labels":{"country":"국가","nickname":"별칭","optional":"선택"},"format":"%S %C%D%n%A%n%O%n%N%n%Z"}"""

    private val gbPostcode = """{"country":"GB","fields":[{"key":"postal_code","label":"Postcode","required":true,
        "pattern":"^(?:GIR ?0AA|(?:(?:AB|AL|B|BA|BB|BD|BF|BH|BL|BN|BR|BS|BT|BX|CA|CB|CF|CH|CM|CO|CR|CT|CV|CW|DA|DD|DE|DG|DH|DL|DN|DT|DY|E|EC|EH|EN|EX|FK|FY|G|GL|GY|GU|HA|HD|HG|HP|HR|HS|HU|HX|IG|IM|IP|IV|JE|KA|KT|KW|KY|L|LA|LD|LE|LL|LN|LS|LU|M|ME|MK|ML|N|NE|NG|NN|NP|NR|NW|OL|OX|PA|PE|PH|PL|PO|PR|RG|RH|RM|S|SA|SE|SG|SK|SL|SM|SN|SO|SP|SR|SS|ST|SW|SY|TA|TD|TF|TN|TQ|TR|TS|TW|UB|W|WA|WC|WD|WF|WN|WR|WS|WV|YO|ZE)(?:\\d[\\dA-Z]? ?\\d[ABD-HJLN-UW-Z]{2}))|BFPO ?\\d{1,4})$",
        "example":"EC1Y 8SY","numeric":false},{"key":"admin_area","label":"County","required":false}],
        "phone":{"label":"Phone number","calling_code":"+44","required":true}}"""

    private val profiles = """{"profiles":[{"country":"KR","name":"홍길동","organization":"","address_line1":"서울특별시 강남구 테헤란로 152",
        "address_line2":"10층 1004호","dependent_locality":"","locality":"","admin_area":"","postal_code":"06236","sorting_code":"",
        "phone":"+821012345678","id":"ship_1a2b3c4d5e6f","label":"집","note":"문 앞에 놓아 주세요","default":true,"created_at":1762650000.0,
        "last_used":null,"formatted":"서울특별시 강남구 테헤란로 152\n10층 1004호\n홍길동\n06236","one_line":"서울특별시 강남구 테헤란로 152, 10층 1004호, 06236"},
        {"country":"US","name":"Jane Roe","organization":"","address_line1":"1600 Amphitheatre Pkwy","address_line2":"Bldg 43","dependent_locality":"",
        "locality":"Mountain View","admin_area":"CA","postal_code":"94043","sorting_code":"","phone":"+16502530000","id":"ship_9f8e7d6c5b4a",
        "label":"Office","note":"","default":false,"created_at":1762650000.0,"last_used":null,
        "formatted":"Jane Roe\n1600 Amphitheatre Pkwy\nBldg 43\nMOUNTAIN VIEW, CA 94043","one_line":"1600 Amphitheatre Pkwy, Bldg 43, MOUNTAIN VIEW, CA 94043"}]}"""

    private val us get() = read(AddressForm.serializer(), usForm)
    private val kr get() = read(AddressForm.serializer(), krForm)

    @Test fun formKeepsTheServersOrderOptionsAndPattern() {
        val f = us
        assertEquals(listOf("name", "address_line1", "address_line2", "locality", "admin_area", "postal_code"), f.fields.map { it.key })
        val state = f.field("admin_area")!!
        assertTrue(state.isPicker)
        assertEquals(listOf("AL", "CA", "NY"), state.options!!.map { it.key })
        assertEquals("California", state.optionName("CA"))
        assertEquals("ZZ", state.optionName("ZZ"))
        val zip = f.field("postal_code")!!
        assertEquals("95014", zip.example)
        assertTrue(zip.numeric)
        assertFalse(f.field("name")!!.isPicker)
        assertFalse(f.field("address_line2")!!.required)
        assertEquals("+1", f.phone.callingCode)
        assertTrue(f.phone.required)
        assertEquals("Delivery note", f.note.label)
        assertEquals("optional", f.labels.optional)
        assertEquals("%N%n%O%n%A%n%C, %S %Z", f.format)
        // KR: postal code before the address, Korean labels
        assertEquals(listOf("name", "postal_code", "address_line1", "address_line2"), kr.fields.map { it.key })
        assertEquals("우편번호", kr.field("postal_code")!!.label)
        assertEquals("+82", kr.phone.callingCode)
        assertEquals("선택", kr.labels.optional)
    }

    @Test fun anOlderOrSparseFormStillReads() {
        val f = read(AddressForm.serializer(), """{"country":"XX","fields":[{"key":"name"}]}""")
        assertEquals("", f.fields[0].label)
        assertFalse(f.fields[0].required)
        assertNull(f.fields[0].options)
        assertNull(f.fields[0].pattern)
        assertEquals("", f.phone.callingCode)
    }

    @Test fun postalPatternIsCaseInsensitiveAndTrimmed() {
        val gb = read(AddressForm.serializer(), gbPostcode).field("postal_code")!!.pattern
        assertTrue(AddressRules.matches(gb, "EC1Y 8SY"))
        assertTrue(AddressRules.matches(gb, "ec1y 8sy"))
        assertTrue(AddressRules.matches(gb, "  SW1A 1AA "))
        assertFalse(AddressRules.matches(gb, "12345"))
        val zip = us.field("postal_code")!!.pattern
        assertTrue(AddressRules.matches(zip, "95014"))
        assertTrue(AddressRules.matches(zip, "95014-1234"))
        assertFalse(AddressRules.matches(zip, "9501"))
        assertFalse(AddressRules.matches(zip, "950144"))
        // nothing typed, no pattern, or a pattern this side cannot read: the server decides
        assertTrue(AddressRules.matches(zip, ""))
        assertTrue(AddressRules.matches(null, "anything"))
        assertTrue(AddressRules.matches("(unclosed", "x"))
    }

    @Test fun problemsNameEveryBadKeyInOrder() {
        val empty = AddressRules.problems(us, AddressDraft("US"))
        assertEquals(listOf("name", "address_line1", "locality", "admin_area", "postal_code", "phone"), empty.keys.toList())
        assertTrue(empty.values.all { it == AddressProblem.REQUIRED })
        val good = AddressDraft(
            "US", mapOf("name" to "Jane Roe", "address_line1" to "1600 Amphitheatre Pkwy", "locality" to "Mountain View", "admin_area" to "CA", "postal_code" to "94043"),
            phone = "(650) 253-0000",
        )
        assertTrue(AddressRules.isValid(us, good))
        val bad = good.with("postal_code", "9404").with("admin_area", "ZZ").copy(phone = "call me")
        assertEquals(
            mapOf("admin_area" to AddressProblem.REQUIRED, "postal_code" to AddressProblem.PATTERN, "phone" to AddressProblem.PHONE),
            AddressRules.problems(us, bad),
        )
        // an optional field left empty is fine; spaces alone count as empty
        assertEquals(AddressProblem.REQUIRED, AddressRules.problems(us, good.with("name", "   "))["name"])
        assertFalse(AddressRules.problems(us, good).containsKey("address_line2"))
    }

    @Test fun phoneChecks() {
        assertTrue(AddressRules.phoneLooksRight("010-1234-5678"))
        assertTrue(AddressRules.phoneLooksRight("+82 10 1234 5678"))
        assertTrue(AddressRules.phoneLooksRight("(650) 253-0000"))
        assertTrue(AddressRules.phoneLooksRight(""))
        assertFalse(AddressRules.phoneLooksRight("12"))
        assertFalse(AddressRules.phoneLooksRight("010-1234-abcd"))
        assertFalse(AddressRules.phoneLooksRight("1".repeat(18)))
    }

    @Test fun changingTheCountryKeepsWhatBothFormsAsk() {
        val typed = AddressDraft(
            "US", mapOf("name" to "Jane", "address_line1" to "1 Main St", "locality" to "Austin", "admin_area" to "TX", "postal_code" to "73301"),
            phone = "5125550100", note = "door",
        )
        val toKr = AddressRules.carry(typed, kr)
        assertEquals("KR", toKr.country)
        assertEquals(mapOf("name" to "Jane", "address_line1" to "1 Main St", "postal_code" to "73301"), toKr.values)
        assertEquals("5125550100", toKr.phone)
        assertEquals("door", toKr.note)
        // back to the US: a state that is not one of the options is dropped, a known one stays
        val back = AddressRules.carry(toKr.with("admin_area", "TX"), us)
        assertNull(back.values["admin_area"])
        assertEquals("CA", AddressRules.carry(toKr.with("admin_area", "CA"), us).values["admin_area"])
    }

    @Test fun nationalPartOfAnE164Number() {
        assertEquals("1012345678", AddressRules.nationalPart("+821012345678", "+82"))
        assertEquals("6502530000", AddressRules.nationalPart("+16502530000", "+1"))
        // another country's number, a national one, or no calling code: as it is
        assertEquals("+16502530000", AddressRules.nationalPart("+16502530000", "+82"))
        assertEquals("010-1234-5678", AddressRules.nationalPart("010-1234-5678", "+82"))
        assertEquals("+821012345678", AddressRules.nationalPart("+821012345678", ""))
        assertEquals("+82", AddressRules.nationalPart("+82", "+82"))
    }

    @Test fun profilesRead() {
        val list = read(ShippingProfiles.serializer(), profiles).profiles
        assertEquals(2, list.size)
        val home = list[0]
        assertEquals("ship_1a2b3c4d5e6f", home.id)
        assertEquals("집", home.label)
        assertTrue(home.isDefault)
        assertEquals("+821012345678", home.phone)
        assertEquals("문 앞에 놓아 주세요", home.note)
        assertNull(home.lastUsed)
        assertEquals("서울특별시 강남구 테헤란로 152, 10층 1004호, 06236", home.oneLine)
        assertEquals(listOf("서울특별시 강남구 테헤란로 152", "10층 1004호", "홍길동", "06236"), AddressRules.lines(home.formatted))
        assertEquals(mapOf("name" to "홍길동", "address_line1" to "서울특별시 강남구 테헤란로 152", "address_line2" to "10층 1004호", "postal_code" to "06236"), home.values())
        assertEquals("CA", list[1].value("admin_area"))
        assertEquals("Mountain View", list[1].value(AddressKeys.LOCALITY))
        assertFalse(list[1].isDefault)
        // nothing personal in logs
        assertFalse(home.toString().contains("홍길동"))
        assertFalse(AddressRules.draftOf(home).toString().contains("홍길동"))
        // the wallet answer carries them too; an older one does not
        val w = read(WalletInfo.serializer(), """{"cards":[],"shipping":${profiles.substringAfter("\"profiles\":").dropLast(1)}}""")
        assertEquals(2, w.shipping!!.size)
        assertNull(read(WalletInfo.serializer(), """{"cards":[]}""").shipping)
    }

    @Test fun draftFromAProfileShowsTheNumberWithoutTheCallingCode() {
        val home = read(ShippingProfiles.serializer(), profiles).profiles[0]
        val d = AddressRules.draftOf(home, kr)
        assertEquals("KR", d.country)
        assertEquals("1012345678", d.phone)
        assertEquals("+821012345678", AddressRules.draftOf(home).phone)
        assertTrue(AddressRules.isValid(kr, d))
    }

    @Test fun bodiesCarryEveryKeySoAChangedCountryLeavesNothingBehind() {
        val d = AddressDraft("KR", mapOf("name" to " 홍길동 ", "postal_code" to "06236", "address_line1" to "테헤란로 152", "locality" to "Austin"), "010-1234-5678", " 문 앞 ")
        val a = AddressRules.address(kr, d)
        assertEquals("KR", a["country"]!!.jsonPrimitive.content)
        assertEquals("홍길동", a["name"]!!.jsonPrimitive.content)
        assertEquals("", a["locality"]!!.jsonPrimitive.content)   // not a KR field
        assertEquals("", a["admin_area"]!!.jsonPrimitive.content)
        assertEquals("010-1234-5678", a["phone"]!!.jsonPrimitive.content)
        assertEquals("문 앞", a["note"]!!.jsonPrimitive.content)
        assertEquals((listOf("country") + AddressKeys.FIELDS + listOf("phone", "note")).toSet(), a.keys)
        assertFalse(a.containsKey("organization"))

        val create = AddressRules.profileBody(kr, d, " 집 ", true)
        assertEquals("집", create["label"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), create["default"])
        assertFalse(AddressRules.profileBody(kr, d, "회사", null).containsKey("default"))

        val answer = AddressRules.answerBody(kr, d, save = true, label = "집", payment = "card")
        assertEquals("KR", answer["address"]!!.jsonObject["country"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), answer["save"])
        assertEquals("card", answer["payment"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, AddressRules.answerBody(kr, d, save = false, label = "", payment = null)["payment"])
    }

    @Test fun countriesReadSortAndSearch() {
        val c = read(AddressCountries.serializer(), """{"countries":[{"code":"AC","name":"Ascension Island"},{"code":"KR","name":"South Korea"},{"code":"AE","name":"United Arab Emirates"}],"default":"KR"}""")
        assertEquals("KR", c.defaultCountry)
        val shown = mapOf("AC" to "어센션 섬", "KR" to "대한민국", "AE" to "아랍에미리트")
        val sorted = AddressRules.sorted(c.countries, { shown.getValue(it) }, java.text.Collator.getInstance(java.util.Locale.KOREAN))
        assertEquals(listOf("KR", "AE", "AC"), sorted.map { it.code })
        assertEquals(listOf("KR"), AddressRules.search(c.countries, "대한", { shown.getValue(it) }).map { it.code })
        assertEquals(listOf("KR"), AddressRules.search(c.countries, "korea", { shown.getValue(it) }).map { it.code })
        assertEquals(listOf("AE"), AddressRules.search(c.countries, "ae", { shown.getValue(it) }).map { it.code })
        assertEquals(3, AddressRules.search(c.countries, " ", { shown.getValue(it) }).size)
    }

    @Test fun shippingAskReadsTheEvent() {
        val o = Json.parseToJsonElement("""{"type":"shipping_form","id":"call_5","form_id":"sf_7c1e2a","site":"shop.example.com","what":"블루 머그 2개",
            "country":"kr","prefill":{"name":"홍길동","phone":"010-1234-5678","postal_code":"","address_line1":"서울특별시 강남구 테헤란로 152","address_line2":"","x":null},
            "label":"집","save":true,"ask_payment":true,"expires_at":1762650600}""").jsonObject
        val a = ShippingAsk.parse(o)
        assertEquals("call_5", a.id)
        assertEquals("sf_7c1e2a", a.formId)
        assertEquals("KR", a.country)
        assertEquals("집", a.label)
        assertTrue(a.save)
        assertTrue(a.askPayment)
        assertEquals(1762650600L, a.expiresAt)
        assertFalse(a.prefill.containsKey("x"))
        assertTrue(a.expired(1762650600))
        assertFalse(a.expired(1762650599))
        val d = AddressRules.draftOf(a)
        assertEquals(mapOf("name" to "홍길동", "address_line1" to "서울특별시 강남구 테헤란로 152"), d.values)
        assertEquals("010-1234-5678", d.phone)
        // a sparse event: save starts on, no payment question, never expires
        val sparse = ShippingAsk.parse(Json.parseToJsonElement("""{"id":"c","form_id":"sf","prefill":{}}""").jsonObject)
        assertTrue(sparse.save)
        assertFalse(sparse.askPayment)
        assertFalse(sparse.expired(Long.MAX_VALUE))
        assertTrue(sparse.prefill.isEmpty())
    }

    @Test fun orderPartsRead() {
        val items = OrderItem.parseList(Json.parseToJsonElement("""[{"name":"블루 머그","options":"색상: 네이비","quantity":2,"price_text":"24,000원"},
            {"name":"","quantity":1},{"name":"양말","quantity":3.0},"x"]"""))
        assertEquals(listOf(OrderItem("블루 머그", "색상: 네이비", 2, "24,000원"), OrderItem("양말", "", 3, "")), items)
        assertTrue(OrderItem.parseList(null).isEmpty())
        val s = OrderShipping.parse(Json.parseToJsonElement("""{"source":"site","label":"","name":"","phone":"","formatted":"서울 어딘가","country":"KR"}"""))!!
        assertTrue(s.fromSite)
        assertEquals("서울 어딘가", s.formatted)
        assertNull(OrderShipping.parse(JsonNull))
        assertNull(OrderShipping.parse(JsonObject(emptyMap())))
        assertEquals("집 · 홍길동 · 010-1234-5678", AddressRules.who("집", "홍길동", "010-1234-5678"))
        assertEquals("홍길동", AddressRules.who("", " 홍길동 ", ""))
        assertEquals("1600 Amphitheatre Pkwy, Bldg 43, MOUNTAIN VIEW, CA 94043", AddressRules.oneLine("1600 Amphitheatre Pkwy\nBldg 43\n\nMOUNTAIN VIEW, CA 94043"))
    }

    @Test fun formAnswerReads() {
        val a = read(ShippingFormAnswer.serializer(), """{"ok":true,"profile":null,"formatted":"서울\n06236"}""")
        assertTrue(a.ok)
        assertNull(a.profile)
        assertEquals("서울, 06236", AddressRules.oneLine(a.formatted))
    }
}
