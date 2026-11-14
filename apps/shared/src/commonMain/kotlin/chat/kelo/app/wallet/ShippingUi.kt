package chat.kelo.app.wallet

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.SelectField
import chat.kelo.app.chat.SelectOption
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.my.appLocale
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * The country list and the address forms, kept in memory for this process (they change only with a server update). Forms are kept
 * per language, since their labels come in the language asked for.
 */
internal object AddressCache {
    private var countries: AddressCountries? = null
    private val forms = HashMap<String, AddressForm>()

    suspend fun countries(api: KeloApi): AddressCountries =
        countries ?: ShippingCalls(api).countries().also { if (it.countries.isNotEmpty()) countries = it }

    suspend fun form(api: KeloApi, country: String, lang: String): AddressForm {
        val key = "$lang:${country.uppercase()}"
        forms[key]?.let { return it }
        return ShippingCalls(api).form(country).also { synchronized(forms) { forms[key] = it } }
    }

    /** A country's form already loaded (no call). */
    fun known(country: String, lang: String): AddressForm? = forms["$lang:${country.uppercase()}"]
}

/** A country's name in the app's language (the English name from the server when the phone does not know the code). */
internal fun countryName(code: String, english: String, locale: Locale): String {
    if (code.isEmpty()) return english
    val shown = Locale("", code).getDisplayCountry(locale)
    return if (shown.isBlank() || shown.equals(code, ignoreCase = true)) english.ifEmpty { code } else shown
}

/** A saved E.164 number as people write it in that country ("010-1234-5678"), else as it is. */
internal fun phoneShown(phone: String, country: String): String {
    return chat.kelo.app.ui.platform.phoneShown(phone, country)
}

/** What goes in the phone box next to the calling code when a saved number is edited: the national form when it is that country's. */
internal fun phoneForField(phone: String, form: AddressForm): String {
    val p = phone.trim()
    if (!p.startsWith("+")) return p
    val national = AddressRules.nationalPart(p, form.phone.callingCode)
    if (national == p) return p
    val local = phoneShown(p, form.country)
    return if (local.startsWith("+")) national else local
}

/** Keeps an [AddressDraft] through scrolling away and configuration changes (flat list of strings). */
internal val AddressDraftSaver: Saver<AddressDraft, ArrayList<String>> = Saver(
    save = { d -> arrayListOf(d.country, d.phone, d.note).apply { d.values.forEach { (k, v) -> add(k); add(v) } } },
    restore = { l ->
        val values = l.drop(3).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
        AddressDraft(l.getOrElse(0) { "" }, values, l.getOrElse(1) { "" }, l.getOrElse(2) { "" })
    },
)

/** The words of a problem under a field. */
@Composable
internal fun problemText(p: AddressProblem, field: AddressField?): String = when (p) {
    AddressProblem.REQUIRED -> string(Res.string.shipping_err_required)
    AddressProblem.PHONE -> string(Res.string.shipping_err_phone)
    AddressProblem.PATTERN -> field?.example?.takeIf { it.isNotBlank() }?.let { string(Res.string.shipping_err_pattern, it) }
        ?: string(Res.string.shipping_err_format)
}

/** The label as shown: with "(optional)" when the field may stay empty. */
@Composable
internal fun fieldLabelText(text: String, optional: Boolean, optionalWord: String): String =
    if (optional) string(Res.string.shipping_label_optional, text, optionalWord.ifBlank { string(Res.string.shipping_optional) }) else text

/** A field's label, with "(optional)" when it may stay empty; it wraps onto more lines in any language, never cut. */
@Composable
internal fun FieldLabel(text: String, optional: Boolean, optionalWord: String) {
    val c = Kelo.colors
    Text(
        fieldLabelText(text, optional, optionalWord),
        style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(start = 2.dp),
    )
}

/** A text box of the address form (the wallet's look); [lead]: a fixed prefix inside the box (the calling code). */
@Composable
internal fun AddressBox(
    value: String, onChange: (String) -> Unit, placeholder: String = "", keyboard: KeyboardOptions = KeyboardOptions.Default,
    error: String? = null, lead: String? = null, leadDescription: String? = null, singleLine: Boolean = true, enabled: Boolean = true,
    /** What the field is for: read by the screen reader (the visible label is a separate line above). */
    label: String? = null,
) {
    val c = Kelo.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 4, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { label?.let { contentDescription = it } },
            placeholder = if (placeholder.isEmpty()) null else ({ chat.kelo.app.ui.components.FieldPlaceholder(placeholder, singleLine) }),
            leadingIcon = lead?.let { code ->
                {
                    Text(
                        code, color = c.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp).semantics { leadDescription?.let { contentDescription = it } },
                    )
                }
            },
            keyboardOptions = keyboard, isError = error != null,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.card, unfocusedContainerColor = c.card, errorContainerColor = c.card, disabledContainerColor = c.card,
                focusedBorderColor = c.accent, unfocusedBorderColor = c.line, errorBorderColor = c.danger, cursorColor = c.accent,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink, errorTextColor = c.ink, disabledTextColor = c.muted,
            ),
        )
        if (error != null) ErrorText(error, small = true, modifier = Modifier.padding(start = 2.dp))
    }
}

/** The country picker: every country the server knows, by its name in the app's language, with a search for the long list. */
@Composable
internal fun CountryPicker(label: String, countries: List<AddressCountry>, selected: String, onPick: (String) -> Unit) {
    val locale = appLocale()
    val options = remember(countries, locale, selected) {
        val list = countries.ifEmpty { listOf(AddressCountry(selected, selected)) }
        val collator = Collator.getInstance(locale)
        AddressRules.sorted(list, { code -> countryName(code, list.firstOrNull { it.code == code }?.name.orEmpty(), locale) }, collator)
            .map { SelectOption(it.code, countryName(it.code, it.name, locale)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, optional = false, optionalWord = "")
        SelectField(label, options, selected, wrap = true, onSelect = onPick)
    }
}

/**
 * The address form as the server lays it out for [form]'s country: its fields in order (a picker for one with options, a postal
 * code with its example as the hint and a number keyboard when it is digits), then the phone with the calling code in front, then
 * the delivery note. [problems] show under their fields.
 */
@Composable
internal fun AddressFieldRows(
    form: AddressForm, draft: AddressDraft, onDraft: (AddressDraft) -> Unit, problems: Map<String, AddressProblem>, enabled: Boolean = true,
) {
    val optional = form.labels.optional
    form.fields.forEachIndexed { i, f ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(f.label.ifBlank { f.key }, optional = !f.required, optionalWord = optional)
            val err = problems[f.key]?.let { problemText(it, f) }
            if (f.isPicker) {
                val choose = string(Res.string.shipping_pick)
                val opts = remember(f) { listOf(SelectOption("", choose, keep = true)) + f.options!!.map { SelectOption(it.key, it.name) } }
                SelectField(f.label, opts, draft.value(f.key), wrap = true) { v -> onDraft(draft.with(f.key, v)) }
                if (err != null) ErrorText(err, small = true, modifier = Modifier.padding(start = 2.dp))
            } else {
                val digitsOnly = f.example?.all { it.isDigit() } != false
                AddressBox(
                    draft.value(f.key), { v -> onDraft(draft.with(f.key, v.take(200))) },
                    placeholder = f.example?.takeIf { it.isNotBlank() }?.let { string(Res.string.shipping_example, it) }.orEmpty(),
                    keyboard = KeyboardOptions(
                        keyboardType = when {
                            f.numeric && digitsOnly -> KeyboardType.Number
                            f.numeric -> KeyboardType.Phone
                            else -> KeyboardType.Text
                        },
                        capitalization = if (f.key == AddressKeys.POSTAL_CODE) KeyboardCapitalization.Characters else KeyboardCapitalization.Words,
                        autoCorrectEnabled = false,
                        imeAction = if (i == form.fields.lastIndex) ImeAction.Next else ImeAction.Next,
                    ),
                    error = err, enabled = enabled, label = fieldLabelText(f.label.ifBlank { f.key }, !f.required, optional),
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(form.phone.label, optional = !form.phone.required, optionalWord = optional)
        AddressBox(
            draft.phone, { v -> onDraft(draft.copy(phone = v.take(30))) },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            error = problems[AddressKeys.PHONE]?.let { problemText(it, null) },
            lead = form.phone.callingCode.ifBlank { null },
            leadDescription = form.phone.callingCode.takeIf { it.isNotBlank() }?.let { string(Res.string.shipping_calling_code, it) },
            enabled = enabled, label = fieldLabelText(form.phone.label, !form.phone.required, optional),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(form.note.label, optional = !form.note.required, optionalWord = optional)
        AddressBox(
            draft.note, { v -> onDraft(draft.copy(note = v.take(300))) }, singleLine = false,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), enabled = enabled,
            label = fieldLabelText(form.note.label, !form.note.required, optional),
        )
    }
}

/** The form's place while it loads, or why it did not. */
@Composable
internal fun FormPending(failed: String?, onRetry: () -> Unit) {
    if (failed != null) {
        ErrorText(string(Res.string.shipping_form_failed) + (if (failed.isNotBlank()) "\n" + failed else ""), small = true)
        SecondaryButton(string(Res.string.retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
    } else {
        SkeletonLines(4)
    }
}

/**
 * The wallet page's shipping addresses, under the cards: each with its nickname, a "Default" tag on the default one, the address on
 * up to two lines and the name; a tap opens it. An empty list says what the addresses are for; "Add address" is always there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShippingSection(profiles: List<ShippingProfile>, onOpen: (ShippingProfile) -> Unit, onAdd: () -> Unit) {
    val c = Kelo.colors
    SectionLabel(string(Res.string.shipping_section))
    if (profiles.isEmpty()) Note(string(Res.string.shipping_empty))
    Column {
        profiles.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(p) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(KeloIcons.MapPin, contentDescription = null, tint = c.muted, modifier = Modifier.padding(top = 2.dp).size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            p.label.ifBlank { string(Res.string.shipping_home) }, color = c.ink, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        if (p.isDefault) Row(Modifier.align(Alignment.CenterVertically)) { CardTag(string(Res.string.wallet_tag_default), TagTone.ACCENT) }
                    }
                    val line = p.oneLine.ifBlank { AddressRules.oneLine(p.formatted) }
                    if (line.isNotBlank()) Text(line, color = c.ink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val who = listOf(p.name, phoneShown(p.phone, p.country)).filter { it.isNotBlank() }.joinToString(" · ")
                    if (who.isNotBlank()) Text(who, color = c.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    MenuGroup { row { MenuRow(KeloIcons.Plus, string(Res.string.shipping_add), onClick = onAdd) } }
}

/** What 배송지 추가 / 수정 holds while typing (kept through the wallet's lock, in memory only, by [WalletPlaces]). */
data class ShippingEditDraft(
    val profileId: String?, val label: String, val address: AddressDraft, val makeDefault: Boolean, val touched: Boolean = false,
) {
    override fun toString(): String = "ShippingEditDraft(profile=$profileId)"
}

/**
 * Adding or changing a shipping address: the nickname first ("Home" for a new one), the country, the server's fields for that
 * country in order, the phone and the delivery note, then "use as default". Save checks the required fields and the postal code
 * here first; the server's own refusal (422) shows as it words it. A saved address can be deleted after a confirm.
 */
@Composable
internal fun ShippingEditPage(
    profile: ShippingProfile?, firstOne: Boolean, onBack: () -> Unit, onSaved: () -> Unit, onDeleted: () -> Unit,
) {
    val services = LocalAppServices.current
    val c = Kelo.colors
    val home = string(Res.string.shipping_home)
    val lang = appLocale().toLanguageTag()
    val vm = rememberViewModel(profile?.id) { ShippingEditViewModel(services.sessions, services.walletPlaces, profile, firstOne, home, lang) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events) { e -> if (e == WalletEvent.Saved) onSaved() else if (e == WalletEvent.Deleted) onDeleted() }
    var deleting by remember { mutableStateOf(false) }
    val form = ui.shownForm
    val edit = ui.edit

    SubPage(string(if (profile == null) Res.string.shipping_add else Res.string.shipping_edit_title), onBack) {
        MyCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(form?.labels?.nickname?.ifBlank { null } ?: string(Res.string.shipping_nickname), optional = false, optionalWord = "")
                AddressBox(
                    edit.label, vm::setLabel,
                    placeholder = string(Res.string.shipping_nickname_hint),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
            }
            CountryPicker(form?.labels?.country?.ifBlank { null } ?: string(Res.string.shipping_country), ui.countries.countries, ui.country, vm::setCountry)
            if (form == null) FormPending(ui.form.failed, vm::retryForm)
            else AddressFieldRows(form, edit.address, vm::setAddress, if (ui.tried) ui.problems else emptyMap())
        }
        MyCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(string(Res.string.shipping_default_title), color = c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = edit.makeDefault, enabled = profile?.isDefault != true && !firstOne,
                    onCheckedChange = vm::setMakeDefault,
                    // the default one (or the first address) can't be switched off here, but it must still read as on
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = c.accent, checkedThumbColor = c.bg,
                        disabledCheckedTrackColor = c.accent.copy(alpha = 0.55f), disabledCheckedThumbColor = c.bg,
                    ),
                )
            }
            MutedText(string(Res.string.shipping_default_note), small = true)
        }
        if (ui.msg.isNotEmpty()) ErrorText(ui.msg, small = true, modifier = Modifier.padding(horizontal = 4.dp))
        SaveButton(string(Res.string.save), enabled = form != null, busy = ui.busy, onClick = vm::save)
        if (profile != null) {
            MenuGroup { row { MenuRow(KeloIcons.Trash, string(Res.string.shipping_delete), onClick = { deleting = true }, danger = true) } }
        }
    }
    if (deleting && profile != null) {
        ConfirmDialog(
            text = string(Res.string.shipping_delete_confirm, profile.label.ifBlank { home }), yes = string(Res.string.delete),
            onDismiss = { deleting = false },
            onYes = vm::delete,
        )
    }
}
