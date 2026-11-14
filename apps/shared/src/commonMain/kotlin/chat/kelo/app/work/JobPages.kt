package chat.kelo.app.work

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.agent.AgentSubPage
import chat.kelo.app.agent.AgentViewModel
import chat.kelo.app.agent.TabRow
import chat.kelo.app.agent.WorkerDetail
import chat.kelo.app.agent.clockText
import chat.kelo.app.agent.dayClockText
import chat.kelo.app.chat.MarkdownText
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay

/**
 * One background job: what it was asked, where it is (its state, step budget, the steps so far as a small timeline) and what it
 * reported; Stop while it runs, go on / send it a few words when it stopped, and its browser live.
 */
@Composable
fun JobPage(vm: AgentViewModel, id: String, onBack: () -> Unit, onOpenLive: (String) -> Unit, onOpenTask: (Long) -> Unit) {
    val c = Kelo.colors
    var detail by remember { mutableStateOf<WorkerDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    var answering by remember { mutableStateOf(false) }
    val workChanged by LocalAppServices.current.agentActivity.workChanged.collectAsStateWithLifecycle()
    LaunchedEffect(id, tick, workChanged) {
        while (true) {
            try {
                detail = vm.worker(id)
                failed = false
            } catch (e: ApiError) {
                failed = true
            }
            if (detail?.status != chat.kelo.app.agent.Worker.RUNNING) break
            delay(3_000)
        }
    }
    AgentSubPage(detail?.title?.ifBlank { null } ?: string(Res.string.work_job_title), onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val d = detail
            when {
                d == null && failed -> ErrorText(string(Res.string.connect_failed))
                d == null -> SkeletonRows(4)
                else -> {
                    val w = d.brief()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(jobWord(d.status), jobTone(d.status))
                        Text(string(Res.string.work_job_line, kindWord(d.kind)), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                        if (d.startedAt > 0) Text(dayClockText(d.startedAt), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                    if (w.needsYou || d.status == chat.kelo.app.agent.Worker.INTERRUPTED) Text(waitingText(w), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Timeline.progress(w)?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = jobTone(d.status), trackColor = c.line, drawStopIndicator = {})
                        Text(string(Res.string.work_job_steps, d.steps, d.maxSteps), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (w.active) SecondaryButton(string(Res.string.agent_task_stop), onClick = { confirmStop = true }, modifier = Modifier.weight(1f))
                        if (w.canResume) PrimaryButton(string(Res.string.work_job_resume), onClick = { vm.resumeWorker(id) { tick++ } }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(string(Res.string.work_job_answer), onClick = { answering = true }, modifier = Modifier.weight(1f))
                        Timeline.tabFor(w)?.let { tab -> SecondaryButton(string(Res.string.work_live_view), onClick = { onOpenLive(tab) }, modifier = Modifier.weight(1f)) }
                    }
                    d.report?.takeIf { it.summary.isNotBlank() || it.details.isNotBlank() }?.let { r ->
                        Label(string(Res.string.work_job_report))
                        Panel { if (r.summary.isNotBlank()) Text(r.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink)
                            if (r.details.isNotBlank()) MarkdownText(r.details) }
                    }
                    d.error?.takeIf { it.isNotBlank() }?.let { ErrorText(it, small = true) }
                    chat.kelo.app.agent.TaskGrantsBlock(id, load = { vm.jobGrants(id) }, revoke = { vm.revokeScheduleGrant(it) })
                    if (chat.kelo.app.agent.SkillRules.canSave(d.status)) {
                        val skills by vm.skillProposals.collectAsStateWithLifecycle()
                        LaunchedEffect(id) { vm.loadSkillProposals() }
                        val place = chat.kelo.app.agent.skillReviewPlace()
                        chat.kelo.app.agent.SaveAsSkillButton(chat.kelo.app.agent.SkillRules.drafting(skills.data, workerId = id),
                            onClick = { vm.saveAsSkill(workerId = id, where = place) })
                    }
                    if (d.task.isNotBlank()) {
                        Label(string(Res.string.work_job_asked))
                        Panel { Text(d.task, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            d.url?.takeIf { it.isNotBlank() }?.let { MutedText(it, small = true) } }
                    }
                    if (d.lines.isNotEmpty()) {
                        Label(string(Res.string.work_job_steps_title))
                        Panel {
                            d.lines.asReversed().forEachIndexed { i, l ->
                                if (i > 0) HorizontalDivider(color = c.line)
                                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                    Text(if (l.at > 0) clockText(l.at) else "", style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                                    Text(l.text, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    d.parentTaskId?.let { pid -> chat.kelo.app.ui.components.LinkButton(string(Res.string.work_job_parent), onClick = { onOpenTask(pid) }) }
                }
            }
        }
    }
    if (confirmStop) {
        ConfirmDialog(string(Res.string.work_job_stop_confirm), string(Res.string.agent_task_stop), onDismiss = { confirmStop = false }, onYes = {
            vm.stopWorker(id) { tick++ }
        })
    }
    if (answering) MessageSheet(onSend = { text -> vm.messageWorker(id, text) { tick++ } }, onClose = { answering = false })
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Kelo.colors.accent, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val c = Kelo.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** 열린 브라우저: every tab the agent keeps (working ones first, closed ones last); a tap opens it live. */
@Composable
fun OpenBrowsersPage(vm: AgentViewModel, onBack: () -> Unit, onOpenLive: (String) -> Unit) {
    val c = Kelo.colors
    val state by vm.browser.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadBrowser() }
    AgentSubPage(string(Res.string.work_open_browsers), onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val data = state.data
            when {
                data == null && state.failed -> ErrorText(string(Res.string.connect_failed), small = true)
                data == null -> SkeletonRows(3)
                !data.available -> EmptyNote(string(Res.string.agent_browser_none))
                data.items.isEmpty() -> EmptyNote(string(Res.string.agent_browser_empty))
                else -> Panel {
                    data.items.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        TabRow(t) { onOpenLive(t.id) }
                    }
                }
            }
            MutedText(string(Res.string.work_open_browsers_note), small = true)
        }
    }
}
