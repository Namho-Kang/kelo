package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo

/**
 * A question from work the chat is not following (a background job, a scheduled run), live at the bottom of the chat: a line
 * saying what asked, then the same card and answers as on the 작업 page. Once answered here it folds into one line.
 * [onAnswer]: (allow, remember the site, remember for the scheduled job).
 */
@Composable
fun ChatAskCard(ask: ChatAsk, onSolve: () -> Unit = {}, onAnswer: (Boolean, Boolean, Boolean) -> Unit) {
    val a = ask.approval
    Box(Modifier.fillMaxWidth().animateContentSize(FoldSpec)) {
        val end = ask.end
        if (end != null) {
            CardBox {
                val what = a.event.label.ifBlank { a.event.name }
                ResultLine(
                    when (end) { AskEnd.DENIED, AskEnd.SKIPPED -> KeloIcons.Close; AskEnd.GONE -> KeloIcons.Clock; else -> KeloIcons.Check },
                    when (end) {
                        AskEnd.SOLVED -> string(if (a.isVerification) Res.string.handover_verify_done else Res.string.handover_solved)
                        AskEnd.SKIPPED -> string(Res.string.handover_skipped, a.site.ifBlank { string(Res.string.handover_this_site) })
                        AskEnd.PROGRESSED -> string(Res.string.handover_progressed)
                        else -> string(Res.string.chat_ask_resolved, what, askEndText(end))
                    },
                    muted = end == AskEnd.GONE || end == AskEnd.DENIED || end == AskEnd.SKIPPED,
                )
            }
        } else {
            CardBox {
                OriginLine(a)
                if (a.isHumanCheck) {
                    chat.kelo.app.agent.HumanCheckBody(a, ask.working, onSolve = onSolve, onDone = { onAnswer(true, false, false) }, onSkip = { onAnswer(false, false, false) })
                } else {
                    chat.kelo.app.agent.PendingApprovalBody(a, ask.working, ask.cardError, onAnswer)
                }
            }
        }
    }
}

/** "따로 맡긴 일 · <the job's title>" over the card; a polite live region, so a screen reader says a new question came. */
@Composable
private fun OriginLine(a: chat.kelo.app.agent.PendingApproval) {
    val c = Kelo.colors
    val origin = ChatAsks.origin(a)
    val head = string(
        when (origin) {
            AskOrigin.JOB -> Res.string.chat_ask_from_job
            AskOrigin.SCHEDULE -> Res.string.chat_ask_from_schedule
            AskOrigin.AUTO -> Res.string.work_ask_unattended
            AskOrigin.CHAT -> Res.string.chat_ask_from_reply
        },
    )
    val title = a.title.trim().takeIf { it.isNotEmpty() && origin != AskOrigin.CHAT }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (origin == AskOrigin.SCHEDULE) KeloIcons.Calendar else KeloIcons.Work, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
        Text(
            if (title != null) string(Res.string.chat_ask_titled, head, title) else head,
            style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun askEndText(e: AskEnd): String = string(
    when (e) {
        AskEnd.ALLOWED -> Res.string.agent_approval_allowed
        AskEnd.ALWAYS -> Res.string.agent_approval_always
        AskEnd.KEPT_FOR_SCHEDULE -> Res.string.approval_kept_for_schedule
        AskEnd.DENIED -> Res.string.agent_approval_denied
        AskEnd.GONE -> Res.string.agent_approval_gone
        AskEnd.SOLVED -> Res.string.agent_approval_allowed
        AskEnd.SKIPPED -> Res.string.agent_approval_denied
        AskEnd.PROGRESSED -> Res.string.agent_approval_allowed
    },
)
