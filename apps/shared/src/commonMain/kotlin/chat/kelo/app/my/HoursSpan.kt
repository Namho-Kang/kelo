package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import androidx.compose.runtime.Composable
import chat.kelo.app.agent.clockText
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string

/** "오전 10:00 – 오후 10:00", or "오전 10:00 – 다음 날 오전 1:00" for a window past midnight. */
@Composable
fun hoursSpanText(start: String, end: String): String {
    val a = ContactHoursRules.minutes(start)?.let { clockText(it / 60, it % 60) } ?: start
    val b = ContactHoursRules.minutes(end)?.let { clockText(it / 60, it % 60) } ?: end
    return string(if (ContactHoursRules.pastMidnight(start, end)) Res.string.hours_span_next_day else Res.string.hours_span, a, b)
}
