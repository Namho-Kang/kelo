package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.chat.MarkdownText
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo

/** The look-back switch with its one-line explanation. */
@Composable
fun LookbackSwitchRow(on: Boolean, onToggle: (Boolean) -> Unit, onOpen: (() -> Unit)?) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val desc = string(Res.string.lookback_title)
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(KeloIcons.Book, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(desc, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink)
            Text(string(Res.string.lookback_when), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Switch(
            checked = on, onCheckedChange = { haptics.tick(); onToggle(it) },
            modifier = Modifier.semantics { contentDescription = desc },
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
}

/**
 * The last run in one plain sentence in the app's language ("어젯밤 정리했어요 · 새 규칙 2개 제안"), made only from the server's
 * structured fields; the server's own log text is never shown.
 */
@Composable
fun LastRunLine(run: LookbackRun?, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val line = run?.let(LookbackRules::runLine) ?: return
    val at = run.at
    if (line.withWhen && at == null) return
    val whenText = at?.let {
        when (LookbackRules.runWhen(it, java.time.ZonedDateTime.now())) {
            RunWhen.LAST_NIGHT -> string(Res.string.lookback_at_last_night)
            RunWhen.TODAY -> string(Res.string.lookback_at_today)
            RunWhen.DAY -> string(Res.string.lookback_at_day,
                dayText(java.time.Instant.ofEpochMilli((it * 1000).toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()))
        }
    }.orEmpty()
    val text = line.text(whenText)
    Text(text.replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodySmall,
        color = if (line.failed) c.danger else c.muted, modifier = modifier)
}


/**
 * What the nightly look-back learned about how to work for this person: the suggested rules first (highlighted, each with its
 * reason and two plain actions, "이 규칙 지키기" / "버리기"; after a choice the card says "지킬게요" / "버렸어요" with an undo for a few
 * seconds), then the rules it keeps, then the ones the user set aside (folded). Tapping a kept or discarded rule shows why.
 */
@Composable
fun LearnedRulesBlock(rules: List<LearnedRule>, onStatus: (Long, String) -> Unit) {
    val c = Kelo.colors
    var decided by remember { mutableStateOf(mapOf<Long, String>()) }
    LaunchedEffect(decided) {
        if (decided.isNotEmpty()) {
            kotlinx.coroutines.delay(LookbackRules.UNDO_MILLIS)
            decided = emptyMap()
        }
    }
    val groups = remember(rules, decided) { LookbackRules.group(rules, decided.keys) }
    var open by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var showRejected by rememberSaveable { mutableStateOf(false) }
    fun toggle(id: Long) { open = if (id in open) open - id else open + id }
    val haptics = LocalHaptics.current

    if (groups.empty) {
        GroupCard { MutedText(string(Res.string.lookback_rules_empty), small = true, modifier = Modifier.padding(14.dp)) }
        return
    }
    if (groups.proposed.isNotEmpty()) {
        RulesHeading(plural(Res.plurals.lookback_rules_proposed, groups.proposed.size, groups.proposed.size), c.accent)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.proposed.forEach { r ->
                val shape = RoundedCornerShape(12.dp)
                val choice = decided[r.id]
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(c.accent.copy(alpha = 0.08f)).border(1.dp, c.accent.copy(alpha = 0.45f), shape)
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(r.rule, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink)
                    Basis(r)
                    if (choice == null) {
                        RuleMeta(r)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SecondaryButton(string(Res.string.lookback_rule_discard), modifier = Modifier.weight(1f), onClick = {
                                haptics.reject(); decided = decided + (r.id to LearnedRule.REJECTED); onStatus(r.id, LearnedRule.REJECTED)
                            })
                            SmallPrimary(string(Res.string.lookback_rule_keep), Modifier.weight(1f)) {
                                haptics.confirm(); decided = decided + (r.id to LearnedRule.ACCEPTED); onStatus(r.id, LearnedRule.ACCEPTED)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (choice == LearnedRule.ACCEPTED) KeloIcons.Check else KeloIcons.Minus, contentDescription = null,
                                tint = if (choice == LearnedRule.ACCEPTED) c.accent else c.muted, modifier = Modifier.size(18.dp))
                            Text(string(if (choice == LearnedRule.ACCEPTED) Res.string.lookback_rule_kept else Res.string.lookback_rule_discarded),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, modifier = Modifier.weight(1f))
                            LinkButton(string(Res.string.undo), onClick = {
                                haptics.tick(); decided = decided - r.id; onStatus(r.id, LearnedRule.PROPOSED)
                            })
                        }
                    }
                }
            }
        }
    }
    if (groups.accepted.isNotEmpty()) {
        RulesHeading(string(Res.string.lookback_rules_accepted), c.muted)
        GroupCard {
            groups.accepted.forEachIndexed { i, r ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 14.dp))
                RuleRow(r, open = r.id in open, onToggle = { toggle(r.id) }) {
                    LinkButton(string(Res.string.lookback_rule_stop), onClick = { haptics.tick(); onStatus(r.id, LearnedRule.REJECTED) })
                }
            }
        }
    }
    if (groups.rejected.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { showRejected = !showRejected }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(plural(Res.plurals.lookback_rules_rejected, groups.rejected.size, groups.rejected.size),
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
            Icon(if (showRejected) KeloIcons.Minus else KeloIcons.Plus, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
        if (showRejected) GroupCard {
            groups.rejected.forEachIndexed { i, r ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 14.dp))
                RuleRow(r, open = r.id in open, onToggle = { toggle(r.id) }, muted = true) {
                    LinkButton(string(Res.string.lookback_rule_restore), onClick = { haptics.tick(); onStatus(r.id, LearnedRule.ACCEPTED) })
                }
            }
        }
    }
}

@Composable
private fun RulesHeading(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
}

/** A kept or discarded rule: "11월 6일"; a suggestion replies already follow: "이미 답에 반영 중". */
@Composable
private fun RuleMeta(r: LearnedRule) {
    val c = Kelo.colors
    val text = if (r.status == LearnedRule.PROPOSED) {
        if (LookbackRules.inUse(r)) string(Res.string.lookback_rule_in_use) else null
    } else DayGroups.parseDay(r.date)?.let { dayText(it) }
    if (text != null) Text(text, style = MaterialTheme.typography.labelSmall, color = c.muted)
}

/** One line of why it was suggested: "근거: 11월 8일 (일) · 캘린더 일정 등록 허락을 3번 거절했어요". */
@Composable
private fun Basis(r: LearnedRule) {
    val c = Kelo.colors
    val body = listOfNotNull(DayGroups.parseDay(r.date)?.let { dayText(it) }, r.evidence.trim().ifBlank { null }).joinToString(" · ")
    if (body.isNotEmpty()) Text(string(Res.string.lookback_rule_basis, body), style = MaterialTheme.typography.bodySmall, color = c.muted)
}

/** Why the look-back thinks so (its own words), or a plain line when it gave none. */
@Composable
private fun Evidence(r: LearnedRule) {
    val c = Kelo.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bubble.copy(alpha = 0.6f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(string(Res.string.lookback_rule_why), style = MaterialTheme.typography.labelSmall, color = c.muted)
        Text(r.evidence.ifBlank { string(Res.string.lookback_rule_no_evidence) }, style = MaterialTheme.typography.bodySmall, color = c.ink)
    }
}

@Composable
private fun RuleRow(r: LearnedRule, open: Boolean, onToggle: () -> Unit, muted: Boolean = false, action: @Composable () -> Unit) {
    val c = Kelo.colors
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = string(Res.string.lookback_rule_why), onClick = onToggle)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (open) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(r.rule, style = MaterialTheme.typography.bodyMedium, color = if (muted) c.muted else c.ink)
        RuleMeta(r)
        if (open) {
            Evidence(r)
            action()
        }
    }
}

/**
 * The last memory pass in one line under the look-back ("기억 정리: 3개 추가, 2개 정리") with a link to what changed; after an
 * undo, "기억 정리를 되돌렸어요". Nothing when no pass has changed anything yet.
 */
@Composable
fun MemoryPassLine(pass: MemoryPass?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    if (pass == null || !MemoryPassRules.shown(pass)) return
    val text = if (pass.undone) string(Res.string.memory_pass_undone) else {
        val added = if (pass.added > 0) plural(Res.plurals.memory_pass_added, pass.added, pass.added) else null
        val tidied = if (pass.tidied > 0) plural(Res.plurals.memory_pass_tidied, pass.tidied, pass.tidied) else null
        string(Res.string.memory_pass_line,
            if (added != null && tidied != null) string(Res.string.memory_pass_both, added, tidied) else added ?: tidied.orEmpty())
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
        if (!pass.undone) LinkButton(string(Res.string.memory_pass_view), onClick = onOpen)
    }
}

@Composable
fun memoryAreaName(area: MemoryArea): String = string(
    when (area) {
        MemoryArea.ABOUT_YOU -> Res.string.memory_area_about_you
        MemoryArea.TO_REMEMBER -> Res.string.memory_area_to_remember
        MemoryArea.PEOPLE -> Res.string.memory_area_people
        MemoryArea.WORKING_NOTES -> Res.string.memory_area_working_notes
    },
)

/** What the last memory pass changed, by area: new or rewritten lines ("+") and lines it took out or folded ("−"), and its undo. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MemoryChangesSheet(state: Loadable<MemoryChanges>, undoing: Boolean, onUndo: () -> Unit, onClose: () -> Unit) {
    val c = Kelo.colors
    var ask by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose, sheetState = chat.kelo.app.ui.components.rememberKeloSheetState(), containerColor = c.bg,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(string(Res.string.memory_pass_changes_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            val d = state.data
            when {
                d == null -> if (state.failed) ErrorText(string(Res.string.connect_failed), small = true) else SkeletonLines(3)
                else -> {
                    val groups = MemoryPassRules.group(d.changes)
                    if (groups.isEmpty()) MutedText(string(Res.string.memory_pass_no_lines), small = true)
                    groups.forEach { g ->
                        Text(memoryAreaName(g.area), style = MaterialTheme.typography.labelLarge, color = c.muted,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                        GroupCard {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                g.added.forEach { ChangedLine(it, added = true) }
                                g.removed.forEach { ChangedLine(it, added = false) }
                            }
                        }
                    }
                    if (d.lastPass?.canUndo == true) {
                        SecondaryButton(string(Res.string.memory_pass_undo), onClick = { ask = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), enabled = !undoing)
                    }
                }
            }
        }
    }
    if (ask) {
        chat.kelo.app.ui.components.ConfirmDialog(string(Res.string.memory_pass_undo_confirm), string(Res.string.memory_pass_undo),
            onYes = onUndo, onDismiss = { ask = false }, danger = false)
    }
}

/** "+ line" for a new or rewritten line, "− line" (struck through) for one taken out. */
@Composable
private fun ChangedLine(text: String, added: Boolean) {
    val c = Kelo.colors
    val desc = string(if (added) Res.string.memory_pass_added_line else Res.string.memory_pass_removed_line, text)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (added) "+" else "−", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (added) c.accent else c.muted, modifier = Modifier.size(width = 12.dp, height = 20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (added) c.ink else c.muted, modifier = Modifier.weight(1f),
            textDecoration = if (added) null else androidx.compose.ui.text.style.TextDecoration.LineThrough)
    }
}

