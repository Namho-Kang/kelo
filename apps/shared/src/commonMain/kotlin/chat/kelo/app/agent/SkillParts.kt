package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo

/**
 * The skills waiting for the owner on the Records page, under the learned rules: what Kelo proposed or drafted from a finished task
 * (the full text, or the changed lines of a change) and skills whose files changed outside the app. Approve writes exactly the text
 * shown and Kelo uses it from then on; Discard drops it (for a skill changed outside the app: puts the approved text back).
 */
@Composable
fun SkillProposalsBlock(data: SkillProposals?, onAnswer: (SkillProposal, Boolean, (Boolean) -> Unit) -> Unit) {
    if (data == null || !data.available) return
    val items = SkillRules.ordered(data.items)
    if (items.isEmpty() && data.drafting.isEmpty()) return
    val c = Kelo.colors
    Text(string(Res.string.skill_review_title), style = MaterialTheme.typography.labelLarge, color = c.accent,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
    MutedText(string(Res.string.skill_review_about), small = true)
    if (data.drafting.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = c.muted, strokeWidth = 2.dp)
            Text(string(Res.string.skill_review_drafting), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { p -> SkillProposalCard(p, onAnswer) }
    }
}

@Composable
private fun SkillProposalCard(p: SkillProposal, onAnswer: (SkillProposal, Boolean, (Boolean) -> Unit) -> Unit) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(12.dp)
    var all by rememberSaveable(p.id) { mutableStateOf(false) }
    // null: not answered; true / false: approved / discarded (shown until the card leaves the list)
    var decided by remember(p.id) { mutableStateOf<Boolean?>(null) }
    var sending by remember(p.id) { mutableStateOf(false) }
    val edited = p.kind == SkillProposal.EDITED
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.accent.copy(alpha = 0.08f)).border(1.dp, c.accent.copy(alpha = 0.45f), shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            string(when (p.kind) {
                SkillProposal.CHANGE -> Res.string.skill_review_kind_change
                SkillProposal.EDITED -> Res.string.skill_review_kind_edited
                else -> Res.string.skill_review_kind_new
            }),
            style = MaterialTheme.typography.labelMedium, color = c.accent,
        )
        Text(SkillRules.name(p), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink)
        if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        Text(originText(p), style = MaterialTheme.typography.bodySmall, color = c.muted)
        if (edited) Note(string(if (p.neverApproved) Res.string.skill_review_edited_new_note else Res.string.skill_review_edited_note))
        else if (p.outsideText) Note(string(Res.string.skill_review_outside_note))
        if (p.secretsRemoved > 0) Text(string(Res.string.skill_review_secrets_removed), style = MaterialTheme.typography.bodySmall, color = c.muted)
        // the text as it will be saved, or the lines a change adds and removes
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (SkillRules.showsDiff(p)) {
                val (added, removed) = SkillRules.counts(p)
                Text(string(Res.string.skill_review_diff_counts, added, removed), style = MaterialTheme.typography.labelSmall, color = c.muted,
                    modifier = Modifier.padding(bottom = 4.dp))
                SkillRules.preview(p.diff, all).forEach { DiffLine(it) }
            } else {
                Text(SkillRules.preview(SkillRules.textLines(p.text), all).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                    color = c.ink)
            }
        }
        val more = if (SkillRules.showsDiff(p)) SkillRules.hasMore(p.diff) else SkillRules.hasMore(SkillRules.textLines(p.text))
        if (more) LinkButton(string(if (all) Res.string.skill_review_show_less else Res.string.skill_review_show_all), onClick = { all = !all })
        val choice = decided
        if (choice == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(string(if (edited && !p.neverApproved) Res.string.skill_review_put_back else Res.string.skill_review_discard),
                    modifier = Modifier.weight(1f), enabled = !sending, onClick = {
                        haptics.reject(); sending = true; decided = false
                        onAnswer(p, false) { ok -> sending = false; if (!ok) decided = null }
                    })
                SmallPrimary(string(Res.string.skill_review_approve), Modifier.weight(1f)) {
                    if (!sending) {
                        haptics.confirm(); sending = true; decided = true
                        onAnswer(p, true) { ok -> sending = false; if (!ok) decided = null }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (choice) KeloIcons.Check else KeloIcons.Minus, contentDescription = null, tint = if (choice) c.accent else c.muted,
                    modifier = Modifier.size(18.dp))
                Text(string(if (choice) Res.string.skill_review_approved else Res.string.skill_review_discarded),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, modifier = Modifier.weight(1f))
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), color = c.muted, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun originText(p: SkillProposal): String {
    val from = string(when (p.origin.type) {
        "draft" -> Res.string.skill_review_from_draft
        "disk" -> Res.string.skill_review_from_disk
        else -> Res.string.skill_review_from_kelo
    })
    return if (p.reason.isNotBlank() && p.origin.type != "disk") "$from · ${p.reason}" else from
}

@Composable
private fun Note(text: String) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.infoSoft).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(KeloIcons.Info, contentDescription = null, tint = c.infoInk, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.infoInk, modifier = Modifier.weight(1f))
    }
}

/** One line of a change: "+" on a soft accent band, "−" on a soft red band, a gap as "⋯", a file name in bold. */
@Composable
private fun DiffLine(l: SkillDiffLine) {
    val c = Kelo.colors
    when (l.op) {
        "gap" -> Text("⋯", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(vertical = 2.dp))
        "file" -> Text(l.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c.ink,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        else -> {
            val add = l.op == "add"
            val del = l.op == "del"
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    .background(if (add) c.successSoft else if (del) c.dangerSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(if (add) "+" else if (del) "−" else " ", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = if (add) c.successInk else if (del) c.dangerInk else c.muted, modifier = Modifier.width(14.dp).alignByBaseline())
                Text(l.text, style = MaterialTheme.typography.bodySmall,
                    color = if (add) c.successInk else if (del) c.dangerInk else c.muted, modifier = Modifier.weight(1f).alignByBaseline())
            }
        }
    }
}

/**
 * "Save as skill" under a finished chat task or background job: Kelo drafts a skill from the run (when to use it, the steps, pitfalls,
 * how to check it worked), and the draft waits for approval on the Records page.
 */
/** The names of the tab and page where drafts wait (마이 → 기록), for the message after "Save as skill". */
@Composable
fun skillReviewPlace(): List<String> = listOf(string(Res.string.tab_my), string(Res.string.my_records_title))

@Composable
fun SaveAsSkillButton(drafting: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(
            string(if (drafting) Res.string.skill_review_drafting else Res.string.skill_save),
            onClick = onClick, enabled = !drafting, modifier = Modifier.fillMaxWidth(),
        )
        Text(string(Res.string.skill_save_about, string(Res.string.tab_my), string(Res.string.my_records_title)), style = MaterialTheme.typography.bodySmall, color = c.muted,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}
