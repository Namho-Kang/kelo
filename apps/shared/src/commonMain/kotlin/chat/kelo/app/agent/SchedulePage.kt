package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import chat.kelo.app.my.appLocale
import chat.kelo.app.ui.components.rememberKeloSheetState
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.Tag
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun lastRunWord(status: String): String = runWord(status)

/** The repeat choices, in their order (as a phone calendar offers them). */
private val FREQS = listOf(
    Freq.NONE to Res.string.sched_freq_none, Freq.DAILY to Res.string.agent_repeat_daily, Freq.WEEKDAYS to Res.string.agent_repeat_weekdays,
    Freq.WEEKENDS to Res.string.sched_freq_weekends, Freq.WEEKLY to Res.string.sched_freq_weekly, Freq.MONTHLY to Res.string.agent_repeat_monthly,
    Freq.YEARLY to Res.string.sched_freq_yearly,
)

/**
 * A schedule's page (or a new one), like a phone calendar's event: its name, what Kelo should do, a date and a time, how it repeats
 * (never, every day, weekdays, weekends, chosen weekdays, monthly on the date's day, yearly on its date) and an optional last day.
 * Behind it the server keeps cron / once (PlanRules). A schedule the form cannot show (every few hours, every N minutes, an unusual cron
 * the agent made) is shown in words with "직접 바꾸기", which replaces it with a simple repeat. Saved schedules also have their on/off
 * switch here, "run now" and delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditPage(vm: AgentViewModel, id: Long?, onBack: () -> Unit) {
    val c = Kelo.colors
    val list by vm.schedules.collectAsStateWithLifecycle()
    val s = id?.let { i -> list.data?.items?.firstOrNull { it.id == i } }
    if (id != null && s == null) {
        // opened from a notification before the list came: load it
        androidx.compose.runtime.LaunchedEffect(id) { vm.loadSchedules() }
        AgentSubPage(string(Res.string.agent_schedule_title), onBack) {
            Column(Modifier.padding(16.dp)) { if (list.failed) ErrorText(string(Res.string.connect_failed)) else SkeletonRows(4) }
        }
        return
    }
    val today = remember { LocalDate.now() }
    val saved = remember(s?.id) { s?.let { PlanRules.fromSchedule(it, today) } }
    val first = saved ?: s?.let { PlanRules.replacing(it, today) } ?: SchedulePlan(today.plusDays(1), 9, 0, Freq.NONE)
    var name by rememberSaveable(s?.id) { mutableStateOf(s?.name.orEmpty()) }
    var prompt by rememberSaveable(s?.id) { mutableStateOf(s?.prompt.orEmpty()) }
    // a schedule the form cannot show stays as it is until the user chooses to replace it
    var simple by rememberSaveable(s?.id) { mutableStateOf(s == null || saved != null) }
    var dateEpochDay by rememberSaveable(s?.id) { mutableStateOf(first.date.toEpochDay()) }
    var hour by rememberSaveable(s?.id) { mutableStateOf(first.hour) }
    var minute by rememberSaveable(s?.id) { mutableStateOf(first.minute) }
    var freq by rememberSaveable(s?.id) { mutableStateOf(first.freq) }
    var days by rememberSaveable(s?.id) { mutableStateOf(first.days.toList()) }
    var untilEpochDay by rememberSaveable(s?.id) { mutableStateOf(first.until?.toEpochDay()) }
    var timeDialog by remember { mutableStateOf(false) }
    var repeatSheet by remember { mutableStateOf(false) }
    var endSheet by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf<String?>(null) }   // "date" | "until"
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val date = LocalDate.ofEpochDay(dateEpochDay)
    val plan = SchedulePlan(date, hour, minute, freq, days.toSet(), untilEpochDay?.let { LocalDate.ofEpochDay(it) }?.takeIf { freq != Freq.NONE })
    val now = java.time.LocalDateTime.now()
    val problem = if (simple) PlanRules.problem(plan, now) else null
    val canSave = name.isNotBlank() && prompt.isNotBlank() && problem == null && !saving

    AgentSubPage(if (s == null) string(Res.string.agent_schedule_new) else string(Res.string.agent_schedule_title), onBack) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FieldLabel(string(Res.string.agent_schedule_name))
            KeloTextField(name, { name = it }, string(Res.string.agent_schedule_name_hint), maxLength = 60)
            FieldLabel(string(Res.string.agent_schedule_prompt))
            KeloTextField(prompt, { prompt = it }, string(Res.string.agent_schedule_prompt_hint), singleLine = false, maxLength = 2000,
                modifier = Modifier.heightIn(min = 112.dp))
            if (!simple && s != null) {
                // made by the agent in a way the form has no words for: say it in a sentence, offer to replace it
                FieldLabel(string(Res.string.agent_schedule_repeat))
                Column(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(scheduleText(s), style = MaterialTheme.typography.bodyLarge, color = c.ink, fontWeight = FontWeight.Medium)
                    MutedText(string(Res.string.sched_custom_note), small = true)
                    LinkButton(string(Res.string.sched_custom_convert), onClick = { simple = true })
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(c.card)
                        .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                ) {
                    PickerRow(string(if (freq == Freq.NONE) Res.string.agent_schedule_date else Res.string.sched_starts), fullDayText(date)) { dateDialog = "date" }
                    HorizontalDivider(color = c.line)
                    PickerRow(string(Res.string.agent_schedule_time), clockText(hour, minute)) { timeDialog = true }
                    HorizontalDivider(color = c.line)
                    PickerRow(string(Res.string.sched_repeat), repeatValue(plan)) { repeatSheet = true }
                    if (freq != Freq.NONE) {
                        HorizontalDivider(color = c.line)
                        val end = untilEpochDay?.let { string(Res.string.sched_end_until, dayOrFullText(LocalDate.ofEpochDay(it), today)) }
                        PickerRow(string(Res.string.sched_end), end ?: string(Res.string.sched_end_never)) { endSheet = true }
                    }
                }
                // what it will mean, in the words the agenda uses
                Text(planText(plan, now), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(horizontal = 2.dp))
                when (problem) {
                    PlanRules.Problem.NO_DAY -> ErrorText(string(Res.string.agent_schedule_need_day), small = true)
                    PlanRules.Problem.PAST -> ErrorText(string(Res.string.sched_past), small = true)
                    PlanRules.Problem.END_BEFORE_START -> ErrorText(string(Res.string.sched_end_before), small = true)
                    null -> {}
                }
            }
            if (s?.timezone?.isNotBlank() == true && s.timezone != java.util.TimeZone.getDefault().id) {
                MutedText(string(Res.string.agent_schedule_zone, s.timezone), small = true)
            }
            if (s != null) ScheduleStateBlock(vm, s)
            if (name.isBlank() || prompt.isBlank()) MutedText(string(Res.string.sched_save_hint), small = true)
            PrimaryButton(string(Res.string.save), enabled = canSave, onClick = {
                saving = true
                vm.saveSchedule(s?.id, name, prompt, if (simple) plan else null, if (s == null) java.util.TimeZone.getDefault().id else null,
                    onFailed = { saving = false }) {
                    saving = false
                    onBack()
                }
            }, modifier = Modifier.padding(top = 8.dp))
            if (s != null) {
                SecondaryButton(string(Res.string.agent_schedule_run_now), onClick = { vm.runNow(s.id) }, modifier = Modifier.fillMaxWidth())
                LinkButton(string(Res.string.delete), onClick = { confirmDelete = true }, danger = true)
            }
        }
    }

    if (timeDialog) {
        val t = rememberTimePickerState(hour, minute, is24Hour = uses24Hour())
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            containerColor = c.card,
            text = { TimePicker(t) },
            confirmButton = { TextButton(onClick = { hour = t.hour; minute = t.minute; timeDialog = false }) { Text(string(Res.string.ok), color = c.accent) } },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    }
    if (repeatSheet) {
        RepeatSheet(plan, onPick = { f ->
            val p = PlanRules.withFreq(plan, f)
            freq = p.freq; days = p.days.toList()
        }, onDays = { days = it.toList() }, onClose = { repeatSheet = false })
    }
    if (endSheet) {
        EndSheet(untilEpochDay?.let { LocalDate.ofEpochDay(it) }, today, onNever = { untilEpochDay = null }, onPickDate = { dateDialog = "until" },
            onClose = { endSheet = false })
    }
    dateDialog?.let { which ->
        val shown = if (which == "until") untilEpochDay?.let { LocalDate.ofEpochDay(it) } ?: PlanRules.defaultUntil(date, today) else date
        val earliest = if (which == "until") maxOf(date, today) else today
        val d = rememberDatePickerState(
            initialSelectedDateMillis = shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(earliest)
                override fun isSelectableYear(year: Int): Boolean = year >= earliest.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { dateDialog = null },
            confirmButton = {
                TextButton(onClick = {
                    d.selectedDateMillis?.let { ms ->
                        val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "until") untilEpochDay = picked.toEpochDay()
                        else {
                            dateEpochDay = picked.toEpochDay()
                            // a weekly repeat follows the new date's weekday when only one day was picked
                            if (freq == Freq.WEEKLY && days.size <= 1) days = listOf(picked.dayOfWeek.value % 7)
                        }
                    }
                    dateDialog = null
                }) { Text(string(Res.string.ok), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { dateDialog = null }) { Text(string(Res.string.cancel), color = c.ink) } },
        ) { DatePicker(d) }
    }
    if (confirmDelete && s != null) {
        val keep = vm.archive.collectAsStateWithLifecycle().value.data?.keepDays ?: 30
        DeleteScheduleDialog(s.name, keep, onDismiss = { confirmDelete = false },
            onArchive = { confirmDelete = false; vm.deleteSchedule(s.id, onBack) },
            onDelete = { confirmDelete = false; vm.deleteSchedule(s.id, onBack, forever = true) })
    }
}

/** A saved schedule's on/off switch, its next and last run, and why it was switched off. */
@Composable
private fun ScheduleStateBlock(vm: AgentViewModel, s: Schedule) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val desc = string(Res.string.agent_schedule_switch, s.name)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(string(if (s.enabled) Res.string.work_schedule_on else Res.string.work_schedule_off), style = MaterialTheme.typography.bodyLarge,
                color = c.ink, modifier = Modifier.weight(1f))
            Switch(
                checked = s.enabled, onCheckedChange = { haptics.tick(); vm.setEnabled(s, it) },
                modifier = Modifier.semantics { contentDescription = desc },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
        val next = ScheduleTimes.nextRunSeconds(s)
        if (s.enabled && next != null) {
            Text(string(Res.string.agent_schedule_next, relText(next), dayClockText(next)), style = MaterialTheme.typography.bodySmall, color = c.ink)
        }
        s.lastRun?.takeIf { it.at != null }?.let { lr ->
            Text(
                string(Res.string.agent_schedule_last, lastRunWord(lr.status), relText(lr.at ?: 0.0)),
                style = MaterialTheme.typography.labelSmall, color = if (lr.status == "failed") c.danger else c.muted,
            )
        }
        s.disabledReason?.takeIf { it.isNotBlank() && !s.enabled }?.let {
            Text(string(Res.string.agent_schedule_off_reason, it), style = MaterialTheme.typography.labelSmall, color = c.danger)
        }
        if (s.paused) {
            Text(string(Res.string.work_schedule_paused_reason, s.pausedReason?.ifBlank { null } ?: string(Res.string.work_schedule_paused_unknown)),
                style = MaterialTheme.typography.bodySmall, color = c.ink)
            LinkButton(string(Res.string.work_schedule_resume), onClick = { vm.resumeSchedule(s.id) })
        }
    }
    RunHistory(vm, s.id)
    GrantsBlock(vm, s.id)
}

/** What this schedule's runs may do without asking (a login, a purchase up to a cap), allowed once at setup; each can be revoked. */
@Composable
private fun GrantsBlock(vm: AgentViewModel, id: Long) {
    val scope = rememberCoroutineScope()
    var grants by remember(id) { mutableStateOf<List<ScheduleGrant>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(id) { grants = runCatching { vm.scheduleGrants(id) }.getOrDefault(emptyList()) }
    if (grants.isEmpty()) return
    FieldLabel(string(Res.string.grant_section_title))
    ScheduleGrantRows(grants, showSchedule = false) { g ->
        scope.launch { if (runCatching { vm.revokeScheduleGrant(g) }.isSuccess) grants = grants - g }
    }
    MutedText(string(Res.string.grant_section_note), small = true)
}

/** The last runs of a schedule, newest first: when, how it went, and its one line. */
@Composable
private fun RunHistory(vm: AgentViewModel, id: Long) {
    val c = Kelo.colors
    var runs by remember(id) { mutableStateOf<List<ScheduleRun>?>(null) }
    androidx.compose.runtime.LaunchedEffect(id) { runs = runCatching { vm.scheduleRuns(id) }.getOrDefault(emptyList()) }
    FieldLabel(string(Res.string.work_schedule_runs))
    val list = runs
    when {
        list == null -> SkeletonRows(2)
        list.isEmpty() -> MutedText(string(Res.string.work_schedule_runs_empty), small = true)
        else -> Column(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        ) {
            list.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (r.startedAt > 0) dayClockText(r.startedAt) else "", style = MaterialTheme.typography.labelSmall, color = c.muted)
                        val line = r.result.ifBlank { r.error.orEmpty() }
                        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(runWord(r.status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                        color = if (r.status == "failed") c.danger else if (r.status == "done") c.success else c.muted)
                }
            }
        }
    }
}

@Composable
fun runWord(status: String): String = string(
    when (status) {
        "running" -> Res.string.agent_status_running
        "done" -> Res.string.agent_status_done
        "quiet" -> Res.string.work_run_quiet
        "failed" -> Res.string.agent_status_failed
        "stopped" -> Res.string.agent_status_stopped
        "skipped" -> Res.string.agent_status_missed
        "paused" -> Res.string.work_run_paused
        "missed" -> Res.string.agent_status_missed
        else -> Res.string.agent_status_done
    },
)

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Kelo.colors.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
}

/** A row of the date/time/repeat card: its name on the left, the value right-aligned with a chevron; the whole row opens a picker. */
@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodyMedium)
        Text(
            value, color = c.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp, end = 4.dp),
        )
        Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

/** The repeat row's value: "안 함", "매일", "매주 월, 수", "매달 9일", "매년 11월 9일". */
@Composable
private fun repeatValue(p: SchedulePlan): String = when (p.freq) {
    Freq.NONE -> string(Res.string.sched_freq_none)
    Freq.DAILY -> string(Res.string.agent_repeat_daily)
    Freq.WEEKDAYS -> string(Res.string.agent_repeat_weekdays)
    Freq.WEEKENDS -> string(Res.string.sched_freq_weekends)
    Freq.WEEKLY -> string(
        Res.string.sched_value_weekly,
        PlanRules.weekOrder(firstWeekday()).filter { it in p.days }.map { weekdayShort(it) }.joinToString(string(Res.string.agent_list_separator)),
    )
    Freq.MONTHLY -> string(Res.string.sched_value_monthly, p.date.dayOfMonth)
    Freq.YEARLY -> string(Res.string.sched_value_yearly, monthDayText(p.date.monthValue, p.date.dayOfMonth))
}

/** The first day of the week where the user is: the app language's, with the phone's region when the language carries none ("ko" + KR). */
@Composable
private fun firstWeekday(): DayOfWeek {
    val l = appLocale()
    val withRegion = if (l.country.isNotEmpty()) l else runCatching {
        Locale.Builder().setLanguage(l.language).setRegion(Locale.getDefault().country).build()
    }.getOrDefault(l)
    return WeekFields.of(withRegion).firstDayOfWeek
}

/** Closes a sheet with its slide-down, then tells the page. */
@OptIn(ExperimentalMaterial3Api::class)
private fun closeSheet(scope: kotlinx.coroutines.CoroutineScope, state: SheetState, onClose: () -> Unit) {
    scope.launch { state.hide() }.invokeOnCompletion { onClose() }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Kelo.colors.ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** One choice of a sheet: a radio, its words and (optionally) a second muted line. */
@Composable
private fun OptionRow(text: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.muted))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = c.ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

/**
 * How it repeats: never, every day, weekdays, weekends, every week (with the weekdays as small round toggles), every month on the start
 * date's day, every year on its date. Any choice but "every week" closes the sheet at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSheet(plan: SchedulePlan, onPick: (Freq) -> Unit, onDays: (Set<Int>) -> Unit, onClose: () -> Unit) {
    val c = Kelo.colors
    val state = rememberKeloSheetState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle(string(Res.string.sched_repeat))
            Column(Modifier.selectableGroup()) {
                FREQS.forEach { (f, label) ->
                    val text = when (f) {
                        Freq.MONTHLY -> string(Res.string.sched_opt_monthly, plan.date.dayOfMonth)
                        Freq.YEARLY -> string(Res.string.sched_opt_yearly, monthDayText(plan.date.monthValue, plan.date.dayOfMonth))
                        else -> string(label)
                    }
                    OptionRow(text, selected = plan.freq == f) {
                        haptics.tick()
                        onPick(f)
                        if (f != Freq.WEEKLY) closeSheet(scope, state, onClose)
                    }
                    if (f == Freq.WEEKLY && plan.freq == Freq.WEEKLY) WeekdayCircles(plan.days) { onDays(it) }
                }
            }
            if (plan.freq == Freq.WEEKLY) {
                PrimaryButton(string(Res.string.ok), onClick = { closeSheet(scope, state, onClose) },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            }
        }
    }
}

/** The seven weekdays as small circles in one line, the locale's first day first; a chosen day is filled. The last one stays on. */
@Composable
private fun WeekdayCircles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val locale = appLocale()
    Row(
        Modifier.padding(start = 56.dp, end = 20.dp, top = 2.dp, bottom = 10.dp).widthIn(max = 340.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlanRules.weekOrder(firstWeekday()).forEach { d ->
            val dow = CronBuilder.dayOfWeek(d)
            val on = d in days
            val full = dow.getDisplayName(TextStyle.FULL, locale)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (on) c.accent else c.card).border(1.dp, if (on) c.accent else c.line, CircleShape)
                    .toggleable(value = on, role = Role.Checkbox) { haptics.tick(); onChange(PlanRules.toggleDay(days, d)) }
                    .semantics { contentDescription = full },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    PlanRules.circleLabel(dow.getDisplayName(TextStyle.SHORT, locale), dow.getDisplayName(TextStyle.NARROW, locale)),
                    style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) c.onAccent else c.ink, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** When a repeat ends: never, or on a date (which opens the date picker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndSheet(until: LocalDate?, today: LocalDate, onNever: () -> Unit, onPickDate: () -> Unit, onClose: () -> Unit) {
    val c = Kelo.colors
    val state = rememberKeloSheetState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).selectableGroup()) {
            SheetTitle(string(Res.string.sched_end))
            OptionRow(string(Res.string.sched_end_never), selected = until == null) {
                haptics.tick(); onNever(); closeSheet(scope, state, onClose)
            }
            OptionRow(string(Res.string.sched_end_on), selected = until != null, sub = until?.let { string(Res.string.sched_end_until, dayOrFullText(it, today)) }) {
                haptics.tick(); closeSheet(scope, state) { onClose(); onPickDate() }
            }
        }
    }
}

/**
 * 삭제 on a schedule's page: three answers. 보관함에 넣기 keeps it restorable for [keepDays] days; 삭제 removes it at once for good;
 * 취소 does nothing.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeleteScheduleDialog(name: String, keepDays: Int, onDismiss: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    val c = Kelo.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(string(Res.string.schedule_delete_title, name), color = c.ink, style = MaterialTheme.typography.titleMedium) },
        text = { Text(plural(Res.plurals.schedule_delete_body, keepDays, keepDays), color = c.ink) },
        confirmButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) }
                TextButton(onClick = onArchive) { Text(string(Res.string.schedule_delete_archive), color = c.accent) }
                TextButton(onClick = onDelete) { Text(string(Res.string.delete), color = c.danger) }
            }
        },
    )
}
