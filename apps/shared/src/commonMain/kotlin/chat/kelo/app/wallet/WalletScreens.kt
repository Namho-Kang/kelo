package chat.kelo.app.wallet

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.keyguard.KeyguardNeededDialog
import chat.kelo.app.keyguard.rememberKeyguardOn
import chat.kelo.app.agent.ScheduleGrant

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.Bar
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.my.appLocale
import chat.kelo.app.my.whenText
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The list: new auto-payments, the cards as small card faces, and the way to the limits and the history. How the cards are kept
 * safe is one tap away behind the (i) in the bar; a new card is added with the button at the bottom.
 */
@Composable
internal fun WalletList(
    info: WalletInfo, keyId: String?, notices: List<WalletPayment>, onBack: () -> Unit, go: (WalletView) -> Unit,
    onChanged: () -> Unit = {}, onRevoke: (ScheduleGrant) -> Unit = {},
) {
    val canAdd = LocalAppServices.current.cardKeys.available
    val c = Kelo.colors
    var explain by remember { mutableStateOf(false) }
    // a card's row "turn on auto-pay": the notice, then this phone's own copy opened with the owner's check (no retyping)
    val turnOn = rememberAutoPayOn(go = go, onChanged = onChanged)
    // auto-pay works only with Keyguard on: with it off, auto-pay cards show as paused (owner's decision)
    val keyguardOn = rememberKeyguardOn().on
    val keyguardPause = keyguardOn == false && info.cards.any { AutoPayGate.pausedForKeyguard(it, keyguardOn) }
    Box(Modifier.fillMaxSize()) {
        SubPage(string(Res.string.wallet_title), onBack, actions = {
            IconButton(onClick = { explain = true }) { Icon(KeloIcons.Info, contentDescription = string(Res.string.wallet_safe_title), tint = c.ink) }
        }) {
            if (notices.isNotEmpty()) {
                MyCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(KeloIcons.Card, contentDescription = null, tint = c.success, modifier = Modifier.size(20.dp))
                        Text(
                            plural(Res.plurals.wallet_notices_title, notices.size, notices.size),
                            fontWeight = FontWeight.Bold, color = c.ink, style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    notices.take(5).forEach { p ->
                        Text(
                            string(Res.string.wallet_notice_line, p.site, p.amountText.ifEmpty { moneyText(p.amount, p.currency) }, p.cardLabel, whenText(p.at)),
                            style = MaterialTheme.typography.bodySmall, color = c.ink,
                        )
                    }
                }
            }
            if (info.paused) {
                MyCard(onClick = { go(WalletView.Limits) }) {
                    Text(string(Res.string.wallet_paused_banner), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // a device without a card key (the desktop) never adds a card: the phone does
            if (!canAdd) MyCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(KeloIcons.Phone, contentDescription = null, tint = c.accent)
                    Text(string(Res.string.wallet_add_title), fontWeight = FontWeight.Bold, color = c.ink, style = MaterialTheme.typography.bodyLarge)
                }
                MutedText(string(Res.string.desktop_ops_wallet_note), small = true)
            }
            if (info.cards.isEmpty()) Note(string(Res.string.wallet_no_cards))
            Column {
                info.cards.forEach { card -> CardRow(card, keyId, keyguardOn, onTurnOn = { turnOn(card) }) { go(WalletView.Card(card.id)) } }
            }
            // where Kelo ships what it orders, next to what it pays with (absent on a server without addresses)
            info.shipping?.let { list ->
                ShippingSection(list, onOpen = { p -> go(WalletView.ShippingEdit(p.id)) }, onAdd = { go(WalletView.ShippingEdit(null)) })
            }
            SectionLabel(string(Res.string.wallet_auto_section))
            val autoCount = info.autoCount
            MenuGroup {
                row {
                    // how many cards really pay on their own now: never just "on" while every card asks
                    MenuRow(
                        KeloIcons.Shield, string(Res.string.wallet_limits_title), onClick = { go(WalletView.Limits) },
                        value = when {
                            info.paused || keyguardPause -> string(Res.string.wallet_auto_paused_value)
                            autoCount == 0 -> string(Res.string.wallet_auto_none_value)
                            else -> plural(Res.plurals.wallet_auto_cards_value, autoCount, autoCount)
                        },
                    )
                }
                row { MenuRow(KeloIcons.Receipt, string(Res.string.wallet_history_title), onClick = { go(WalletView.Payments) }) }
            }
            if (info.cards.isNotEmpty() && autoCount == 0 && !info.paused) Note(string(Res.string.wallet_auto_none_note))
            if (autoCount > 0 && !keyguardPause) Note(string(Res.string.wallet_first_site_rule))
            if (info.scheduleGrants.isNotEmpty()) {
                SectionLabel(string(Res.string.wallet_schedule_section))
                chat.kelo.app.agent.ScheduleGrantRows(info.scheduleGrants, showSchedule = true, onRevoke = onRevoke)
                Note(string(Res.string.grant_section_note))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(72.dp))
        }
        if (canAdd) ExtendedFloatingActionButton(
            onClick = { go(WalletView.Add) }, containerColor = c.accent, contentColor = c.onAccent,
            icon = { Icon(KeloIcons.Plus, contentDescription = null) }, text = { Text(string(Res.string.wallet_add_title)) },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false }, containerColor = c.card,
            icon = { Icon(KeloIcons.Lock, contentDescription = null, tint = c.accent) },
            title = { Text(string(Res.string.wallet_safe_title), color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(string(Res.string.wallet_safe_body), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                    Text(string(Res.string.wallet_relock_note), color = c.muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { explain = false }) { Text(string(Res.string.ok), color = c.accent) } },
        )
    }
}

/**
 * One card in the list: its face, its name, expiry and last use, whether it pays on its own or why it asks, and what state it is
 * in. A card saved to ask each time offers turning auto-pay on right here ([onTurnOn]: the number and CVC are asked for again).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardRow(card: WalletCard, keyId: String?, keyguardOn: Boolean?, onTurnOn: () -> Unit, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CardFaceThumb(CardBrand.of(card.brand), card.last4, dimmed = card.expired)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(card.label, color = c.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            cardSubtitle(card)?.let { Text(it, color = c.muted, style = MaterialTheme.typography.bodySmall) }
            Text(
                string(Res.string.wallet_card_meta, Expiry.display(card.expMonth, card.expYear), whenText(card.lastUsed)),
                color = c.muted, style = MaterialTheme.typography.bodySmall,
            )
            autoLine(card, keyguardOn)?.let { Text(it, color = c.ink, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusTags(card, keyId, keyguardOn)
            }
            if (card.offersTurnOn) LinkButton(string(Res.string.wallet_auto_turn_on_card), onClick = onTurnOn)
        }
    }
}

/** Whether the card pays on its own, or why it asks each time, in one line (null for a card that cannot pay at all). */
@Composable
internal fun autoLine(card: WalletCard, keyguardOn: Boolean? = null): String? = when {
    card.expired || !card.usable -> null
    AutoPayGate.pausedForKeyguard(card, keyguardOn) -> string(Res.string.wallet_auto_paused_keyguard)
    card.autoNow -> string(Res.string.wallet_row_pays_auto)
    else -> reasonText(card.reason)
}

@Composable
internal fun reasonText(reason: String): String = string(
    when (reason) {
        WalletCard.REASON_NO_SERVER_COPY -> Res.string.wallet_reason_no_server_copy
        WalletCard.REASON_PAUSED -> Res.string.wallet_reason_paused
        WalletCard.REASON_VAULT_LOCKED -> Res.string.wallet_reason_vault_locked
        else -> Res.string.wallet_reason_not_auto
    },
)

/** The line under a card's nickname: brand and last 4 ("Visa ••4242"); null when the nickname already is exactly that. */
internal fun cardSubtitle(card: WalletCard): String? {
    val b = CardBrand.of(card.brand)
    return CardMask.subtitle(card.label, brandName(b) ?: brandShort(b), card.last4)
}

@Composable
private fun StatusTags(card: WalletCard, keyId: String?, keyguardOn: Boolean? = null) {
    if (card.isDefault) CardTag(string(Res.string.wallet_tag_default), TagTone.ACCENT)
    // paused until Keyguard is on: "Paused", not "Asks each time" (an auto-only card cannot be asked on this phone)
    if (AutoPayGate.pausedForKeyguard(card, keyguardOn)) CardTag(string(Res.string.wallet_auto_paused_value), TagTone.DANGER)
    else if (card.autoNow) CardTag(string(Res.string.wallet_tag_auto), TagTone.ACCENT)
    else if (!card.expired && card.usable) CardTag(string(Res.string.wallet_tag_asks))
    when (CardStatusRules.of(card, keyId)) {
        CardStatus.EXPIRED -> CardTag(string(Res.string.wallet_tag_expired), TagTone.DANGER)
        CardStatus.NOT_ON_PHONE -> CardTag(string(Res.string.wallet_tag_not_here), TagTone.DANGER)
        CardStatus.UNFINISHED -> CardTag(string(Res.string.wallet_tag_unfinished), TagTone.DANGER)
        CardStatus.AUTO_ONLY -> CardTag(string(Res.string.wallet_tag_auto_only))
        CardStatus.USABLE -> CardTag(string(Res.string.wallet_tag_ready))
    }
}

/**
 * One card: its nickname (the title) with brand and last 4, its state, whether it is the default card, auto-pay on or off, the
 * payments Kelo made with it, and removing it. The auto-pay limits live on the wallet list, not here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardPage(
    card: WalletCard, keyId: String?, onBack: () -> Unit, go: (WalletView) -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit,
    cards: List<WalletCard> = listOf(card),
) {
    val services = LocalAppServices.current
    val vm = rememberViewModel(card.id) { CardViewModel(services.sessions, services.walletConsent, card.id) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events) { e -> if (e == WalletEvent.Changed) onChanged() else if (e == WalletEvent.Deleted) onDeleted() }
    val c = Kelo.colors
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }
    var newDefault by remember { mutableStateOf<String?>(null) }   // deleting the default: the card picked to take its place
    val turnOn = rememberAutoPayOn(go = go)
    val keyguard = rememberKeyguardOn()
    var keyguardFor by remember { mutableStateOf(false) }
    if (keyguardFor) KeyguardNeededDialog(
        onEnabled = { keyguardFor = false; keyguard.refresh(); onChanged() }, onDismiss = { keyguardFor = false })
    val canAdd = services.cardKeys.available
    val status = CardStatusRules.of(card, keyId)
    val msg = ui.msg

    SubPage(card.label, onBack) {
        CardFaceLarge(
            CardBrand.of(card.brand), string(Res.string.wallet_card_masked, CardMask.last4(card.last4)), card.holder,
            Expiry.display(card.expMonth, card.expYear), modifier = Modifier.padding(horizontal = 24.dp),
        )
        cardSubtitle(card)?.let { Text(it, color = c.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterHorizontally)) }
        MyCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { StatusTags(card, keyId, keyguard.on) }
            MutedText(string(Res.string.wallet_card_dates, whenText(card.createdAt), whenText(card.lastUsed)), small = true)
        }
        when (status) {
            CardStatus.NOT_ON_PHONE, CardStatus.UNFINISHED -> MyCard {
                ErrorText(string(Res.string.wallet_not_here_body), small = true)
                if (canAdd) SecondaryButton(string(Res.string.wallet_reenter_title), onClick = { go(WalletView.Reenter(card.id, autoPay = false)) }, modifier = Modifier.fillMaxWidth())
            }
            CardStatus.AUTO_ONLY -> MyCard {
                MutedText(string(Res.string.wallet_auto_only_body), small = true)
                if (canAdd) SecondaryButton(string(Res.string.wallet_reenter_title), onClick = { go(WalletView.Reenter(card.id, autoPay = false)) }, modifier = Modifier.fillMaxWidth())
            }
            CardStatus.EXPIRED -> MyCard { ErrorText(string(Res.string.wallet_expired_body), small = true) }
            CardStatus.USABLE -> {}
        }

        MyCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(string(Res.string.wallet_auto_title), color = c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = card.autoPay, enabled = !card.expired && (canAdd || card.autoPay),
                    onCheckedChange = { on -> if (on) turnOn(card) else turningOff = true },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.bg),
                )
            }
            MutedText(string(if (card.autoPay) Res.string.wallet_auto_on_body else Res.string.wallet_auto_off_body), small = true)
            // an auto-pay card on a server without Keyguard: paused (each payment asks on the phone) until Keyguard is on
            if (AutoPayGate.pausedForKeyguard(card, keyguard.on)) {
                Text(string(Res.string.wallet_auto_paused_keyguard), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(string(Res.string.keyguard_turn_on_named), onClick = { keyguardFor = true }, modifier = Modifier.fillMaxWidth())
            }
            if (!card.autoNow && !card.expired && card.usable) {
                // why this card asks each time, and the way to change it (this phone opens its own copy; no retyping)
                Text(reasonText(card.reason), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                if (!card.autoPay) {
                    if (canAdd) {
                        SecondaryButton(string(Res.string.wallet_auto_turn_on_card), onClick = { turnOn(card) }, modifier = Modifier.fillMaxWidth())
                        MutedText(string(Res.string.wallet_auto_from_phone), small = true)
                    } else MutedText(string(Res.string.desktop_ops_wallet_on_phone), small = true)
                }
            }
            MutedText(string(Res.string.wallet_first_site_rule), small = true)
            MutedText(string(Res.string.wallet_auto_tip), small = true)
        }
        MyCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(string(Res.string.wallet_default_title), color = c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (card.isDefault) CardTag(string(Res.string.wallet_tag_default), TagTone.ACCENT)
            }
            MutedText(string(Res.string.wallet_default_on), small = true)
            if (!card.isDefault && !card.expired) {
                SecondaryButton(string(Res.string.wallet_make_default), onClick = vm::makeDefault, modifier = Modifier.fillMaxWidth())
            }
        }
        MenuGroup {
            row { MenuRow(KeloIcons.Edit, string(Res.string.wallet_nickname), onClick = { renaming = true }, value = card.label) }
        }
        SectionLabel(string(Res.string.wallet_card_history))
        CardHistory(ui.history, onMore = vm::loadMore) { link ->
            when (link) {
                PaymentLink.Chat -> services.openRequests.value = chat.kelo.app.notify.OpenTarget.Chat
                is PaymentLink.Task -> services.openRequests.value = chat.kelo.app.notify.OpenTarget.Task(link.id)
                PaymentLink.None -> {}
            }
        }
        MenuGroup { row { MenuRow(KeloIcons.Trash, string(Res.string.wallet_delete), onClick = { deleting = true }, danger = true) } }
        if (msg.isNotEmpty()) ErrorText(msg, small = true)
    }

    if (turningOff) {
        ConfirmDialog(
            text = string(
                if (status == CardStatus.USABLE) Res.string.wallet_auto_off_confirm else Res.string.wallet_auto_off_confirm_unusable,
            ),
            yes = string(Res.string.wallet_auto_turn_off), danger = status != CardStatus.USABLE,
            onDismiss = { turningOff = false },
            onYes = vm::turnAutoPayOff,
        )
    }
    val choices = DefaultCardRules.choicesOnDelete(card, cards)
    if (deleting && choices != null) {
        // the default card with two or more others: which one Kelo pays with from now on
        val picked = newDefault ?: DefaultCardRules.suggested(choices)?.id
        AlertDialog(
            onDismissRequest = { deleting = false; newDefault = null }, containerColor = c.card,
            title = { Text(string(Res.string.wallet_delete_default_title), color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(string(Res.string.wallet_delete_default_body, card.label), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                    choices.forEach { other ->
                        Row(
                            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable(role = Role.RadioButton) { newDefault = other.id }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.RadioButton(selected = picked == other.id, onClick = { newDefault = other.id },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.accent))
                            Column(Modifier.weight(1f)) {
                                Text(other.label, color = c.ink, style = MaterialTheme.typography.bodyMedium)
                                cardSubtitle(other)?.let { Text(it, color = c.muted, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    val next = picked
                    newDefault = null
                    vm.delete(next)
                }, enabled = picked != null) { Text(string(Res.string.delete), color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false; newDefault = null }) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    } else if (deleting) {
        ConfirmDialog(
            text = string(Res.string.wallet_delete_confirm, card.label), yes = string(Res.string.delete),
            onDismiss = { deleting = false },
            onYes = { vm.delete() },
        )
    }
    if (renaming) RenameDialog(card.label, onDismiss = { renaming = false }, onSave = vm::rename)
}

/**
 * Turning auto-pay on for a card: the notice (ticked), then this phone's own end-to-end copy opened with the owner's fingerprint,
 * face or screen lock and sent once to the auto-pay endpoint (the number is never typed again, never shown or logged). A card this
 * phone holds no copy of (added on another device) says so, with typing the number as the last resort. Returns "start for this card".
 */
@Composable
internal fun rememberAutoPayOn(go: (WalletView) -> Unit, onChanged: () -> Unit = {}): (WalletCard) -> Unit {
    val services = LocalAppServices.current
    val vm = rememberViewModel { AutoPayViewModel(services.sessions, services.cardKeys) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events) { e -> if (e == WalletEvent.Changed) onChanged() }
    val c = Kelo.colors
    // auto-pay needs Keyguard: with it off (or not known yet) the dialog comes first, and turning Keyguard on goes on from there
    val keyguard = rememberKeyguardOn()
    ui.needKeyguard?.let {
        KeyguardNeededDialog(onEnabled = { keyguard.refresh(); vm.keyguardEnabled() }, onDismiss = vm::dismissKeyguard)
    }
    val title = string(Res.string.wallet_auth_auto_title)
    val subtitleOf = string(Res.string.wallet_auth_auto_subtitle)
    ui.asking?.let {
        AutoPayDialog(onDismiss = vm::dismissAsking, onConfirm = { vm.confirm(title) { card -> String.format(subtitleOf, card.label) } })
    }
    ui.notHere?.let { card ->
        AlertDialog(
            onDismissRequest = vm::dismissNotHere, containerColor = c.card,
            title = { Text(string(Res.string.wallet_auto_not_here_title), color = c.ink) },
            text = { Text(string(Res.string.wallet_auto_not_here_body), color = c.ink, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { vm.dismissNotHere(); go(WalletView.Reenter(card.id, autoPay = true)) }) {
                    Text(string(Res.string.wallet_auto_enter_number), color = c.accent)
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissNotHere) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    }
    if (ui.failed) {
        AlertDialog(
            onDismissRequest = vm::dismissFailed, containerColor = c.card,
            text = { Text(string(Res.string.wallet_auto_on_failed), color = c.ink, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = vm::dismissFailed) { Text(string(Res.string.ok), color = c.accent) } },
        )
    }
    return { card -> vm.request(card, keyguard.on) }
}

/** The payments Kelo made with one card, newest first, older ones a page at a time; each opens its chat or task when known. */
@Composable
private fun CardHistory(history: CardHistoryState, onMore: () -> Unit, onOpen: (PaymentLink) -> Unit) {
    val c = Kelo.colors
    val items = history.items
    val next = history.next
    val loading = history.loading
    val msg = history.msg
    val list = items
    when {
        list == null -> SkeletonRows(2)
        list.isEmpty() -> EmptyNote(string(Res.string.wallet_card_history_empty))
        else -> MenuGroup {
            list.forEach { p ->
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardTag(string(if (p.auto) Res.string.wallet_kind_auto else Res.string.wallet_kind_approved), if (p.auto) TagTone.ACCENT else TagTone.PLAIN)
                            Text(p.site.ifEmpty { p.host }, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(p.amountText.ifEmpty { moneyText(p.amount, p.currency) }, color = c.ink, fontWeight = FontWeight.SemiBold)
                        }
                        if (p.summary.isNotEmpty()) Text(p.summary, color = c.ink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(whenText(p.at), color = c.muted, style = MaterialTheme.typography.bodySmall)
                        when (val link = CardHistoryRules.link(p)) {
                            PaymentLink.Chat -> LinkButton(string(Res.string.wallet_open_chat), onClick = { onOpen(link) })
                            is PaymentLink.Task -> LinkButton(string(Res.string.wallet_open_task), onClick = { onOpen(link) })
                            PaymentLink.None -> {}
                        }
                    }
                }
            }
        }
    }
    if (next != null && !list.isNullOrEmpty()) {
        SecondaryButton(string(Res.string.wallet_card_history_more), onClick = onMore, enabled = !loading, modifier = Modifier.fillMaxWidth())
    }
    if (msg.isNotEmpty()) ErrorText(msg, small = true)
}

/** Before auto-pay goes on: what it means, ticked, then 켜기 (this phone then opens its own copy of the card). */
@Composable
private fun AutoPayDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Kelo.colors
    var agreed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(string(Res.string.wallet_auto_confirm_title), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(string(Res.string.wallet_auto_notice), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                // no retyping: this phone opens its own copy of the card after the owner's check
                Text(string(Res.string.wallet_auto_from_phone), color = c.muted, style = MaterialTheme.typography.bodySmall)
                AgreeRow(agreed, onAgree = { agreed = it })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = agreed) { Text(string(Res.string.wallet_auto_confirm_yes), color = if (agreed) c.accent else c.muted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Kelo.colors
    // the cursor starts after the current name, ready to change it
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(current.length))) }
    val name = field.text
    // a name never holds card digits (the server refuses that too)
    val digitsInside = Regex("(?:\\d[ -]?){12,}").containsMatchIn(name)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(string(Res.string.wallet_nickname), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = field, onValueChange = { v -> field = if (v.text.length <= 60) v else field },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { chat.kelo.app.ui.components.FieldPlaceholder(string(Res.string.wallet_rename_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.card, unfocusedContainerColor = c.card, focusedBorderColor = c.accent,
                        unfocusedBorderColor = c.line, cursorColor = c.accent, focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                    ),
                )
                MutedText(string(Res.string.wallet_rename_note), small = true)
                if (digitsInside) ErrorText(string(Res.string.wallet_rename_no_digits), small = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSave(name) }, enabled = !digitsInside) { Text(string(Res.string.save), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}

/**
 * 자동결제 한도: the global pause, and per currency the limit for one payment and for a month with what was paid automatically this
 * month. Each can go back to the default; another currency can be added.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitsPage(paused: Boolean, onBack: () -> Unit, onPaused: () -> Unit) {
    val services = LocalAppServices.current
    val vm = rememberViewModel { LimitsViewModel(services.sessions, paused) }
    val ui by vm.state.collectAsStateWithLifecycle()
    HandleWalletEvents(vm.events) { e -> if (e == WalletEvent.Changed) onPaused() }
    val c = Kelo.colors
    var adding by remember { mutableStateOf(false) }
    val editing = ui.editing
    val home = LimitRules.homeCurrencies(appLocale(), systemLocale())

    SubPage(string(Res.string.wallet_limits_title), onBack) {
        MyCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(string(Res.string.wallet_pause_title), color = c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = ui.paused, onCheckedChange = vm::setPaused,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.danger, checkedThumbColor = c.bg),
                )
            }
            MutedText(string(if (ui.paused) Res.string.wallet_pause_on_body else Res.string.wallet_pause_off_body), small = true)
        }
        Note(string(Res.string.wallet_limits_help))
        val d = ui.data
        if (d == null) { SkeletonRows(3); return@SubPage }
        if (d.month.isNotEmpty()) Note(string(Res.string.wallet_limits_month, d.month, d.timezone))
        val shown = LimitRules.visible(d.limits, home, ui.added + listOfNotNull(editing))
        shown.forEach { row ->
            LimitCard(
                row, editing = editing == row.currency, onEdit = { vm.toggleEdit(row.currency) },
                onSave = { per, mon -> vm.save(row.currency, per, mon) },
                onReset = { vm.reset(row.currency) },
            )
        }
        // a currency the user is adding that the server does not know yet
        editing?.takeIf { cur -> d.limits.none { it.currency == cur } }?.let { cur ->
            LimitCard(
                WalletLimit(cur), editing = true, onEdit = vm::stopEditing,
                onSave = { per, mon -> vm.save(cur, per, mon, newToServer = true) },
                onReset = vm::stopEditing,
            )
        }
        if (ui.msg.isNotEmpty()) ErrorText(ui.msg, small = true)
        MenuGroup { row { MenuRow(KeloIcons.Plus, string(Res.string.wallet_limits_add), onClick = { adding = true }) } }
        if (adding) AddCurrencyDialog(LimitRules.addable(d.limits, shown), onDismiss = { adding = false }) { cur ->
            adding = false
            vm.addCurrency(cur)
        }
    }
}

@Composable
private fun LimitCard(row: WalletLimit, editing: Boolean, onEdit: () -> Unit, onSave: (Double, Double) -> Unit, onReset: () -> Unit) {
    val c = Kelo.colors
    MyCard(onClick = if (editing) null else onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.currency, fontWeight = FontWeight.Bold, color = c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            CardTag(
                string(
                    when {
                        row.added -> Res.string.wallet_limit_added
                        row.custom -> Res.string.wallet_limit_custom
                        else -> Res.string.wallet_limit_default
                    },
                ),
                if (row.custom || row.added) TagTone.ACCENT else TagTone.PLAIN,
            )
        }
        if (row.monthly <= 0 && row.perPayment <= 0 && (row.custom || row.added)) {
            MutedText(string(Res.string.wallet_limit_never), small = true)
        } else {
            Text(
                string(Res.string.wallet_limit_line, moneyText(row.perPayment, row.currency), moneyText(row.monthly, row.currency)),
                color = c.ink, style = MaterialTheme.typography.bodyMedium,
            )
            Bar(LimitRules.used(row.spentThisMonth, row.monthly), hot = row.spentThisMonth >= row.monthly && row.monthly > 0)
            MutedText(string(Res.string.wallet_limit_spent, moneyText(row.spentThisMonth, row.currency)), small = true)
        }
        if (editing) LimitEditor(row, onSave, onReset, onCancel = onEdit)
    }
}

@Composable
private fun LimitEditor(row: WalletLimit, onSave: (Double, Double) -> Unit, onReset: () -> Unit, onCancel: () -> Unit) {
    val known = row.custom || row.defaultPerPayment != null
    var per by remember(row.currency) { mutableStateOf(if (known) Money.plain(row.perPayment, row.currency) else "") }
    var mon by remember(row.currency) { mutableStateOf(if (known) Money.plain(row.monthly, row.currency) else "") }
    val perV = Money.parse(per, row.currency)
    val monV = Money.parse(mon, row.currency)
    val check = LimitRules.check(perV, monV)
    val decimals = Money.decimals(row.currency) > 0
    val kb = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number)
    WalletField(string(Res.string.wallet_limit_per, row.currency), per, { per = it.take(16) }, keyboard = kb,
        error = if (per.isNotEmpty() && perV == null) string(Res.string.wallet_limit_bad_number) else null)
    WalletField(string(Res.string.wallet_limit_month, row.currency), mon, { mon = it.take(16) }, keyboard = kb,
        error = when {
            mon.isNotEmpty() && monV == null -> string(Res.string.wallet_limit_bad_number)
            check == LimitCheck.PER_ABOVE_MONTHLY -> string(Res.string.wallet_limit_per_above)
            else -> null
        })
    MutedText(string(Res.string.wallet_limit_zero_note), small = true)
    row.defaultPerPayment?.let { dp ->
        MutedText(string(Res.string.wallet_limit_default_line, moneyText(dp, row.currency), moneyText(row.defaultMonthly ?: 0.0, row.currency)), small = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(string(Res.string.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
        PrimaryButton(string(Res.string.save), onClick = { if (perV != null && monV != null) onSave(perV, monV) }, enabled = check == LimitCheck.OK, modifier = Modifier.weight(1f))
    }
    if (row.custom) {
        LinkButton(string(if (row.added) Res.string.wallet_limit_remove else Res.string.wallet_limit_reset), onClick = onReset, danger = row.added)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCurrencyDialog(options: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Kelo.colors
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(string(Res.string.wallet_limits_add), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KeloTextField(code, { code = it.uppercase().filter { ch -> ch in 'A'..'Z' }.take(3) }, string(Res.string.wallet_currency_hint),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.take(18).forEach { o -> chat.kelo.app.ui.components.ChoiceChip(o, selected = o == code) { code = o } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(code) }, enabled = Money.validCode(code)) { Text(string(Res.string.wallet_currency_add), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}

/** 결제 내역: every payment that went ahead, newest first, automatic or allowed on the phone. */
@Composable
internal fun PaymentsPage(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val vm = rememberViewModel { PaymentsViewModel(services.sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val c = Kelo.colors
    val msg = ui.msg
    SubPage(string(Res.string.wallet_history_title), onBack) {
        val list = ui.items
        if (list == null) { SkeletonRows(4); return@SubPage }
        if (msg.isNotEmpty()) ErrorText(msg, small = true)
        if (list.isEmpty()) EmptyNote(string(Res.string.wallet_history_empty))
        MenuGroup {
            list.forEach { p ->
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardTag(string(if (p.auto) Res.string.wallet_kind_auto else Res.string.wallet_kind_approved), if (p.auto) TagTone.ACCENT else TagTone.PLAIN)
                            Text(p.site.ifEmpty { p.host }, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(p.amountText.ifEmpty { moneyText(p.amount, p.currency) }, color = c.ink, fontWeight = FontWeight.SemiBold)
                        }
                        if (p.summary.isNotEmpty()) Text(p.summary, color = c.ink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (p.cardLabel.isEmpty()) whenText(p.at) else string(Res.string.wallet_history_meta, p.cardLabel, whenText(p.at)),
                            color = c.muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
