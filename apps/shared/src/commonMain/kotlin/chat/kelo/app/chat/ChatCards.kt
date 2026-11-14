package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.Motion

/**
 * A tool waiting for a yes or no. Once answered (or expired) the card stays as it was asked, without its buttons, and a chip on
 * it says how it ended.
 */
@Composable
fun ApprovalCard(a: ChatItem.Approval, onAnswer: (String, Boolean, Boolean) -> Unit, onKeepForSchedule: (String) -> Unit = {}) {
    val c = Kelo.colors
    CardBox {
        val payment = a.payment
        if (a.urgent && a.open) chat.kelo.app.agent.UrgentLine(a.missed)
        if (payment != null) {
            PurchaseApproval(a, payment, onAnswer = { ok -> onAnswer(a.id, ok, false) })
            return@CardBox
        }
        val grant = a.grant
        if (grant != null) {
            // the setup card of a "when X happens, do Y" task: its own layout (trigger, action, limits, the card's auto-pay limit)
            chat.kelo.app.agent.TaskGrantApproval(grant, a.open, outcome = { Outcomes.of(a)?.let { OutcomeChip(it) } }) { ok -> onAnswer(a.id, ok, false) }
            return@CardBox
        }
        var rememberSite by remember(a.id) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ChatAsks.question(a.ask) ?: string(Res.string.chat_approval_ask, ProviderConsentRules.askWord(a.shown, textLocale().language), ProviderConsentRules.objectParticle(a.shown)), color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Outcomes.of(a)?.let { OutcomeChip(it) }
        }
        // what it will do, in words: the readable pairs, else the server's sentence; the raw arguments are never shown
        ApprovalFacts(a.lines, a.detail)
        if (a.items.isNotEmpty()) ApprovalItems(a.items)
        if (a.open) {
            if (a.canRemember && a.site.isNotBlank()) {
                chat.kelo.app.agent.RememberSiteCheck(a.site, rememberSite, enabled = true) { rememberSite = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(string(Res.string.chat_approval_deny), onClick = { onAnswer(a.id, false, false) }, modifier = Modifier.weight(1f))
                PrimaryButton(string(Res.string.chat_approval_allow), onClick = { onAnswer(a.id, true, rememberSite) }, modifier = Modifier.weight(1f))
            }
            if (a.canRememberSchedule) LinkButton(string(Res.string.approval_keep_for_schedule), onClick = { onKeepForSchedule(a.id) })
        }
    }
}

/** The items a permission for many covers, behind "see the list (37)": the user sees exactly what the one Allow is for. */
@Composable
fun ApprovalItems(items: List<String>) {
    val c = Kelo.colors
    var open by remember(items) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinkButton(string(if (open) Res.string.chat_approval_list_hide else Res.string.chat_approval_list_show, items.size), onClick = { open = !open })
        if (open) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(10.dp)).background(c.bubble)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEachIndexed { i, line ->
                    Text("${i + 1}. $line", style = MaterialTheme.typography.bodySmall, color = c.ink)
                }
            }
        }
    }
}

/** An approval's readable pairs as a small two-column list, or its one sentence when there are none. */
@Composable
fun ApprovalFacts(lines: List<ApprovalLine>, detail: String?) {
    val c = Kelo.colors
    if (lines.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bubble).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEach { l ->
                if (l.full) {
                    // the whole thing, as it will be sent (e.g. a text message): the label above, every word below
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (l.label.isNotEmpty()) Text(l.label, style = MaterialTheme.typography.bodySmall, color = c.muted)
                        Text(l.value, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (l.label.isNotEmpty()) Text(l.label, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.widthIn(min = 56.dp, max = 110.dp))
                    Text(chat.kelo.app.ui.components.TokenText.fit(l.value, 28), style = MaterialTheme.typography.bodySmall, color = c.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    } else if (!detail.isNullOrBlank()) {
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.ink)
    }
}
