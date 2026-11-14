package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.CheckInfo
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.PurchaseApproval
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.Card
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo

/**
 * One approval waiting for an answer. A payment is answered here the same way as in the chat: with a card kept only on this
 * phone, Allow first asks for the owner's check and sends the card.
 */
@Composable
fun PendingCard(vm: AgentViewModel, a: PendingApproval, working: Boolean) {
    val haptics = LocalHaptics.current
    val sessions = LocalAppServices.current.sessions
    val senders = chat.kelo.app.work.LocalCardSenders.current
    var cardError by remember { mutableStateOf<chat.kelo.app.wallet.CardSendError?>(null) }
    val container = LocalAppServices.current
    if (a.isHumanCheck) {
        Card {
            HumanCheckBody(a, working, onSolve = { haptics.tick(); container.openRequests.value = chat.kelo.app.notify.OpenTarget.Tab(a.tab, control = true) },
                onDone = { haptics.confirm(); vm.answer(a, true) },
                onSkip = { haptics.reject(); vm.answer(a, false) })
        }
        return
    }
    Card {
        PendingApprovalBody(a, working, cardError) { ok, rememberSite, rememberSchedule ->
            val card = a.event.card
            if (ok && card != null && card.needsCard) {
                haptics.tick()
                vm.answer(a, true, beforeAllow = {
                    val err = senders.card(a.id, card.card.id)()
                    cardError = err
                    err == null
                })
            } else {
                if (ok) haptics.confirm() else haptics.reject()
                vm.answer(a, ok, remember = rememberSite, rememberSchedule = rememberSchedule)
            }
        }
    }
}

/**
 * What a waiting approval shows and offers, the same on the 작업 page and in the chat: a payment as the chat's purchase card;
 * anything else as the question, the site, what it will do, when it was asked, 거절 / 허용, the "don't ask again on this site" checkbox when the server allows
 * remembering it, and "keep allowing in this scheduled job" when that is offered. [onAnswer]: (allow, remember the site, remember
 * for the scheduled job); sending an end-to-end card first is the caller's part.
 */
@Composable
fun PendingApprovalBody(
    a: PendingApproval, working: Boolean, cardError: chat.kelo.app.wallet.CardSendError?,
    onAnswer: (Boolean, Boolean, Boolean) -> Unit,
) {
    val c = Kelo.colors
    val payment = a.event.payment
    if (a.event.urgent) UrgentLine(a.event.missed)
    a.event.grant?.let { grant ->
        // the setup card of a "when X happens, do Y" task, the same as in the chat
        TaskGrantApproval(grant, open = !working) { ok -> onAnswer(ok, false, false) }
        return
    }
    if (payment != null) {
        // the chat's own purchase card: where, what, how much, which card
        val item = ChatItem.Approval(
            key = a.id, id = a.id, name = a.event.name, args = "", detail = a.event.detail, label = a.event.label,
            payment = payment, card = a.event.card,
            send = when {
                working -> chat.kelo.app.chat.CardSend.Working
                cardError != null -> chat.kelo.app.chat.CardSend.Failed(cardError)
                else -> chat.kelo.app.chat.CardSend.Idle
            },
        )
        PurchaseApproval(item, payment) { ok -> onAnswer(ok, false, false) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var rememberSite by remember(a.id) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(KeloIcons.Shield, contentDescription = null, tint = c.accent)
            Text(chat.kelo.app.chat.ChatAsks.question(a.event.ask) ?: a.event.label.ifBlank { a.event.name }.let { w -> string(Res.string.chat_approval_ask, chat.kelo.app.chat.ProviderConsentRules.askWord(w, textLocale().language), chat.kelo.app.chat.ProviderConsentRules.objectParticle(w)) }, style = MaterialTheme.typography.titleMedium,
                color = c.ink, modifier = Modifier.weight(1f))
        }
        if (a.site.isNotBlank() && a.event.lines.isEmpty()) MutedText(a.site, small = true)
        chat.kelo.app.chat.ApprovalFacts(a.event.lines, a.event.detail)
        if (a.event.items.isNotEmpty()) chat.kelo.app.chat.ApprovalItems(a.event.items)
        if (a.askedAt > 0) Text(string(Res.string.agent_perm_asked, relText(a.askedAt)), style = MaterialTheme.typography.labelSmall, color = c.muted)
        if (a.canRemember && a.site.isNotBlank() && a.event.payment == null) {
            RememberSiteCheck(a.site, rememberSite, enabled = !working) { rememberSite = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(string(Res.string.chat_approval_deny), enabled = !working, modifier = Modifier.weight(1f), onClick = { onAnswer(false, false, false) })
            PrimaryButton(string(Res.string.chat_approval_allow), enabled = !working, modifier = Modifier.weight(1f), onClick = { onAnswer(true, rememberSite, false) })
        }
        if (a.canRememberSchedule) {
            LinkButton(string(Res.string.approval_keep_for_schedule), onClick = { onAnswer(true, false, true) })
        }
    }
}

/**
 * "Don't ask again on this site" under an approval's question (OFF by default) and, under it, what a rule really covers: the site and
 * all its sub-addresses (activity.py). The whole line ticks the box.
 */
@Composable
fun RememberSiteCheck(site: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Kelo.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = c.accent))
            Text(string(Res.string.approval_remember_site), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
        Text(string(Res.string.approval_remember_scope, site), style = MaterialTheme.typography.bodySmall, color = c.muted,
            modifier = Modifier.padding(start = 48.dp))
    }
}

/**
 * A site's check whether a person is there, handed to the user (the same on the 작업 page and in the chat): who asks, one short
 * sentence, "직접 풀기" (the tab live with control taken; the card ends by itself once the check is gone) and "이 사이트 건너뛰기"
 * (the agent goes on without that site and says so). The buttons sit one under the other so long words in any language fit.
 * A sign-in approved on the phone ([CheckInfo.isApproveStep]): "I approved it" first ([onDone]: done, go on), the live view second.
 */
@Composable
fun HumanCheckBody(a: PendingApproval, working: Boolean, onSolve: () -> Unit, onDone: () -> Unit, onSkip: () -> Unit) {
    val c = Kelo.colors
    val site = a.site.ifBlank { string(Res.string.handover_this_site) }
    // a sign-in's second step (approve on the phone, or a code) is not a person check: its own words, the server's step first
    val verify = a.isVerification
    val approveStep = a.event.check?.isApproveStep == true
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (verify) KeloIcons.Shield else KeloIcons.User, contentDescription = null, tint = c.accent)
            Text(string(if (verify) Res.string.handover_verify_title else Res.string.handover_title, site),
                style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
        }
        val step = a.event.check?.name.orEmpty()
        if (verify && step.isNotBlank()) Text(step, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        Text(string(when { approveStep -> Res.string.handover_verify_approve_body; verify -> Res.string.handover_verify_body; else -> Res.string.handover_body }), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        if (a.askedAt > 0) Text(string(Res.string.agent_perm_asked, relText(a.askedAt)), style = MaterialTheme.typography.labelSmall, color = c.muted)
        if (approveStep) {
            PrimaryButton(string(Res.string.handover_verify_approved), enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = onDone)
            SecondaryButton(string(Res.string.handover_verify_open), enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = onSolve)
        } else {
            PrimaryButton(string(if (verify) Res.string.handover_verify_open else Res.string.handover_solve), enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = onSolve)
        }
        SecondaryButton(string(Res.string.handover_skip), enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = onSkip)
    }
}

@Composable
fun RuleRow(r: ApprovalRule, onRevoke: () -> Unit) {
    val c = Kelo.colors
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.site, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ruleKind(r), style = MaterialTheme.typography.bodySmall, color = c.muted)
            Text(plural(Res.plurals.agent_rule_uses, r.uses, r.uses), style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
        IconButton(onClick = { confirm = true }) {
            Icon(KeloIcons.Trash, contentDescription = string(Res.string.agent_rule_revoke, r.site), tint = c.muted)
        }
    }
    if (confirm) {
        ConfirmDialog(string(Res.string.agent_rule_revoke_confirm, r.site), string(Res.string.agent_rule_revoke_yes), onDismiss = { confirm = false }, onYes = onRevoke)
    }
}

/** What a rule allows, in the app's words (the server's label for kinds it does not know). */
@Composable
private fun ruleKind(r: ApprovalRule): String = when (r.kind) {
    "browser_action" -> string(Res.string.agent_rule_kind_browser)
    "login" -> string(Res.string.agent_rule_kind_login)
    else -> r.kindLabel.ifBlank { r.kind }
}
