@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package chat.kelo.app.work

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
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
import chat.kelo.app.ui.platform.rememberViewModel
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

/** The 작업 tab's pages over its timeline, written as short strings so the stack survives a rotation. */
object WorkPages {
    const val NEW_SCHEDULE = "schedule:new"
    const val BROWSERS = "browsers"
    const val ARCHIVE = "archive"
    /** 보관한 예약 (reached from 예약's 보관함 entry) */
    const val ARCHIVED = "archived"
    fun watch(id: Long) = "watch:$id"
    fun task(id: Long) = "task:$id"
    fun job(id: String) = "job:$id"
    fun schedule(id: Long) = "schedule:$id"
}

/**
 * The 작업 tab: one timeline of everything the agent does. 지금 하는 일 (what runs now, background jobs nested under the task that
 * handed them off, each with its live browser one tap away), 답이 필요해요 (approvals, payments that need this phone's card, jobs
 * stopped for the user), 다가오는 일 (the schedules as an agenda by day and time) and 지난 일 (finished work by day, with a filter).
 * The bar's menu opens the browser tabs that are open, a new browser job and a new schedule. [start] is a place a notification
 * asked for; [onOpenLive] shows a browser tab live over everything.
 */
@Composable
fun WorkScreen(start: OpenTarget?, onStartUsed: () -> Unit, onOpenLive: (String) -> Unit, onClose: (() -> Unit)? = null, hooks: WorkScreenHooks = WorkScreenHooks.Default) {
    val container = LocalAppServices.current
    val vm: AgentViewModel = viewModel { AgentViewModel(container.sessions, hooks.platform) }
    val offersVm = rememberViewModel { chat.kelo.app.offers.OffersViewModel(container.sessions) }
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun setStack(s: List<String>) { stack = s; hooks.hideChrome(CHROME_KEY, s.isNotEmpty()) }
    fun push(p: String) = setStack(stack + p)
    val back: () -> Unit = { setStack(stack.dropLast(1)) }
    // the app's own bars step aside while a sub page is open, and come back when this screen leaves
    androidx.compose.runtime.DisposableEffect(stack.isNotEmpty()) {
        hooks.hideChrome(CHROME_KEY, stack.isNotEmpty())
        onDispose { hooks.hideChrome(CHROME_KEY, false) }
    }
    val list = rememberLazyListState()
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(start) {
        when (start) {
            is OpenTarget.Task -> setStack(listOf(WorkPages.task(start.id)))
            is OpenTarget.Schedule -> setStack(listOf(WorkPages.schedule(start.id)))
            OpenTarget.Approvals -> { setStack(emptyList()); jumpTo = SECTION_ASKS }
            OpenTarget.Schedules -> { setStack(emptyList()); jumpTo = SECTION_UPCOMING }
            OpenTarget.Activity -> { setStack(emptyList()); jumpTo = SECTION_NOW }
            OpenTarget.Watchers -> { setStack(emptyList()); jumpTo = SECTION_WATCH }
            else -> {}
        }
        if (start != null) onStartUsed()
    }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(if (m.detail != null) loadString(m.text, m.detail) else loadString(m.text, *m.args.toTypedArray()))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.loadWork(); offersVm.load() }
    // new things show up while the tab is open: often while the agent works, now and then otherwise
    val status by container.agentActivity.status.collectAsStateWithLifecycle()
    val following by container.agentActivity.following.collectAsStateWithLifecycle()
    val busy = status?.busy == true || following || (status?.pendingApprovals ?: 0) > 0
    val workChanged by container.agentActivity.workChanged.collectAsStateWithLifecycle()
    LaunchedEffect(busy) {
        while (true) {
            delay(if (busy) LIVE_POLL_MS else IDLE_POLL_MS)
            vm.refreshLive()
            offersVm.load()
        }
    }
    LaunchedEffect(workChanged) { if (workChanged > 0) vm.refreshLive() }
    LaunchedEffect(hooks.reselect) { hooks.reselect.collect { if (stack.isNotEmpty()) back() else list.animateScrollToItem(0) } }

    CompositionLocalProvider(LocalWorkHooks provides hooks, LocalCardSenders provides hooks.cardSenders()) {
    Box(Modifier.fillMaxSize()) {
        hooks.PageStack(state = stack, back = if (stack.isEmpty()) null else stack.dropLast(1), onBack = back) { shown ->
            val page = shown.lastOrNull()
            // in a window the app's floating header sits on top of the page: a page and the title bar start below it (0 on the phone)
            val below = headerHeight()
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(if (page != null) Modifier.background(Kelo.colors.bg).padding(top = below) else Modifier)) {
            when {
                page == null -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Kelo.colors.bg).statusBarsPadding().padding(top = below)) {
                    // opened from the phone's agent the bar has a back arrow; as a tab of a window there is nothing to go back to
                    if (onClose != null) chat.kelo.app.ui.components.BackTitleBar(string(Res.string.tab_work), onClose)
                    else Text(string(Res.string.tab_work), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge, color = Kelo.colors.ink)
                    Box(Modifier.weight(1f)) {
                        androidx.compose.runtime.CompositionLocalProvider(chat.kelo.app.ui.platform.LocalHeaderHeight provides 0.dp) {
                            Timeline(vm, offersVm, list, jumpTo, onJumped = { jumpTo = null }, push = ::push, onOpenLive = onOpenLive)
                        }
                    }
                }
                page.startsWith("task:") -> TaskPage(vm, page.removePrefix("task:").toLongOrNull() ?: 0, back, onOpenTab = onOpenLive)
                page.startsWith("job:") -> JobPage(vm, page.removePrefix("job:"), back, onOpenLive = onOpenLive,
                    onOpenTask = { push(WorkPages.task(it)) })
                page == WorkPages.NEW_SCHEDULE -> ScheduleEditPage(vm, null, back)
                page.startsWith("schedule:") -> ScheduleEditPage(vm, page.removePrefix("schedule:").toLongOrNull(), back)
                page == WorkPages.BROWSERS -> OpenBrowsersPage(vm, back, onOpenLive)
                page == WorkPages.ARCHIVE -> SchedulesPage(vm, back, onNew = { push(WorkPages.NEW_SCHEDULE) }, onOpen = { push("schedule:$it") },
                    onArchive = { push(WorkPages.ARCHIVED) })
                page == WorkPages.ARCHIVED -> ArchivedSchedulesPage(vm, back)
                page.startsWith("watch:") -> WatcherPage(vm, page.removePrefix("watch:").toLongOrNull() ?: 0, back)
                else -> Timeline(vm, offersVm, list, jumpTo, onJumped = { jumpTo = null }, push = ::push, onOpenLive = onOpenLive)
            }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (stack.isEmpty()) hooks.bottomInset + 8.dp else 16.dp).padding(horizontal = 12.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(8.dp), containerColor = Kelo.colors.ink, contentColor = Kelo.colors.bg)
        }
    }
    }
}

private const val CHROME_KEY = "work-sub"
private const val LIVE_POLL_MS = 4_000L
private const val IDLE_POLL_MS = 20_000L
private const val SEEN_AFTER_MS = 1_500L
const val SECTION_NOW = "sec:now"
const val SECTION_ASKS = "sec:asks"
const val SECTION_UPCOMING = "sec:upcoming"
const val SECTION_WATCH = "sec:watch"
const val SECTION_PAST = "sec:past"

@Composable
private fun Timeline(
    vm: AgentViewModel, offersVm: chat.kelo.app.offers.OffersViewModel, list: androidx.compose.foundation.lazy.LazyListState, jumpTo: String?, onJumped: () -> Unit,
    push: (String) -> Unit, onOpenLive: (String) -> Unit,
) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val workers by vm.workers.collectAsStateWithLifecycle()
    val perms by vm.perms.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val browser by vm.browser.collectAsStateWithLifecycle()
    val watchers by vm.watchers.collectAsStateWithLifecycle()
    val followups by vm.followups.collectAsStateWithLifecycle()
    val followupBusy by vm.followupBusy.collectAsStateWithLifecycle()
    val offersState by offersVm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(PastFilter.ALL) }
    var showPaused by rememberSaveable { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val taskItems = tasks.data?.items.orEmpty()
    val workerItems = workers.data?.items.orEmpty()
    val nested = remember(taskItems, workerItems) { Timeline.nest(taskItems, workerItems) }
    val now = remember(nested) { Timeline.now(nested) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val container = chat.kelo.app.LocalAppServices.current
    val asks = remember(perms.data?.pending, workerItems, followups.data, entries.data) {
        Timeline.asks(perms.data?.pending.orEmpty(), workerItems, followups.data?.items.orEmpty(), entries.data.orEmpty())
    }
    val plan = remember(schedules.data) { Agenda.build(schedules.data?.items.orEmpty()) }
    val past = remember(nested, filter) { Timeline.past(nested, filter, today) }
    val tabs = browser.data?.items.orEmpty()
    val answering = perms.data?.answering.orEmpty()

    // looked at: the finished ones count as seen (their dots stay for this visit)
    val topDone = taskItems.firstOrNull { !it.running }?.id
    LaunchedEffect(topDone) {
        if (topDone == null) return@LaunchedEffect
        delay(SEEN_AFTER_MS)
        vm.markSeen(topDone)
    }
    // more of the past when its end comes into view
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && total > 0 && last >= total - 3 }
            .collect { vm.loadMoreTasks() }
    }
    // a notification asked for one section
    // (the target stays pending until the lists it depends on have been answered; then it goes once)
    val permsLoaded = perms.data != null || perms.failed
    val schedulesLoaded = schedules.data != null || schedules.failed
    val watchersLoaded = watchers.data != null || watchers.failed
    val rows = remember(
        tasks, workers, perms, schedules, browser, watchers, followups, followupBusy, entries, filter, showPaused, vm, offersState,
    ) {
        buildWorkRows(
            WorkRowsInput(
                vm = vm, tasks = tasks, workers = workers, perms = perms, schedules = schedules, browser = browser, watchers = watchers,
                followups = followups, followupBusy = followupBusy, entries = entries, filter = filter, showPaused = showPaused,
                onTogglePaused = { showPaused = !showPaused }, onFilter = { filter = it }, today = today, push = push,
                onOpenLive = onOpenLive, container = { container },
                offers = offersState.items,
                offerStarting = offersState.starting,
                offerProblem = offersState.problem,
                onOfferStart = { o ->
                    offersVm.start(o) { id ->
                        container.chatFollowTurn.value = id
                        container.openRequests.value = chat.kelo.app.notify.OpenTarget.Chat
                    }
                },
                onOfferDismiss = { o, reason ->
                    offersVm.dismiss(o, reason)
                },
            ),
        )
    }
    // a notification asked for one section: its place is where its row sits in the one list the screen draws
    LaunchedEffect(jumpTo, rows, tasks.data != null, permsLoaded, schedulesLoaded, watchersLoaded) {
        val key = jumpTo ?: return@LaunchedEffect
        if (!SectionJump.ready(key, tasks.data != null, permsLoaded, schedulesLoaded, watchersLoaded)) return@LaunchedEffect
        val i = rows.indexOfFirst { it.key == key }
        if (i >= 0) list.animateScrollToItem(i)
        onJumped()
    }

    WorkRowsList(
        rows, list,
        PaddingValues(start = 12.dp, end = 16.dp, top = headerHeight() + 8.dp, bottom = LocalWorkHooks.current.bottomInset + 32.dp),
    )
}

/** Draws the rows, exactly as [buildWorkRows] lists them. */
@Composable
internal fun WorkRowsList(rows: List<WorkRow>, list: androidx.compose.foundation.lazy.LazyListState, contentPadding: PaddingValues) {
    chat.kelo.app.ui.components.KeepAtTopUntilScrolled(list, rows.map { it.key })
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
        items(rows, key = { it.key }, contentType = { it.contentType }) { row -> row.content() }
    }
}

/** The shortcuts under the tab's title, as small chips that wrap onto a second line in long languages. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorkShortcuts(onBrowsers: () -> Unit, onSchedules: () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShortcutChip(KeloIcons.Globe, string(Res.string.work_open_browsers), onBrowsers)
        ShortcutChip(KeloIcons.Calendar, string(Res.string.work_schedules), onSchedules)
    }
}

@Composable
private fun ShortcutChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val c = Kelo.colors
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = c.card, contentColor = c.ink),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun filterLabel(f: PastFilter): String = string(
    when (f) {
        PastFilter.ALL -> Res.string.work_filter_all
        PastFilter.DONE -> Res.string.work_filter_done
        PastFilter.PROBLEMS -> Res.string.work_filter_problems
        PastFilter.AUTOMATIC -> Res.string.work_filter_automatic
    },
)

@Composable
internal fun agendaDayLabel(date: LocalDate, today: LocalDate): String = when (Agenda.whenOf(date, today)) {
    Agenda.When.TODAY -> string(Res.string.work_day_today, chat.kelo.app.agent.dayText(date))
    Agenda.When.TOMORROW -> string(Res.string.work_day_tomorrow, chat.kelo.app.agent.dayText(date))
    Agenda.When.LATER -> chat.kelo.app.agent.dayText(date)
}

/** A browser tab live, over everything (from a chat card, a notification or a task). */
@Composable
fun LiveOverlay(tabId: String, takeControl: Boolean = false, picture: String? = null, onClose: () -> Unit) {
    val container = LocalAppServices.current
    val vm: AgentViewModel = viewModel(key = "live-overlay") { AgentViewModel(container.sessions) }
    Box(Modifier.fillMaxSize().background(Kelo.colors.bg)) { BrowserTabPage(vm, tabId, onClose, takeControl, picture) }
}
