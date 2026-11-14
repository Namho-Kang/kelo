package chat.kelo.app.chat

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.wallet.AddressBox
import chat.kelo.app.wallet.AddressDraftSaver
import chat.kelo.app.wallet.AddressFieldRows
import chat.kelo.app.wallet.AddressRules
import chat.kelo.app.wallet.CountryPicker
import chat.kelo.app.wallet.FieldLabel
import chat.kelo.app.wallet.FormPending
import chat.kelo.app.wallet.ShippingAsk
import chat.kelo.app.wallet.ShippingAskViewModel
import chat.kelo.app.my.appLocale
import chat.kelo.app.ui.platform.rememberViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.wallet.HandleWalletEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The agent asks where an order goes (ask_shipping): an address form in the chat, in the look of the chat's answer buttons. The
 * site and what is ordered, the country (starting at the one asked with), that country's fields prefilled with what Kelo remembers,
 * the phone and the delivery note, "use for next orders" (on, with the nickname while it is on) and, when asked, how to pay. Send
 * checks the required fields and the postal code here first; the server's refusal (422) shows under the form and keeps it open.
 * Answered, cancelled or gone, it folds into one line; once its time is up or the reply is over it shows as expired.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShippingFormCard(item: ChatItem.ShippingForm, onOutcome: (ShippingOutcome) -> Unit) {
    CardBox(Modifier.animateContentSize(FoldSpec)) {
        val ask = item.ask
        var timedOut by remember(ask.formId, ask.expiresAt) { mutableStateOf(ask.expired(System.currentTimeMillis() / 1000)) }
        LaunchedEffect(ask.formId, ask.expiresAt) {
            if (ask.expiresAt <= 0) return@LaunchedEffect
            while (!timedOut) {
                val left = ask.expiresAt * 1000 - System.currentTimeMillis()
                if (left <= 0) timedOut = true else delay(left.coerceAtMost(30_000))
            }
        }
        when {
            item.state == ShippingFormState.DONE -> ResultLine(
                KeloIcons.MapPin,
                string(Res.string.shipping_ask_done, listOf(item.doneLabel, item.doneLine).filter { it.isNotBlank() }.joinToString(" · ")),
            )
            item.state == ShippingFormState.CANCELLED -> ResultLine(KeloIcons.Close, string(Res.string.shipping_ask_cancelled), muted = true)
            item.state == ShippingFormState.CLOSED -> ResultLine(KeloIcons.Clock, string(Res.string.shipping_ask_closed), muted = true)
            item.state == ShippingFormState.EXPIRED || timedOut -> ResultLine(KeloIcons.Clock, string(Res.string.shipping_ask_expired), muted = true)
            else -> ShippingFormBody(ask, onOutcome)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShippingFormBody(ask: ShippingAsk, onOutcome: (ShippingOutcome) -> Unit) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val services = LocalAppServices.current
    val lang = appLocale().toLanguageTag()
    val vm = rememberViewModel(ask.formId) { ShippingAskViewModel(services.sessions, ask, lang) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events)
    LaunchedEffect(vm) { vm.outcomes.collect(onOutcome) }
    val countries = ui.countries
    // what is typed survives scrolling the card away and back (the list keeps saved state per row)
    var draft by rememberSaveable(ask.formId, stateSaver = AddressDraftSaver) { mutableStateOf(AddressRules.draftOf(ask)) }
    var save by rememberSaveable(ask.formId) { mutableStateOf(ask.save) }
    var label by rememberSaveable(ask.formId) { mutableStateOf(ask.label) }
    var payment by rememberSaveable(ask.formId) { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    val busy = ui.busy
    val msg = ui.msg
    val home = string(Res.string.shipping_home)
    val country = draft.country.ifEmpty { countries.defaultCountry }
    LaunchedEffect(country) { vm.showCountry(country) }
    val form = ui.form.form?.takeIf { it.country.equals(country, ignoreCase = true) }
    val problems = if (form != null) AddressRules.problems(form, draft) else emptyMap()

    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(KeloIcons.MapPin, contentDescription = null, tint = c.accent, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(string(Res.string.shipping_ask_title), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
    }
    if (ask.site.isNotBlank() || ask.what.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (ask.site.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(KeloIcons.Globe, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
                    Text(ask.site, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ask.what.isNotBlank()) Text(ask.what, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
    }
    CountryPicker(form?.labels?.country?.ifBlank { null } ?: string(Res.string.shipping_country), countries.countries, country) { cc ->
        if (!cc.equals(country, ignoreCase = true)) draft = draft.copy(country = cc)
    }
    if (form == null) FormPending(ui.form.failed, vm::retryForm)
    else {
        LaunchedEffect(form.country) {
            val carried = AddressRules.carry(draft, form)
            if (carried != draft) draft = carried
        }
        AddressFieldRows(form, draft, { draft = it }, if (tried) problems else emptyMap(), enabled = !busy)
    }
    // "use for next orders": the nickname shows while it is on
    Row(
        Modifier.fillMaxWidth().toggleable(value = save, role = Role.Checkbox, enabled = !busy, onValueChange = { save = it }).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = save, onCheckedChange = null, enabled = !busy,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted),
        )
        Text(string(Res.string.shipping_ask_save), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
    }
    if (save) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(form?.labels?.nickname?.ifBlank { null } ?: string(Res.string.shipping_nickname), optional = false, optionalWord = "")
            AddressBox(
                label, { label = it.take(40) }, placeholder = string(Res.string.shipping_nickname_hint),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), enabled = !busy,
            )
        }
    }
    if (ask.askPayment) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(string(Res.string.shipping_ask_pay), style = MaterialTheme.typography.labelLarge, color = c.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(string(Res.string.shipping_pay_card), selected = payment == ShippingAsk.PAY_CARD) {
                    payment = if (payment == ShippingAsk.PAY_CARD) null else ShippingAsk.PAY_CARD
                }
                ChoiceChip(string(Res.string.shipping_pay_site), selected = payment == ShippingAsk.PAY_SITE) {
                    payment = if (payment == ShippingAsk.PAY_SITE) null else ShippingAsk.PAY_SITE
                }
            }
        }
    }
    if (msg.isNotEmpty()) ErrorText(msg, small = true)
    fun send() {
        val f = form ?: return
        tried = true
        if (problems.isNotEmpty()) { haptics.reject(); return }
        vm.send(f, draft, save, label, payment, home)
    }
    // one under the other: long words in any language fit at 320dp
    PrimaryButton(
        string(if (busy) Res.string.shipping_ask_sending else Res.string.shipping_ask_send), onClick = ::send,
        enabled = form != null && !busy, modifier = Modifier.fillMaxWidth(),
    )
    SecondaryButton(string(Res.string.cancel), onClick = vm::cancel, enabled = !busy, modifier = Modifier.fillMaxWidth())
}
