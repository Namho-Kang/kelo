package chat.kelo.app.goals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.agent.dayText
import chat.kelo.app.core.Goal
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.rememberPageViewModelStoreOwner
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One goal's own page: what the user told Kelo for it (a question they chose not to answer shows as 말하지 않음), the plan (the
 * user's own steps can be ticked; Kelo's show where they stand), numbers with a small chart, the automations and documents made for
 * it, and what happened so far. "Kelo에게 이어서 맡기기" has Kelo pick it up again in the chat.
 */
@Composable
fun GoalDetailPage(goal: Goal, onBack: () -> Unit, onEdit: (Goal) -> Unit, onOpenChat: () -> Unit) {
    // the page's view model lives as long as the page is open
    val owner = rememberPageViewModelStoreOwner("goal-detail:${goal.id}")
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { GoalDetailContent(goal, onBack, onEdit, onOpenChat) }
}

@Composable
private fun GoalDetailContent(goal: Goal, onBack: () -> Unit, onEdit: (Goal) -> Unit, onOpenChat: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val vm = viewModel(key = "goal-${goal.id}") { GoalDetailViewModel(sessions, goal) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    LaunchedEffect(goal.id) { vm.load() }
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val w = state.workspace
    // the title, note and tag as the list has them (an edit shows at once); the plan's state from the page's own load
    val g = goal.copy(plan = w?.goal?.plan ?: goal.plan, asking = w?.goal?.asking ?: goal.asking, working = w?.goal?.working ?: goal.working)
    val meta = remember(g.note) { GoalMetaCodec.parse(g.note) }
    SubPage(g.title, onBack, actions = {
        TextButton(onClick = { onEdit(g) }) { Text(string(Res.string.goal_edit), color = c.accent) }
    }) {
        if (meta.note.isNotBlank()) Text(meta.note, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (g.plan.total > 0) Chip(string(Res.string.goals_plan_count, g.plan.done, g.plan.total), c.success)
            meta.due?.let { Chip(string(Res.string.goals_due_by, dayText(it)), c.accent) }
            when (GoalsRules.activity(g)) {
                GoalActivity.WORKING -> Chip(string(Res.string.goals_working), c.success)
                GoalActivity.ASKING -> Chip(string(Res.string.goals_asking), c.accent)
                null -> {}
            }
        }
        PrimaryButton(string(Res.string.goal_resume), enabled = !state.resuming && !g.isDone, modifier = Modifier.fillMaxWidth(), onClick = {
            haptics.confirm()
            vm.resume(onOpenChat)
        })
        state.resumed?.let { MutedText(string(it), small = true) }
        if (state.failed) ErrorText(string(Res.string.connect_failed), small = true)
        if (w == null) return@SubPage

        Heading(string(Res.string.goal_facts))
        if (w.facts.isEmpty()) MutedText(string(Res.string.goal_facts_empty), small = true)
        w.facts.forEach { f -> FactRow(f) }

        Heading(string(Res.string.goal_plan))
        if (w.steps.isEmpty()) MutedText(string(Res.string.goal_plan_empty), small = true)
        w.steps.forEach { s ->
            val shown = state.ticking[s.id]?.let { s.copy(status = it) } ?: s
            StepRow(shown, onTick = { haptics.tick(); vm.tick(s) })
        }

        if (w.metrics.isNotEmpty()) {
            Heading(string(Res.string.goal_numbers))
            w.metrics.forEach { m -> MetricCard(m) }
        }
        if (w.schedules.isNotEmpty()) {
            Heading(string(Res.string.goal_schedules))
            val services = LocalAppServices.current
            w.schedules.forEach { s -> ScheduleRow(s, onOpen = { services.openRequests.value = it }) }
        }
        if (w.documents.isNotEmpty()) {
            Heading(string(Res.string.goal_documents))
            w.documents.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(KeloIcons.File, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                    Text(d.title.ifBlank { d.path.substringAfterLast('/') }, style = MaterialTheme.typography.bodyMedium,
                        color = if (d.exists) c.ink else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (w.log.isNotEmpty()) {
            Heading(string(Res.string.goal_log))
            w.log.take(20).forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(logDay(e.at), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    Text(e.text, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun logDay(at: Double): String = dayText(Instant.ofEpochMilli((at * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate())

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Kelo.colors.accent,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() })
}

@Composable
private fun Chip(text: String, tone: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun FactRow(f: GoalFact) {
    val c = Kelo.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(f.key, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(0.42f))
        if (f.skipped) {
            Text(string(Res.string.goal_fact_skipped), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.weight(0.58f))
        } else {
            Text(f.value, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(0.58f))
        }
    }
}

@Composable
private fun StepRow(s: GoalStep, onTick: () -> Unit) {
    val c = Kelo.colors
    val label = string(Res.string.goal_mark_step)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(if (s.mine) Modifier.clickable(role = Role.Checkbox, onClick = onTick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (s.mine) {
            Checkbox(
                checked = s.isDone, onCheckedChange = { onTick() },
                modifier = Modifier.semantics { contentDescription = label },
                colors = CheckboxDefaults.colors(checkedColor = c.success, uncheckedColor = c.muted, checkmarkColor = c.onSuccess),
            )
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (s.isDone) KeloIcons.Check else KeloIcons.Spark, contentDescription = null,
                    tint = if (s.isDone) c.success else c.accent, modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                s.title, style = MaterialTheme.typography.bodyLarge, color = if (s.isDone) c.muted else c.ink,
                textDecoration = if (s.isDone) TextDecoration.LineThrough else null,
            )
            if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(string(if (s.mine) Res.string.goal_owner_me else Res.string.goal_owner_kelo), if (s.mine) c.muted else c.accent)
                if (!s.mine) Chip(string(when (s.status) {
                    "done" -> Res.string.goal_status_done
                    "doing" -> Res.string.goal_status_doing
                    else -> Res.string.goal_status_todo
                }), if (s.isDone) c.success else c.muted)
                s.due?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { Chip(string(Res.string.goal_due, dayText(it)), c.muted) } }
            }
        }
    }
}

@Composable
private fun MetricCard(m: GoalMetric) {
    val c = Kelo.colors
    val last = m.values.lastOrNull()?.value
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                if (last != null) string(Res.string.goal_number_latest, GoalDetailRules.withUnit(last, m.unit)) else string(Res.string.goal_number_none),
                style = MaterialTheme.typography.bodySmall, color = if (last != null) c.ink else c.muted,
            )
        }
        val points = remember(m) { GoalDetailRules.chart(m) }
        val target = remember(m) { GoalDetailRules.targetLevel(m) }
        if (points.size >= 2) {
            val line = c.accent
            val goalLine = c.success
            val desc = m.values.joinToString(", ") { GoalDetailRules.number(it.value) }
            Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = desc }) {
                val pad = 6.dp.toPx()
                fun at(p: Pair<Float, Float>) = Offset(pad + p.first * (size.width - 2 * pad), size.height - pad - p.second * (size.height - 2 * pad))
                target?.let { t ->
                    val y = size.height - pad - t * (size.height - 2 * pad)
                    drawLine(goalLine, Offset(pad, y), Offset(size.width - pad, y), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                }
                val path = Path().apply { points.forEachIndexed { i, p -> val o = at(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
                drawCircle(line, radius = 3.5.dp.toPx(), center = at(points.last()))
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            m.start?.let { Text(string(Res.string.goal_number_start, GoalDetailRules.withUnit(it, m.unit)), style = MaterialTheme.typography.labelSmall, color = c.muted) }
            m.target?.let { Text(string(Res.string.goal_number_target, GoalDetailRules.withUnit(it, m.unit)), style = MaterialTheme.typography.labelSmall, color = c.success) }
        }
    }
}

/** A goal's schedule: a click opens it on 작업 ([onOpen] gets [chat.kelo.app.notify.OpenTarget.Schedule]), where it can be changed, paused or run. */
@Composable
internal fun ScheduleRow(s: GoalSchedule, onOpen: (chat.kelo.app.notify.OpenTarget) -> Unit) {
    val c = Kelo.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { onOpen(chat.kelo.app.notify.OpenTarget.Schedule(s.id)) }
            .padding(vertical = 2.dp),
    ) {
        Text(s.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(s.description.ifBlank { null }, if (!s.enabled) string(Res.string.goal_schedule_off) else null).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}
