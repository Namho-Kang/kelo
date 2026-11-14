package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.agent.SmallPrimary
import chat.kelo.app.agent.clockText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo

/**
 * The review row for learned message hours on 마이 → 기록, beside the suggested rules: what the owner's use shows, what is set now,
 * and "지금대로 두기" / "이 시간으로 바꾸기". [done] is the choice just made (true = changed), shown for a moment before the row goes.
 */
@Composable
fun HoursSuggestionCard(s: HoursSuggestion, done: Boolean?, onAnswer: (accept: Boolean) -> Unit) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val shape = RoundedCornerShape(12.dp)
    val learned = hoursSpanText(s.wakeStart, s.wakeEnd)
    val weekend = s.dayType == ContactHoursRules.WEEKEND
    Text(string(if (weekend) Res.string.hours_suggest_heading_weekend else Res.string.hours_suggest_heading), style = MaterialTheme.typography.labelLarge, color = c.accent,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.accent.copy(alpha = 0.08f)).border(1.dp, c.accent.copy(alpha = 0.45f), shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(string(if (weekend) Res.string.hours_suggest_title_weekend else Res.string.hours_suggest_title, learned), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink)
        Text(
            plural(Res.plurals.hours_suggest_basis, s.activeDays, s.activeDays, hoursSpanText(s.currentStart, s.currentEnd)),
            style = MaterialTheme.typography.bodySmall, color = c.muted,
        )
        if (done == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(string(Res.string.hours_suggest_keep), modifier = Modifier.weight(1f), onClick = { haptics.reject(); onAnswer(false) })
                SmallPrimary(string(Res.string.hours_suggest_accept), Modifier.weight(1f)) { haptics.confirm(); onAnswer(true) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (done) KeloIcons.Check else KeloIcons.Minus, contentDescription = null, tint = if (done) c.accent else c.muted, modifier = Modifier.size(18.dp))
                Text(
                    if (done) string(Res.string.hours_suggest_accepted, learned) else string(Res.string.hours_suggest_kept),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.ink, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
