@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package chat.kelo.app.work

import chat.kelo.app.agent.*

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import chat.kelo.app.ui.platform.headerHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.agent.AgentViewModel
import chat.kelo.app.agent.BrowserTabPage
import chat.kelo.app.agent.ScheduleEditPage
import chat.kelo.app.agent.TaskPage
import chat.kelo.app.agent.dayLabel
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.LocalDate

/** One row of the work list: its key, its content type and what it draws. */
class WorkRow(val key: String, val contentType: String?, val content: @Composable () -> Unit)

/** Collects the rows in the order the list shows them (the same call shape as a lazy list's `item`). */
class WorkRowSink {
    val rows = mutableListOf<WorkRow>()
    fun item(key: String, contentType: String? = null, content: @Composable () -> Unit) { rows += WorkRow(key, contentType, content) }
}

/** What the work list is built from: the view model's lists as they are now, the screen's own choices and what a tap does. */
class WorkRowsInput(
    val vm: AgentViewModel,
    val tasks: Loadable<TaskList>,
    val workers: Loadable<WorkerList>,
    val perms: Loadable<Permissions>,
    val schedules: Loadable<ScheduleList>,
    val browser: Loadable<BrowserTabs>,
    val watchers: Loadable<WatcherList>,
    val followups: Loadable<chat.kelo.app.followup.FollowupList>,
    val followupBusy: Set<String>,
    val entries: Loadable<List<chat.kelo.app.chat.vault.SecretRequest>>,
    val filter: PastFilter,
    val showPaused: Boolean,
    val onTogglePaused: () -> Unit,
    val onFilter: (PastFilter) -> Unit,
    val today: LocalDate,
    val push: (String) -> Unit,
    val onOpenLive: (String) -> Unit,
    /** The app services, read only when a tap needs them. */
    val container: () -> chat.kelo.app.AppServices,
    val offers: List<chat.kelo.app.offers.Offer> = emptyList(),
    val offerStarting: String? = null,
    val offerProblem: Pair<String, chat.kelo.app.offers.OfferStart>? = null,
    val onOfferStart: (chat.kelo.app.offers.Offer) -> Unit = {},
    val onOfferDismiss: (chat.kelo.app.offers.Offer, chat.kelo.app.offers.OfferReason) -> Unit = { _, _ -> },
)

/**
 * The rows of the 작업 timeline, in order: the one place that says what is in the list and where. The screen draws exactly this list,
 * and a jump to a section is the index of its row, so the two can never disagree.
 */
fun buildWorkRows(i: WorkRowsInput): List<WorkRow> = WorkRowSink().apply {
    val vm = i.vm; val tasks = i.tasks; val workers = i.workers; val perms = i.perms; val schedules = i.schedules; val browser = i.browser
    val watchers = i.watchers; val followups = i.followups; val followupBusy = i.followupBusy; val entries = i.entries
    val filter = i.filter; val showPaused = i.showPaused; val today = i.today; val push = i.push; val onOpenLive = i.onOpenLive; val container = i.container
    val offers = i.offers; val offerStarting = i.offerStarting; val offerProblem = i.offerProblem; val onOfferStart = i.onOfferStart; val onOfferDismiss = i.onOfferDismiss
    val taskItems = tasks.data?.items.orEmpty()
    val workerItems = workers.data?.items.orEmpty()
    val nested = Timeline.nest(taskItems, workerItems)
    val now = Timeline.now(nested)
    val asks = Timeline.asks(perms.data?.pending.orEmpty(), workerItems, followups.data?.items.orEmpty(), entries.data.orEmpty())
    val plan = Agenda.build(schedules.data?.items.orEmpty())
    val past = Timeline.past(nested, filter, today)
    val tabs = browser.data?.items.orEmpty()
    val answering = perms.data?.answering.orEmpty()
    val onTogglePaused = i.onTogglePaused
    val setFilter = i.onFilter
    fun asksSection() {
        // ---- 답이 필요해요
        item(key = SECTION_ASKS, contentType = "section") { SectionTitle(string(Res.string.work_asks), asks.size, attention = asks.isNotEmpty()) }
        if (perms.data != null && asks.isEmpty()) item(key = "asks:empty") { RailNote { EmptyLine(string(Res.string.work_asks_empty)) } }
        asks.forEach { a ->
            item(key = a.key, contentType = "ask") {
                RailItem(RailMarker.ASK, label = null) {
                    when (a) {
                        is Ask.Approval -> ApprovalAsk(vm, a, working = a.approval.id in answering)
                        is Ask.Job -> JobAsk(vm, a.worker, onOpen = { push(WorkPages.job(a.worker.id)) }, onOpenLive = onOpenLive)
                        is Ask.Hold -> HoldAsk(vm, a.followup, busy = a.followup.id in followupBusy)
                        is Ask.Entry -> EntryAsk(a, onOpen = { container().openRequests.value = chat.kelo.app.notify.OpenTarget.Entry(a.request.id) },
                            onCancel = { vm.cancelEntry(a.request) { kind -> container().agentActivity.entryClosed(kind) } })
                    }
                }
            }
        }
    }
    // ---- the tab's title and its shortcuts (open browsers, a new browser job, a new schedule, archived schedules)
    item(key = "title", contentType = "title") {
        androidx.compose.foundation.layout.Column {
            WorkShortcuts(
                onBrowsers = { push(WorkPages.BROWSERS) }, onSchedules = { push(WorkPages.ARCHIVE) },
            )
        }
    }
    if (asks.isNotEmpty()) asksSection()
    if (offers.isNotEmpty()) {
        item(key = "offers", contentType = "section") { SectionTitle(string(Res.string.offers_title), offers.size) }
        offers.forEach { o ->
            item(key = "offer:" + o.id, contentType = "offer") {
                RailItem(RailMarker.ASK, label = null) {
                    chat.kelo.app.offers.OfferCard(
                        o = o,
                        starting = offerStarting == o.id,
                        problem = offerProblem?.takeIf { it.first == o.id }?.second,
                        onStart = { onOfferStart(o) },
                        onDismiss = { reason -> onOfferDismiss(o, reason) },
                    )
                }
            }
        }
    }
    // ---- 지금 하는 일
    item(key = SECTION_NOW, contentType = "section") { SectionTitle(string(Res.string.work_now), now.count) }
    when {
        tasks.data == null && tasks.failed -> item(key = "now:err") { RailNote { ErrorText(string(Res.string.connect_failed), small = true) } }
        tasks.data == null -> item(key = "now:load") { RailNote { SkeletonRows(2) } }
        now.empty -> item(key = "now:empty") { RailNote { EmptyLine(string(Res.string.work_now_empty)) } }
    }
    now.tasks.forEach { node ->
        item(key = "now:" + node.key, contentType = "now") {
            RailItem(RailMarker.LIVE, label = null) {
                NowTaskCard(vm, node, tabs, workerItems, onOpen = { push(WorkPages.task(node.task.id)) },
                    onOpenJob = { push(WorkPages.job(it)) }, onOpenTask = { push(WorkPages.task(it)) }, onOpenLive = onOpenLive)
            }
        }
    }
    now.jobs.forEach { job ->
        item(key = "now:" + job.key, contentType = "now") {
            RailItem(RailMarker.LIVE, label = null) {
                JobCard(vm, job, onOpen = { push(WorkPages.job(job.worker.id)) }, onOpenLive = onOpenLive)
            }
        }
    }
    perms.data?.taskGrants.orEmpty().forEach { g ->
        item(key = "grant:" + g.id, contentType = "grant") {
            RailItem(RailMarker.LIVE, label = null) { TaskGrantCard(g, onRevoke = { vm.revokeTaskPermission(g) }) }
        }
    }

    // ---- 답이 필요해요 (here while nothing waits; at the top, above 지금 하는 일, while something does)
    if (asks.isEmpty()) asksSection()

    // ---- 지켜보는 중 (only when the server has watchers and there are some)
    val watching = Watching.order(watchers.data?.items.orEmpty())
    if (watching.isNotEmpty()) {
        item(key = SECTION_WATCH, contentType = "section") { SectionTitle(string(Res.string.work_watching), watching.count { it.enabled }) }
        watching.forEach { w ->
            item(key = "watch:" + w.id, contentType = "watch") {
                RailItem(if (w.enabled) RailMarker.LIVE else RailMarker.PAUSED, label = null) { WatcherRow(vm, w, onOpen = { push(WorkPages.watch(w.id)) }) }
            }
        }
    }

    // ---- 다가오는 일
    item(key = SECTION_UPCOMING, contentType = "section") {
        SectionTitle(string(Res.string.work_upcoming), plan.days.sumOf { it.entries.size } + plan.unscheduled.size)
    }
    when {
        schedules.data == null && schedules.failed -> item(key = "up:err") { RailNote { ErrorText(string(Res.string.connect_failed), small = true) } }
        schedules.data == null -> item(key = "up:load") { RailNote { SkeletonRows(2) } }
        plan.empty -> item(key = "up:empty") { RailNote { EmptyLine(string(Res.string.agent_schedules_empty)) } }
    }
    agendaItems(plan, today, showPaused, onTogglePaused = onTogglePaused, onOpen = { push(WorkPages.schedule(it)) },
        onResume = { vm.resumeSchedule(it) })

    // ---- 지난 일
    item(key = SECTION_PAST, contentType = "section") { SectionTitle(string(Res.string.work_past), null) }
    item(key = "past:filters", contentType = "filters") {
        // wraps to a second line instead of scrolling sideways, so no chip is ever cut at the edge
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(start = RAIL_WIDTH, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PastFilter.entries.forEach { f -> ChoiceChip(filterLabel(f), selected = filter == f, onClick = { setFilter(f) }) }
        }
    }
    if (tasks.data != null && past.isEmpty()) item(key = "past:empty") { RailNote { EmptyLine(string(Res.string.agent_activity_empty)) } }
    past.forEach { g ->
        item(key = "past:day:" + g.label.toString(), contentType = "day") { DayCaption(dayLabel(g.label)) }
        g.items.forEach { node ->
            item(key = "past:" + node.key, contentType = "past") {
                RailItem(if (node.problem) RailMarker.FAILED else RailMarker.DONE, label = node.task.startedAt.takeIf { it > 0 }?.let { chat.kelo.app.agent.clockText(it) }) {
                    PastRow(node, onOpen = { push(WorkPages.task(node.task.id)) }, onOpenJob = { push(WorkPages.job(it)) })
                }
            }
        }
    }
    if (tasks.data?.loadingMore == true) {
        item(key = "past:more") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Kelo.colors.muted, strokeWidth = 2.dp)
            }
        }
    }
}.rows

/** The agenda: a caption per day, each run with its time on the rail; switched-off schedules folded at the end. */
private fun WorkRowSink.agendaItems(
    plan: Agenda.Plan, today: LocalDate, showPaused: Boolean, onTogglePaused: () -> Unit, onOpen: (Long) -> Unit, onResume: (Long) -> Unit,
) {
    plan.days.forEach { day ->
        item(key = "up:day:" + day.date, contentType = "day") { DayCaption(agendaDayLabel(day.date, today)) }
        day.entries.forEach { e ->
            item(key = "up:" + day.date + ":" + e.schedule.id, contentType = "agenda") {
                RailItem(RailMarker.PLANNED, label = e.at?.let { chat.kelo.app.agent.clockText(it) }) { AgendaRow(e.schedule) { onOpen(e.schedule.id) } }
            }
        }
    }
    if (plan.unscheduled.isNotEmpty()) {
        item(key = "up:unscheduled", contentType = "day") { DayCaption(string(Res.string.work_upcoming_unscheduled)) }
        plan.unscheduled.forEach { s ->
            item(key = "up:u:" + s.id, contentType = "agenda") { RailItem(RailMarker.PLANNED, label = null) { AgendaRow(s) { onOpen(s.id) } } }
        }
    }
    // paused by a run: always shown (they want a look); switched off by hand: folded
    plan.paused.filter { it.paused }.forEach { s ->
        item(key = "up:pp:" + s.id, contentType = "agenda") { RailItem(RailMarker.ASK, label = null) { AgendaRow(s, onResume = { onResume(s.id) }) { onOpen(s.id) } } }
    }
    val off = plan.paused.filter { !it.paused }
    if (off.isNotEmpty()) {
        item(key = "up:paused", contentType = "paused") { PausedToggle(off.size, showPaused, onTogglePaused) }
        if (showPaused) off.forEach { s ->
            item(key = "up:p:" + s.id, contentType = "agenda") { RailItem(RailMarker.PAUSED, label = null) { AgendaRow(s) { onOpen(s.id) } } }
        }
    }
}

