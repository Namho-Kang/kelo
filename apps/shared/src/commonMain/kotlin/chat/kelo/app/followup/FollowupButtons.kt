package chat.kelo.app.followup

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo

/** A button's words, from the app's own strings (the server's ids never show). */
@Composable
fun actionLabel(step: FollowupStep): String = when (step) {
    is FollowupStep.OpenLogin -> string(Res.string.followup_action_login)
    is FollowupStep.Answer -> string(if (step.approve) Res.string.followup_action_approve else Res.string.followup_action_deny)
    is FollowupStep.ResumeWorker -> string(Res.string.followup_action_resume)
    is FollowupStep.Drop -> string(Res.string.followup_action_drop)
    is FollowupStep.Snooze -> string(Res.string.followup_action_snooze, laterText(step.minutes))
    is FollowupStep.Retry -> string(Res.string.followup_action_retry)
}

/** "30분 뒤" / "2시간 뒤" / "1일 뒤" */
@Composable
fun laterText(minutes: Int): String = when {
    minutes >= 1440 && minutes % 1440 == 0 -> plural(Res.plurals.agent_rel_days_future, minutes / 1440, minutes / 1440)
    minutes >= 60 && minutes % 60 == 0 -> plural(Res.plurals.agent_rel_hours_future, minutes / 60, minutes / 60)
    else -> plural(Res.plurals.agent_rel_minutes_future, minutes, minutes)
}

/** The main step of a set (filled); the others are outlined. */
private fun primary(step: FollowupStep): Boolean =
    step is FollowupStep.OpenLogin || step is FollowupStep.Retry || (step is FollowupStep.Answer && step.approve) || step is FollowupStep.ResumeWorker

/**
 * The buttons under an agent's message or on a card: they wrap onto more lines rather than squeeze (long words in any language
 * stay whole). [busy] shows a small spinner in place of the buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowupButtons(buttons: List<Pair<FollowupAction, FollowupStep>>, busy: Boolean, onPress: (FollowupStep) -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    if (buttons.isEmpty()) return
    if (busy) {
        Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.muted, strokeWidth = 2.dp)
        }
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // one filled button at most: the first main step; the rest are outlined
        val main = buttons.indexOfFirst { primary(it.second) }
        buttons.forEachIndexed { i, (_, step) ->
            val label = actionLabel(step)
            val pad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            if (i == main) {
                Button(
                    onClick = { onPress(step) }, shape = RoundedCornerShape(50), contentPadding = pad,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
            } else {
                OutlinedButton(
                    onClick = { onPress(step) }, shape = RoundedCornerShape(50), contentPadding = pad, border = BorderStroke(1.dp, c.line),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = c.card, contentColor = c.ink),
                ) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** The line that replaces a message's buttons once one was used. */
@Composable
fun outcomeText(o: chat.kelo.app.chat.ActionOutcome): String = string(
    when (o) {
        chat.kelo.app.chat.ActionOutcome.APPROVED -> Res.string.followup_done_approved
        chat.kelo.app.chat.ActionOutcome.DENIED -> Res.string.followup_done_denied
        chat.kelo.app.chat.ActionOutcome.RESUMED -> Res.string.followup_done_resumed
        chat.kelo.app.chat.ActionOutcome.DROPPED -> Res.string.followup_done_dropped
        chat.kelo.app.chat.ActionOutcome.SNOOZED -> Res.string.followup_done_snoozed
        chat.kelo.app.chat.ActionOutcome.SIGNED_IN -> Res.string.followup_done_signed_in
        chat.kelo.app.chat.ActionOutcome.GONE -> Res.string.followup_done_gone
        chat.kelo.app.chat.ActionOutcome.RESOLVED -> Res.string.followup_done_resolved
    },
)
