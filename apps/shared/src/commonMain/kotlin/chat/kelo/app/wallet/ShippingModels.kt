package chat.kelo.app.wallet

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Shipping addresses, the server-driven address form, the in-chat address question and the order parts of a payment approval.
// Plain Kotlin only (no android.*): the desktop app uses this file too (the apps/shared/ module).

/** The address keys a profile and a form can carry, in the profile's own order. */
object AddressKeys {
    const val NAME = "name"
    const val ORGANIZATION = "organization"
    const val LINE1 = "address_line1"
    const val LINE2 = "address_line2"
    const val DEPENDENT_LOCALITY = "dependent_locality"
    const val LOCALITY = "locality"
    const val ADMIN_AREA = "admin_area"
    const val POSTAL_CODE = "postal_code"
    const val SORTING_CODE = "sorting_code"
    const val PHONE = "phone"
    const val NOTE = "note"

    /** The keys a form's fields can have (organization is kept on a profile but no form asks for it). */
    val FIELDS = listOf(NAME, LINE1, LINE2, DEPENDENT_LOCALITY, LOCALITY, ADMIN_AREA, POSTAL_CODE, SORTING_CODE)
}

/** One saved shipping address (GET /api/wallet/shipping). [formatted]: the country's own postal layout, with the name. */
@Serializable
data class ShippingProfile(
    val id: String = "",
    val label: String = "",
    val country: String = "",
    val name: String = "",
    val organization: String = "",
    @SerialName("address_line1") val addressLine1: String = "",
    @SerialName("address_line2") val addressLine2: String = "",
    @SerialName("dependent_locality") val dependentLocality: String = "",
    val locality: String = "",
    @SerialName("admin_area") val adminArea: String = "",
    @SerialName("postal_code") val postalCode: String = "",
    @SerialName("sorting_code") val sortingCode: String = "",
    /** E.164 ("+821012345678") */
    val phone: String = "",
    val note: String = "",
    @SerialName("default") val isDefault: Boolean = false,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("last_used") val lastUsed: Double? = null,
    val formatted: String = "",
    /** The address on one line, without the name */
    @SerialName("one_line") val oneLine: String = "",
) {
    /** The value of an address key ("" for a key it does not have). */
    fun value(key: String): String = when (key) {
        AddressKeys.NAME -> name
        AddressKeys.ORGANIZATION -> organization
        AddressKeys.LINE1 -> addressLine1
        AddressKeys.LINE2 -> addressLine2
        AddressKeys.DEPENDENT_LOCALITY -> dependentLocality
        AddressKeys.LOCALITY -> locality
        AddressKeys.ADMIN_AREA -> adminArea
        AddressKeys.POSTAL_CODE -> postalCode
        AddressKeys.SORTING_CODE -> sortingCode
        AddressKeys.PHONE -> phone
        AddressKeys.NOTE -> note
        else -> ""
    }

    /** The form keys that hold something. */
    fun values(): Map<String, String> = AddressKeys.FIELDS.associateWith(::value).filterValues { it.isNotEmpty() }

    override fun toString(): String = "ShippingProfile(id=$id, country=$country, default=$isDefault)"
}

@Serializable
data class ShippingProfiles(val profiles: List<ShippingProfile> = emptyList())

/** GET /api/address/countries. [name] is English: the apps show the name in the user's language. */
@Serializable
data class AddressCountry(val code: String, val name: String = "")

@Serializable
data class AddressCountries(val countries: List<AddressCountry> = emptyList(), @SerialName("default") val defaultCountry: String = "")

@Serializable
data class AddressOption(val key: String, val name: String = "")

/**
 * One row of an address form. [options]: a picker (US states, JP prefectures; the profile keeps the option's key); [pattern]: the
 * postal code's check (case-insensitive); [example]: the placeholder; [numeric]: a number keyboard.
 */
@Serializable
data class AddressField(
    val key: String,
    val label: String = "",
    val required: Boolean = false,
    val options: List<AddressOption>? = null,
    val pattern: String? = null,
    val example: String? = null,
    val numeric: Boolean = false,
) {
    val isPicker: Boolean get() = !options.isNullOrEmpty()

    /** The shown name of an option key ([key] itself when it is not one of the options, e.g. typed on an older form). */
    fun optionName(key: String): String = options?.firstOrNull { it.key == key }?.name ?: key
}

@Serializable
data class AddressPhone(val label: String = "", @SerialName("calling_code") val callingCode: String = "", val required: Boolean = true)

@Serializable
data class AddressNote(val label: String = "", val required: Boolean = false)

@Serializable
data class AddressLabels(val country: String = "", val nickname: String = "", val optional: String = "")

/** GET /api/address/form/{CC}: the fields in display order, labels in the request's language. */
@Serializable
data class AddressForm(
    val country: String = "",
    @SerialName("country_name") val countryName: String = "",
    val lang: String = "",
    val fields: List<AddressField> = emptyList(),
    val phone: AddressPhone = AddressPhone(),
    val note: AddressNote = AddressNote(),
    val labels: AddressLabels = AddressLabels(),
    val format: String = "",
) {
    fun field(key: String): AddressField? = fields.firstOrNull { it.key == key }
}

/** What POST /api/shipping-forms/{form_id} answers. */
@Serializable
data class ShippingFormAnswer(val ok: Boolean = false, val profile: ShippingProfile? = null, val formatted: String = "")

/** Why a field does not pass the local checks. */
enum class AddressProblem { REQUIRED, PATTERN, PHONE }

/** What the user typed into an address form. [values]: form keys -> text (an option's key for a picker). */
data class AddressDraft(
    val country: String = "",
    val values: Map<String, String> = emptyMap(),
    val phone: String = "",
    val note: String = "",
) {
    fun value(key: String): String = values[key].orEmpty()
    fun with(key: String, v: String): AddressDraft = copy(values = values + (key to v))

    override fun toString(): String = "AddressDraft(country=$country, keys=${values.keys})"
}

/** The address form's local checks and the bodies the calls send. */
object AddressRules {
    /** The postal code (or any patterned field) matches [pattern], case-insensitive; an empty value or an unreadable pattern passes. */
    fun matches(pattern: String?, value: String): Boolean {
        val v = value.trim()
        if (pattern.isNullOrEmpty() || v.isEmpty()) return true
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return true
        return re.matches(v)
    }

    /** At least a few digits, and nothing but what phone numbers are written with. */
    fun phoneLooksRight(phone: String): Boolean {
        val p = phone.trim()
        if (p.isEmpty()) return true
        if (!p.all { it.isDigit() || it in " +-().·/" }) return false
        return p.count { it.isDigit() } in 4..17
    }

    /**
     * Every key that does not pass, in the form's order (then "phone"): a required field left empty, a postal code that does not
     * fit its pattern, a picker value that is not one of its options, a phone number that is missing or not a number.
     */
    fun problems(form: AddressForm, draft: AddressDraft): Map<String, AddressProblem> {
        val out = LinkedHashMap<String, AddressProblem>()
        for (f in form.fields) {
            val v = draft.value(f.key).trim()
            when {
                v.isEmpty() && f.required -> out[f.key] = AddressProblem.REQUIRED
                v.isEmpty() -> {}
                f.isPicker && f.options!!.none { it.key == v } -> out[f.key] = AddressProblem.REQUIRED
                !matches(f.pattern, v) -> out[f.key] = AddressProblem.PATTERN
            }
        }
        val phone = draft.phone.trim()
        when {
            phone.isEmpty() && form.phone.required -> out[AddressKeys.PHONE] = AddressProblem.REQUIRED
            !phoneLooksRight(phone) -> out[AddressKeys.PHONE] = AddressProblem.PHONE
        }
        return out
    }

    fun isValid(form: AddressForm, draft: AddressDraft): Boolean = problems(form, draft).isEmpty()

    /** The country changed: the values of keys both forms have stay, the rest go (phone and note always stay). */
    fun carry(draft: AddressDraft, next: AddressForm): AddressDraft {
        val keys = next.fields.map { it.key }.toSet()
        val kept = draft.values.filterKeys { it in keys }.filter { (k, v) ->
            // a picker keeps only a value that is one of its options
            val f = next.field(k)
            f == null || !f.isPicker || f.options!!.any { it.key == v }
        }
        return draft.copy(country = next.country, values = kept)
    }

    /**
     * The number to show next to the calling-code chip: the part after [callingCode] when [phone] is E.164 in that country,
     * else [phone] as it is.
     */
    fun nationalPart(phone: String, callingCode: String): String {
        val p = phone.trim()
        if (callingCode.isEmpty() || !p.startsWith("+")) return p
        val code = callingCode.trim()
        return if (p.startsWith(code) && p.length > code.length) p.substring(code.length).trimStart(' ', '-') else p
    }

    /** A draft from a saved profile, the phone shown without the calling code of [form] (when given). */
    fun draftOf(p: ShippingProfile, form: AddressForm? = null): AddressDraft = AddressDraft(
        country = p.country, values = p.values(),
        phone = if (form != null) nationalPart(p.phone, form.phone.callingCode) else p.phone,
        note = p.note,
    )

    /**
     * A draft from the in-chat question's prefill (only form keys, phone and note are taken). The form is not known yet when it
     * is made, so every address key is kept and [carry] trims them once it is.
     */
    fun draftOf(ask: ShippingAsk): AddressDraft = AddressDraft(
        country = ask.country,
        values = ask.prefill.filterKeys { it in AddressKeys.FIELDS }.filterValues { it.isNotBlank() },
        phone = ask.prefill[AddressKeys.PHONE].orEmpty(),
        note = ask.prefill[AddressKeys.NOTE].orEmpty(),
    )

    /**
     * The address as the server takes it: country, every address key (the form's typed ones, "" for the others so a changed
     * country leaves nothing of the old one behind), the phone as typed (the server stores it as E.164) and the note.
     */
    fun address(form: AddressForm, draft: AddressDraft): JsonObject = buildJsonObject {
        put("country", form.country.ifEmpty { draft.country })
        val keys = form.fields.map { it.key }.toSet()
        for (k in AddressKeys.FIELDS) put(k, if (k in keys) draft.value(k).trim() else "")
        put(AddressKeys.PHONE, draft.phone.trim())
        put(AddressKeys.NOTE, draft.note.trim())
    }

    /** POST /api/wallet/shipping and PATCH /api/wallet/shipping/{id}: the address, the nickname and (when given) default. */
    fun profileBody(form: AddressForm, draft: AddressDraft, label: String, makeDefault: Boolean?): JsonObject {
        val a = address(form, draft)
        return JsonObject(buildMap {
            putAll(a)
            put("label", JsonPrimitive(label.trim()))
            if (makeDefault != null) put("default", JsonPrimitive(makeDefault))
        })
    }

    /** POST /api/shipping-forms/{form_id}: the answer to the in-chat question. [payment]: "card" | "site" | null. */
    fun answerBody(form: AddressForm, draft: AddressDraft, save: Boolean, label: String, payment: String?): JsonObject = JsonObject(buildMap {
        put("address", address(form, draft))
        put("save", JsonPrimitive(save))
        put("label", JsonPrimitive(label.trim()))
        put("payment", if (payment == null) JsonNull else JsonPrimitive(payment))
    })

    /** "Label · name · phone" for the order card and a folded form, empty parts left out. */
    fun who(label: String, name: String, phone: String): String = listOf(label, name, phone).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

    /** The address's lines (the server's layout), blank lines dropped. */
    fun lines(formatted: String): List<String> = formatted.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** A one-line address from a formatted one (for the folded in-chat form when the server sends no one_line). */
    fun oneLine(formatted: String): String = lines(formatted).joinToString(", ")

    /** Countries sorted by the name shown ([shown]: the code -> the name in the user's language). */
    fun sorted(countries: List<AddressCountry>, shown: (String) -> String, collate: Comparator<in String>): List<AddressCountry> =
        countries.sortedWith(compareBy(collate) { shown(it.code).ifEmpty { it.name } })

    /** The countries whose shown name, English name or code holds [query] (case-insensitive). */
    fun search(countries: List<AddressCountry>, query: String, shown: (String) -> String): List<AddressCountry> {
        val q = query.trim()
        if (q.isEmpty()) return countries
        return countries.filter { c ->
            shown(c.code).contains(q, ignoreCase = true) || c.name.contains(q, ignoreCase = true) || c.code.equals(q, ignoreCase = true)
        }
    }
}

/** The address endpoints and the wallet's shipping profiles. */
class ShippingCalls(private val api: KeloApi) {
    suspend fun countries(): AddressCountries = api.get("/api/address/countries")
    suspend fun form(country: String): AddressForm = api.get("/api/address/form/${KeloApi.segment(country.uppercase())}")
    suspend fun list(): List<ShippingProfile> = api.get<ShippingProfiles>("/api/wallet/shipping").profiles
    suspend fun get(id: String): ShippingProfile = api.get("/api/wallet/shipping/${KeloApi.segment(id)}")
    suspend fun create(body: JsonObject): ShippingProfile = api.post("/api/wallet/shipping", body)
    suspend fun update(id: String, body: JsonObject): ShippingProfile = api.patch("/api/wallet/shipping/${KeloApi.segment(id)}", body)
    suspend fun makeDefault(id: String): ShippingProfile = update(id, buildJsonObject { put("default", true) })
    suspend fun delete(id: String) { api.delete<JsonElement>("/api/wallet/shipping/${KeloApi.segment(id)}") }

    /** The answer to an in-chat address question (404: it is gone, expired or the reply stopped). */
    suspend fun answer(formId: String, body: JsonObject): ShippingFormAnswer = api.post("/api/shipping-forms/${KeloApi.segment(formId)}", body)
    suspend fun cancel(formId: String) { api.post<JsonElement>("/api/shipping-forms/${KeloApi.segment(formId)}", buildJsonObject { put("cancel", true) }) }
}

private fun JsonObject.txt(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

/**
 * The turn event "shipping_form": the agent asks once where an order goes (ask_shipping waits for the answer). [prefill]: what Kelo
 * remembers (address keys, "phone", "note"; may be empty); [save]: the "use for next orders" box starts on; [askPayment]: also ask
 * how to pay ("card" | "site"); [expiresAt]: unix seconds.
 */
data class ShippingAsk(
    val id: String,
    val formId: String,
    val site: String = "",
    val what: String = "",
    val country: String = "",
    val prefill: Map<String, String> = emptyMap(),
    val label: String = "",
    val save: Boolean = true,
    val askPayment: Boolean = false,
    val expiresAt: Long = 0,
) {
    fun expired(nowSeconds: Long): Boolean = expiresAt > 0 && nowSeconds >= expiresAt

    override fun toString(): String = "ShippingAsk(id=$id, form=$formId, country=$country)"

    companion object {
        const val PAY_CARD = "card"
        const val PAY_SITE = "site"

        fun parse(o: JsonObject): ShippingAsk {
            val pre = (o["prefill"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { k to it }
            }.toMap()
            return ShippingAsk(
                id = o.txt("id"), formId = o.txt("form_id"), site = o.txt("site"), what = o.txt("what"),
                country = o.txt("country").uppercase(), prefill = pre, label = o.txt("label"),
                save = (o["save"] as? JsonPrimitive)?.booleanOrNull ?: true,
                askPayment = (o["ask_payment"] as? JsonPrimitive)?.booleanOrNull == true,
                expiresAt = (o["expires_at"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L,
            )
        }
    }
}

/** One line of an order on the payment approval ("블루 머그", "색상: 네이비", 2, "24,000원"). */
data class OrderItem(val name: String, val options: String = "", val quantity: Int = 1, val priceText: String = "") {
    companion object {
        fun parseList(e: JsonElement?): List<OrderItem> = (e as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o.txt("name").trim()
            if (name.isEmpty()) null
            else OrderItem(name, o.txt("options").trim(), (o["quantity"] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 1, o.txt("price_text"))
        }
    }
}

/**
 * Where an order goes, on the payment approval. [source]: "profile" (a saved address), "once" (typed for this order only) or
 * "site" (the address the site account already had: then [name] and [phone] may be empty and [formatted] is the page's text).
 * [formatted] holds no name.
 */
data class OrderShipping(
    val source: String = "", val label: String = "", val name: String = "", val phone: String = "", val formatted: String = "",
    val country: String = "",
) {
    val fromSite: Boolean get() = source == SOURCE_SITE

    companion object {
        const val SOURCE_ONCE = "once"
        const val SOURCE_SITE = "site"

        fun parse(e: JsonElement?): OrderShipping? {
            val o = e as? JsonObject ?: return null
            val s = OrderShipping(o.txt("source"), o.txt("label"), o.txt("name"), o.txt("phone"), o.txt("formatted"), o.txt("country"))
            return if (s.formatted.isBlank() && s.name.isBlank() && s.label.isBlank()) null else s
        }
    }
}
