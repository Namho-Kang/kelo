package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.agent.AgentViewModel
import chat.kelo.app.agent.DayGroups
import chat.kelo.app.agent.DigestRules
import chat.kelo.app.agent.EntryRow
import chat.kelo.app.agent.GroupCard
import chat.kelo.app.agent.JournalEntry
import chat.kelo.app.agent.LastRunLine
import chat.kelo.app.agent.LearnedRulesBlock
import chat.kelo.app.agent.LookbackSwitchRow
import chat.kelo.app.agent.MemoryChangesSheet
import chat.kelo.app.agent.MemoryPassLine
import chat.kelo.app.agent.SkillProposalsBlock
import chat.kelo.app.agent.dayText
import chat.kelo.app.agent.fullDayText
import chat.kelo.app.chat.MarkdownText
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 마이 → 기록: one week at a time. A strip of the seven days (how much was written each day), then each day with the journal
 * entries the agent wrote and that night's look-back note side by side; below, the nightly look-back's switch and what it learned
 * (preferences to confirm or set aside).
 */
@Composable
fun RecordsPage(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val vm = rememberViewModel("my-records") { AgentViewModel(services.sessions) }
    val today = remember { LocalDate.now() }
    var startDay by rememberSaveable { mutableStateOf(DigestRules.startOf(today).toEpochDay()) }
    val start = LocalDate.ofEpochDay(startDay)
    val week by vm.week.collectAsStateWithLifecycle()
    val look by vm.lookback.collectAsStateWithLifecycle()
    val rebuilding by vm.rebuilding.collectAsStateWithLifecycle()
    LaunchedEffect(start) { vm.loadWeek(start) }
    LaunchedEffect(Unit) { vm.loadLookback(); vm.loadSkillProposals() }
    val skills by vm.skillProposals.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var askRebuild by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf<JournalEntry?>(null) }
    var showMemory by rememberSaveable { mutableStateOf(false) }
    // learned message hours waiting for an answer (GET /api/proactive "hours_suggestion"); [hoursDone] = the choice just made
    val hoursVm = rememberViewModel("my-records-hours") { HoursSuggestionViewModel(services.sessions) }
    val hoursUi by hoursVm.state.collectAsStateWithLifecycle()
    val hours = hoursUi.hours
    val hoursDone = hoursUi.done
    fun answerHours(s: HoursSuggestion, accept: Boolean) = hoursVm.answer(s, accept) { e ->
        vm.say(if (e.isNetwork) Res.string.connect_failed else Res.string.agent_error_detail, e.detail)
    }
    val c = Kelo.colors

    SubPage(string(Res.string.my_records_title), onBack) {
        WeekBar(
            start, canNext = DigestRules.canGoNext(start, today),
            onPrev = { startDay = start.minusWeeks(1).toEpochDay() }, onNext = { startDay = start.plusWeeks(1).toEpochDay() },
        )
        val d = week.data?.takeIf { it.start == start }
        if (d == null) {
            if (week.failed) ErrorText(string(Res.string.connect_failed), small = true) else SkeletonRows(3)
        } else {
            DayStrip(start, DigestRules.perDay(d), today)
            val notes = look.data?.notes.orEmpty().associateBy { it.date.take(10) }
            val dates = (d.days.map { it.date.take(10) } + notes.keys.filter { it in start.toString()..d.end.toString() }).distinct().sortedDescending()
            Text(
                plural(Res.plurals.my_records_week_count, d.entries.size, d.entries.size),
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (dates.isEmpty()) EmptyNote(string(Res.string.my_records_week_empty))
            dates.forEach { date ->
                val day = d.days.firstOrNull { it.date.take(10) == date }
                val parsed = DayGroups.parseDay(date)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(parsed?.let { fullDayText(it) } ?: date, style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.weight(1f))
                    if (date in rebuilding) CircularProgressIndicator(Modifier.size(16.dp), color = c.muted, strokeWidth = 2.dp)
                    else if (parsed != null && parsed < today) LinkButton(string(Res.string.agent_journal_rebuild), onClick = { askRebuild = date })
                }
                GroupCard {
                    day?.entries.orEmpty().forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 16.dp))
                        EntryRow(e, open = e.id in expanded, onToggle = { expanded = if (e.id in expanded) expanded - e.id else expanded + e.id }, onDelete = { askDelete = e })
                    }
                    day?.gap?.let { g ->
                        Text(
                            string(if (g.status == "failed") Res.string.agent_journal_failed else Res.string.agent_journal_pending) +
                                (if (g.status == "failed" && g.reason.isNotBlank()) " · " + g.reason else ""),
                            style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(16.dp),
                        )
                    }
                    notes[date]?.let { n ->
                        if (day?.entries?.isNotEmpty() == true || day?.gap != null) HorizontalDivider(color = c.line)
                        Column(Modifier.fillMaxWidth().background(c.bubble.copy(alpha = 0.5f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(string(Res.string.my_records_night_note), style = MaterialTheme.typography.labelLarge, color = c.muted)
                            MarkdownText(n.text)
                        }
                    }
                }
            }
        }

        SectionLabel(string(Res.string.lookback_title))
        val data = look.data
        when {
            data == null -> if (look.failed) ErrorText(string(Res.string.connect_failed), small = true) else SkeletonRows(1)
            !data.available -> {
                MutedText(string(Res.string.lookback_unavailable), small = true)
                hours?.let { h -> HoursSuggestionCard(h, hoursDone) { accept -> answerHours(h, accept) } }
            }
            else -> {
                GroupCard {
                    LookbackSwitchRow(data.enabled, vm::setLookback, onOpen = null)
                    LastRunLine(data.lastRun, Modifier.padding(start = 50.dp, end = 14.dp,
                        bottom = if (chat.kelo.app.agent.MemoryPassRules.shown(data.memory?.lastPass)) 0.dp else 12.dp))
                    MemoryPassLine(data.memory?.lastPass, onOpen = { vm.loadMemoryChanges(); showMemory = true },
                        Modifier.padding(start = 50.dp, end = 14.dp, bottom = 6.dp))
                }
                MutedText(string(Res.string.lookback_about), small = true)
                // the review list: learned message hours first (a row of their own), then the suggested and kept rules
                hours?.let { h -> HoursSuggestionCard(h, hoursDone) { accept -> answerHours(h, accept) } }
                LearnedRulesBlock(data.rules, onStatus = vm::setRuleStatus)
            }
        }
        // skills Kelo proposed or drafted, waiting for the owner's approval (the same review area as the learned rules)
        SkillProposalsBlock(skills.data, onAnswer = vm::answerSkill)
    }
    askRebuild?.let { date ->
        val label = DayGroups.parseDay(date)?.let { fullDayText(it) } ?: date
        ConfirmDialog(string(Res.string.agent_journal_rebuild_confirm, label), string(Res.string.agent_journal_rebuild),
            onDismiss = { askRebuild = null }, danger = false, onYes = { vm.rebuild(date) })
    }
    if (showMemory) {
        val changes by vm.memoryChanges.collectAsStateWithLifecycle()
        val undoing by vm.memoryUndoing.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { if (changes.data == null && !changes.loading) vm.loadMemoryChanges() }
        MemoryChangesSheet(changes, undoing, onUndo = { vm.undoMemoryPass { showMemory = false } }, onClose = { showMemory = false })
    }
    askDelete?.let { e ->
        ConfirmDialog(string(Res.string.agent_journal_delete_confirm, e.title), string(Res.string.delete),
            onDismiss = { askDelete = null }, onYes = { vm.deleteEntry(e) })
    }
    VmMessages(vm)
}

/** ‹ 11월 2일 – 11월 8일 › */
@Composable
private fun WeekBar(start: LocalDate, canNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(KeloIcons.ChevronLeft, contentDescription = string(Res.string.my_records_prev_week), tint = c.ink) }
        Text(
            string(Res.string.my_records_week_range, dayText(start), dayText(start.plusDays(6))),
            style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(KeloIcons.ChevronRight, contentDescription = string(Res.string.my_records_next_week), tint = if (canNext) c.ink else c.disabledInk)
        }
    }
}

/** Seven small columns, Monday first: the weekday, and a bar as tall as that day's number of entries. */
@Composable
private fun DayStrip(start: LocalDate, counts: List<Int>, today: LocalDate) {
    val c = Kelo.colors
    val most = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { i, n ->
            val day = start.plusDays(i.toLong())
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.height(36.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.width(14.dp).height((6 + 30 * n / most).dp).clip(RoundedCornerShape(4.dp))
                            .background(if (n > 0) c.accent else c.line),
                    )
                }
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, appLocale()),
                    style = MaterialTheme.typography.labelSmall, color = if (day == today) c.accent else c.muted,
                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
