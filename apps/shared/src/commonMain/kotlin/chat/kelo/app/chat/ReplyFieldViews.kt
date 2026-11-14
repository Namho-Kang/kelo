package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import chat.kelo.app.ui.platform.bestDatePattern
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import chat.kelo.app.agent.PlanRules
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.agent.uses24Hour
import chat.kelo.app.my.appLocale
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.theme.Kelo
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A quick-reply question's typed field ([ReplyFields]): the phone's own picker for its kind, whatever the question is about.
 * [value]: the plain form once complete ("" while half filled); [onValue] gets the new one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplyField(q: ReplyQuestion, value: String, onValue: (String) -> Unit) {
    val c = Kelo.colors
    val show = rememberFieldWords()
    when (ReplyFields.kind(q)) {
        ReplyFields.TEXT -> KeloTextField(
            value = value, onValueChange = onValue, placeholder = QuickReplyRules.placeholder(q) ?: string(Res.string.reply_type_hint),
            singleLine = false, maxLength = 300, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        ReplyFields.NUMBER -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KeloTextField(
                value = value, onValueChange = { onValue(it.filter { ch -> ch.isDigit() || ch in ".,-" }) },
                placeholder = q.unit.trim().ifEmpty { string(Res.string.reply_number_hint) },
                modifier = Modifier.widthIn(max = 240.dp), maxLength = 20, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            val bad = value.isNotBlank() && ReplyFields.check(q, value) == null
            ReplyFields.range(q)?.let { (lo, hi) ->
                Text(string(Res.string.reply_number_range, lo, hi), style = MaterialTheme.typography.bodySmall, color = if (bad) c.danger else c.muted)
            }
        }
        ReplyFields.DATE -> DateButton(ReplyFields.date(value)) { onValue(it.toString()) }
        ReplyFields.TIME -> TimeButton(ReplyFields.time(value)) { onValue(ReplyFields.timePlain(it)) }
        ReplyFields.DATETIME -> {
            val parts = value.split(' ', limit = 2)
            var date by remember(q) { mutableStateOf(ReplyFields.date(parts[0])) }
            var time by remember(q) { mutableStateOf(parts.getOrNull(1)?.let { ReplyFields.time(it) }) }
            fun emit() { onValue(if (date != null && time != null) "${date} ${ReplyFields.timePlain(time!!)}" else "") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DateButton(date) { date = it; emit() }
                TimeButton(time) { time = it; emit() }
            }
        }
        ReplyFields.WEEKDAYS, ReplyFields.WEEKDAYS_TIME -> {
            val withTime = ReplyFields.kind(q) == ReplyFields.WEEKDAYS_TIME
            val at = value.lastIndexOf(' ')
            var days by remember(q) { mutableStateOf(ReplyFields.days(if (withTime && at > 0) value.substring(0, at) else value).orEmpty()) }
            var time by remember(q) { mutableStateOf(if (withTime && at > 0) ReplyFields.time(value.substring(at + 1)) else null) }
            fun emit() {
                onValue(when {
                    days.isEmpty() -> ""
                    !withTime -> ReplyFields.daysPlain(days)
                    time == null -> ""
                    else -> "${ReplyFields.daysPlain(days)} ${ReplyFields.timePlain(time!!)}"
                })
            }
            val locale = appLocale()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayToggles(ReplyFields.weekOrder(show.first), days, locale) { d -> days = ReplyFields.toggle(days, d); emit() }
                if (withTime) TimeButton(time) { time = it; emit() }
            }
        }
    }
}

/** The seven days as small round toggles in one line (the schedule page's look), the locale's first day first; several may be on. */
@Composable
private fun DayToggles(order: List<DayOfWeek>, days: Set<DayOfWeek>, locale: Locale, onToggle: (DayOfWeek) -> Unit) {
    val c = Kelo.colors
    // circles of up to 40dp that shrink to fit seven in a narrow column (320dp phones, large text)
    BoxWithConstraints(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
    val side = minOf(40.dp, (maxWidth - 4.dp * 6) / 7)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        order.forEach { d ->
            val on = d in days
            Box(
                Modifier.size(side).clip(CircleShape).background(if (on) c.accent else c.card).border(1.dp, if (on) c.accent else c.line, CircleShape)
                    .toggleable(value = on, role = Role.Checkbox) { onToggle(d) }
                    .semantics { contentDescription = d.getDisplayName(TextStyle.FULL, locale) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    PlanRules.circleLabel(d.getDisplayName(TextStyle.SHORT, locale), d.getDisplayName(TextStyle.NARROW, locale)),
                    style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) c.onAccent else c.ink, maxLines = 1, softWrap = false,
                )
            }
        }
    }
    }
}

/** A button that shows the picked date (or "Pick a date") and opens the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(date: LocalDate?, onPick: (LocalDate) -> Unit) {
    val c = Kelo.colors
    val show = rememberFieldWords()
    var open by remember { mutableStateOf(false) }
    ChoiceChip(date?.let(show.date) ?: string(Res.string.reply_pick_date), selected = date != null, onClick = { open = true })
    if (open) {
        val start = date ?: LocalDate.now()
        val d = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    d.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(string(Res.string.ok), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(string(Res.string.cancel), color = c.ink) } },
        ) { DatePicker(d) }
    }
}

/** A button that shows the picked time (or "Pick a time") and opens the time picker (12/24-hour as the app language writes it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeButton(time: LocalTime?, onPick: (LocalTime) -> Unit) {
    val c = Kelo.colors
    val show = rememberFieldWords()
    var open by remember { mutableStateOf(false) }
    ChoiceChip(time?.let(show.time) ?: string(Res.string.reply_pick_time), selected = time != null, onClick = { open = true })
    if (open) {
        val start = time ?: LocalTime.of(12, 0)
        val t = rememberTimePickerState(start.hour, start.minute, is24Hour = uses24Hour())
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = c.card,
            text = { TimePicker(t) },
            confirmButton = { TextButton(onClick = { onPick(LocalTime.of(t.hour, t.minute)); open = false }) { Text(string(Res.string.ok), color = c.accent) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    }
}

/** How field answers are written in the app language: [ReplyFields.display] with the app's own phrases, a date with its weekday
 *  ("2026년 11월 10일 (화)"), the time as the language writes it, and the week starting on the locale's first day. */
class FieldWords(
    val locale: Locale,
    val words: ReplyFields.Words,
    val first: DayOfWeek,
    val date: (LocalDate) -> String,
    val time: (LocalTime) -> String,
) : (ReplyQuestion, String) -> String {
    override fun invoke(q: ReplyQuestion, plain: String): String = ReplyFields.display(q, plain, locale, words, first, time, date)
}

@Composable
fun rememberFieldWords(): FieldWords {
    val locale = appLocale()
    val words = ReplyFields.Words(
        daily = string(Res.string.agent_repeat_daily),
        weekly = string(Res.string.sched_value_weekly),
        dailyAt = string(Res.string.agent_repeat_daily_at),
        weeklyAt = string(Res.string.agent_repeat_weekly_at),
        dateAt = string(Res.string.reply_date_at),
        separator = string(Res.string.agent_list_separator),
    )
    return remember(locale, words) {
        val dateFmt = DateTimeFormatter.ofPattern(bestDatePattern("yMMMdEEE", locale), locale)
        val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        FieldWords(locale, words, firstWeekday(locale), { dateFmt.format(it) }, { timeFmt.format(it) })
    }
}

/** The first day of the week where the user is: the app language's, with the phone's region when the language carries none
 *  (the same rule as the schedule page's weekday circles). */
private fun firstWeekday(l: Locale): DayOfWeek {
    val withRegion = if (l.country.isNotEmpty()) l else runCatching {
        Locale.Builder().setLanguage(l.language).setRegion(Locale.getDefault(Locale.Category.FORMAT).country).build()
    }.getOrDefault(l)
    return WeekFields.of(withRegion).firstDayOfWeek
}
