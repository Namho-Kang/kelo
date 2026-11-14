package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.chat.MarkdownText
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.Badge
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.LocalDate

@Composable
fun GroupCard(content: @Composable () -> Unit) {
    val c = Kelo.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp))) { content() }
}

/** The status icon: a spinner while it works, an hourglass while it waits for an answer, red when it failed. */
@Composable
fun TaskStatusIcon(status: String) {
    val c = Kelo.colors
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (status) {
            AgentTask.STATUS_RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            AgentTask.STATUS_WAITING -> Icon(KeloIcons.Hourglass, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            AgentTask.STATUS_FAILED -> Icon(KeloIcons.Alert, contentDescription = null, tint = c.danger, modifier = Modifier.size(22.dp))
            AgentTask.STATUS_STOPPED, AgentTask.STATUS_INTERRUPTED -> Icon(KeloIcons.Close, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
            AgentTask.STATUS_WAITING_MODEL -> Icon(KeloIcons.Hourglass, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
            else -> Icon(KeloIcons.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun statusWord(status: String): String = string(
    when (status) {
        AgentTask.STATUS_RUNNING -> Res.string.agent_status_running
        AgentTask.STATUS_WAITING -> Res.string.agent_status_waiting
        AgentTask.STATUS_FAILED -> Res.string.agent_status_failed
        AgentTask.STATUS_STOPPED -> Res.string.agent_status_stopped
        AgentTask.STATUS_INTERRUPTED -> Res.string.work_job_interrupted
        AgentTask.STATUS_WAITING_MODEL -> Res.string.agent_status_waiting_model
        else -> Res.string.agent_status_done
    },
)

/** Where a task came from, when it was not the chat. */
@Composable
fun sourceWord(source: String): String? = when {
    source.startsWith("schedule") -> string(Res.string.agent_source_schedule)
    source == "background" -> string(Res.string.agent_source_background)
    source == "worker" -> string(Res.string.work_source_job)
    source == "handoff" -> string(Res.string.work_source_handoff)
    else -> null
}

/** A task in full: the answer (Markdown), what it asked for, Stop and its live browser while it runs. Seen once opened. */
@Composable
fun TaskPage(vm: AgentViewModel, id: Long, onBack: () -> Unit, onOpenTab: ((String) -> Unit)? = null) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    var detail by remember { mutableStateOf<TaskDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(id, tick) {
        while (true) {
            try {
                detail = vm.task(id)
                failed = false
            } catch (e: ApiError) {
                failed = true
            }
            if (detail?.running != true) break
            delay(3_000)
        }
    }
    LaunchedEffect(id) { vm.markSeen(id) }
    AgentSubPage(detail?.title?.ifBlank { null } ?: string(Res.string.work_task_title), onBack) {
        val d = detail
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                d == null && failed -> ErrorText(string(Res.string.connect_failed))
                d == null -> SkeletonRows(4)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskStatusIcon(d.status)
                        Text(if (d.running) d.liveStatus ?: statusWord(d.status) else statusWord(d.status), color = if (d.status == AgentTask.STATUS_FAILED) c.danger else c.ink,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (d.startedAt > 0) Text(dayClockText(d.startedAt), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                    sourceWord(d.source)?.let { MutedText(it, small = true) }
                    if (d.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(string(Res.string.agent_task_stop), onClick = { confirmStop = true }, modifier = Modifier.weight(1f))
                            val tabs by vm.browser.collectAsStateWithLifecycle()
                            val workers by vm.workers.collectAsStateWithLifecycle()
                            val tabId = chat.kelo.app.work.Timeline.tabFor(
                                AgentTask(d.id, status = d.status), tabs.data?.items.orEmpty(), workers.data?.items.orEmpty(),
                            )
                            if (tabId != null && onOpenTab != null) {
                                SecondaryButton(string(Res.string.work_live_view), onClick = { onOpenTab(tabId) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (d.answer.isNotBlank()) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(16.dp)) {
                            MarkdownText(d.answer)
                        }
                    } else if (!d.running && d.result.isNotBlank()) {
                        MutedText(d.result)
                    }
                    if (d.error.isNotBlank()) ErrorText(d.error, small = true)
                    if (SkillRules.canSave(d.status)) {
                        val skills by vm.skillProposals.collectAsStateWithLifecycle()
                        LaunchedEffect(id) { vm.loadSkillProposals() }
                        val place = skillReviewPlace()
                        SaveAsSkillButton(SkillRules.drafting(skills.data, taskId = d.id), onClick = { haptics.tick(); vm.saveAsSkill(taskId = d.id, where = place) })
                    }
                    if (d.approvals.isNotEmpty()) {
                        Text(string(Res.string.agent_task_approvals), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(top = 6.dp))
                        GroupCard {
                            d.approvals.forEachIndexed { i, a ->
                                if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 16.dp))
                                ApprovalRecordRow(a)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmStop) {
        ConfirmDialog(string(Res.string.agent_task_stop_confirm), string(Res.string.agent_task_stop), onDismiss = { confirmStop = false }, onYes = {
            haptics.reject()
            vm.stopTask(id) { tick++ }
        })
    }
}

/** One approval in a history: what, where, and how it was answered. */
@Composable
fun ApprovalRecordRow(a: ApprovalRecord) {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(a.label.ifBlank { a.tool }, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(a.site, a.detail).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.askedAt > 0) Text(dayClockText(a.askedAt), style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
        DecisionBadge(a.decision)
    }
}

@Composable
fun DecisionBadge(decision: String) {
    val word = string(
        when (decision) {
            "allowed_once" -> Res.string.agent_decision_once
            "allowed_always" -> Res.string.agent_decision_always
            "allowed_by_rule" -> Res.string.agent_decision_rule
            "denied" -> Res.string.agent_decision_denied
            "expired" -> Res.string.agent_decision_expired
            "cancelled" -> Res.string.agent_decision_cancelled
            else -> Res.string.agent_decision_pending
        },
    )
    Badge(word, denied = decision == "denied")
}

