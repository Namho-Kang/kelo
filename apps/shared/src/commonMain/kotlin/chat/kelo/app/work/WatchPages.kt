package chat.kelo.app.work

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import chat.kelo.app.ui.icons.KeloIcons
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.agent.AgentSubPage
import chat.kelo.app.agent.AgentViewModel
import chat.kelo.app.agent.BuyReady
import chat.kelo.app.agent.Watcher
import chat.kelo.app.agent.WatcherEvent
import chat.kelo.app.agent.dayClockText
import chat.kelo.app.agent.intervalText
import chat.kelo.app.agent.relText
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo

@Composable
fun watchWord(state: String): String = string(
    when (state) {
        "watching" -> Res.string.work_watch_on
        "error" -> Res.string.work_watch_error
        "done" -> Res.string.work_watch_done
        else -> Res.string.work_watch_off
    },
)

@Composable
fun watchTone(state: String): Color = when (state) {
    "watching" -> Kelo.colors.accent
    "error" -> Kelo.colors.danger
    "done" -> Kelo.colors.success
    else -> Kelo.colors.muted
}

/** A watcher on the timeline: its name, what it looks for, the last value it saw, and its switch. */
@Composable
fun WatcherRow(vm: AgentViewModel, w: Watcher, onOpen: () -> Unit) {
    val c = Kelo.colors
    val haptics = LocalHaptics.current
    val desc = string(Res.string.work_watch_switch, w.name)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpen).padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(w.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StatusChip(watchWord(w.state), watchTone(w.state))
            }
            if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            w.buy?.takeIf { it.armed }?.let { b ->
                Text(
                    string(if (b.problems.isEmpty()) Res.string.buy_ready_short else Res.string.buy_ready_problems_short, b.problems.size),
                    style = MaterialTheme.typography.labelSmall, color = if (b.problems.isEmpty()) c.success else c.danger,
                )
            }
            w.lastValue?.takeIf { it.isNotBlank() }?.let {
                Text(string(Res.string.work_watch_last_value, it), style = MaterialTheme.typography.labelSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            w.lastFiredAt?.let { Text(string(Res.string.work_watch_fired_at, relText(it)), style = MaterialTheme.typography.labelSmall, color = c.success) }
            if (w.state == "error") w.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Switch(
            checked = w.enabled, onCheckedChange = { haptics.tick(); vm.setWatcherEnabled(w, it) },
            modifier = Modifier.semantics { contentDescription = desc },
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
}

/**
 * One watcher: what it checks and how often, its last check, a check now (without the agent; needed before it may be turned on),
 * its switch, its recent events, and removing it.
 */
@Composable
fun WatcherPage(vm: AgentViewModel, id: Long, onBack: () -> Unit) {
    val c = Kelo.colors
    val state by vm.watchers.collectAsStateWithLifecycle()
    LaunchedEffect(id) { if (state.data == null) vm.loadWatchers() }
    val w = state.data?.items?.firstOrNull { it.id == id }
    var events by remember(id) { mutableStateOf<List<WatcherEvent>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id, w?.dryRun?.at, w?.lastCheckedAt) { events = runCatching { vm.watcherEvents(id) }.getOrDefault(emptyList()) }
    AgentSubPage(w?.name ?: string(Res.string.work_watching), onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (w == null) {
                if (state.failed) ErrorText(string(Res.string.connect_failed)) else SkeletonRows(3)
                return@Column
            }
            Panel {
                WatcherRow(vm, w, onOpen = {})
                if (!w.enabled && !w.canEnable) MutedText(string(Res.string.work_watch_needs_check), small = true)
                w.disabledReason?.takeIf { it.isNotBlank() && !w.enabled }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
            }
            w.buy?.takeIf { it.armed }?.let { b -> BuyReadyPanel(vm, w.id, b) }
            Panel {
                w.url?.takeIf { it.isNotBlank() }?.let { Fact(string(Res.string.work_watch_url), it) }
                if (w.everyMinutes > 0) Fact(string(Res.string.work_watch_every), intervalText(w.everyMinutes))
                w.lastCheckedAt?.let { Fact(string(Res.string.work_watch_checked), dayClockText(it)) }
                w.nextCheckAt?.let { Fact(string(Res.string.work_watch_next), relText(it)) }
                if (w.once) Fact(string(Res.string.work_watch_once), string(Res.string.work_watch_once_yes))
                w.note?.takeIf { it.isNotBlank() }?.let { Fact(string(Res.string.work_watch_note), it) }
            }
            w.dryRun?.let { d ->
                Label(string(Res.string.work_watch_last_check))
                Panel {
                    Text(
                        d.detail?.ifBlank { null } ?: d.error?.ifBlank { null } ?: d.value.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, color = if (d.ok) c.ink else c.danger,
                    )
                    d.at?.let { Text(dayClockText(it), style = MaterialTheme.typography.labelSmall, color = c.muted) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    string(if (checking) Res.string.work_watch_checking else Res.string.work_watch_check_now), enabled = !checking,
                    onClick = { checking = true; vm.dryRunWatcher(w) { checking = false } }, modifier = Modifier.weight(1f),
                )
            }
            Label(string(Res.string.work_watch_events))
            val ev = events
            when {
                ev == null -> SkeletonRows(2)
                ev.isEmpty() -> EmptyNote(string(Res.string.work_watch_events_empty))
                else -> Panel {
                    ev.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(eventWord(e.kind), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                    color = if (e.kind == "fired") c.success else if (e.kind == "error") c.danger else c.muted)
                                Text(if (e.at > 0) dayClockText(e.at) else "", style = MaterialTheme.typography.labelSmall, color = c.muted)
                            }
                            val line = e.detail?.ifBlank { null } ?: e.value
                            if (!line.isNullOrBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            chat.kelo.app.agent.TaskGrantsBlock(id, load = { vm.watcherGrants(id) }, revoke = { vm.revokeScheduleGrant(it) })
            LinkButton(string(Res.string.work_watch_delete), onClick = { confirmDelete = true }, danger = true)
        }
    }
    if (confirmDelete && w != null) {
        ConfirmDialog(string(Res.string.work_watch_delete_confirm, w.name), string(Res.string.delete), onDismiss = { confirmDelete = false }, onYes = {
            vm.deleteWatcher(w, onBack)
        })
    }
}

/** "바로 구매 준비됨": the state of a watcher that buys at its hit, what is not ready in the server's words, and a check now. */
@Composable
private fun BuyReadyPanel(vm: AgentViewModel, id: Long, start: BuyReady) {
    val c = Kelo.colors
    val scope = rememberCoroutineScope()
    var b by remember(id, start) { mutableStateOf(start) }
    var busy by remember { mutableStateOf(false) }
    Label(string(Res.string.buy_ready_title))
    Panel {
        Text(
            string(if (b.problems.isEmpty()) Res.string.buy_ready_ok else Res.string.buy_ready_not_ok),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (b.problems.isEmpty()) c.success else c.danger,
        )
        b.problems.forEach { p -> Text("· " + p.text, style = MaterialTheme.typography.bodySmall, color = c.ink) }
        when (b.signedIn) {
            true -> Fact(string(Res.string.buy_ready_login), string(Res.string.buy_ready_login_kept))
            false -> Fact(string(Res.string.buy_ready_login), string(Res.string.buy_ready_login_out))
            null -> {}
        }
        b.lastKeptAt?.let { Fact(string(Res.string.buy_ready_checked), relText(it)) }
        if (b.remembered) MutedText(string(Res.string.buy_ready_remembered), small = true)
        MutedText(string(Res.string.buy_ready_about), small = true)
        SecondaryButton(string(if (busy) Res.string.work_watch_checking else Res.string.buy_ready_check), enabled = !busy, onClick = {
            busy = true
            scope.launch { runCatching { vm.buyCheck(id) }.onSuccess { b = it }; busy = false }
        })
    }
}

@Composable
private fun eventWord(kind: String): String = string(
    when (kind) {
        "fired" -> Res.string.work_watch_ev_fired
        "error" -> Res.string.work_watch_ev_error
        "dry_run" -> Res.string.work_watch_ev_check
        "enabled" -> Res.string.work_watch_ev_on
        "disabled" -> Res.string.work_watch_ev_off
        else -> Res.string.work_watch_ev_check
    },
)

@Composable
private fun Fact(label: String, value: String) {
    val c = Kelo.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(0.65f))
    }
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

/**
 * 작업 → 예약: a 보관함 entry at the top (with how many are kept there), a button to add one, and every schedule (tap to change it;
 * swipe it to the left to put it in the archive at once, with 실행 취소 on the message that follows).
 */
@Composable
fun SchedulesPage(vm: AgentViewModel, onBack: () -> Unit, onNew: () -> Unit, onOpen: (Long) -> Unit, onArchive: () -> Unit = {}) {
    val c = Kelo.colors
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val state by vm.archive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val archivedWords = string(Res.string.agent_schedule_deleted)
    val undoWord = string(Res.string.undo)
    LaunchedEffect(Unit) { vm.loadArchive() }
    Box(Modifier.fillMaxSize()) {
        AgentSubPage(string(Res.string.work_schedules), onBack) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 보관함: easy to find, at the top
                val kept = state.data?.items?.size ?: 0
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onArchive).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(string(Res.string.schedules_archive_entry), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                    if (kept > 0) Text(kept.toString(), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                }
                chat.kelo.app.ui.components.PrimaryButton(string(Res.string.work_schedule_add), onClick = onNew, modifier = Modifier.fillMaxWidth())
                val items = schedules.data?.items.orEmpty()
                when {
                    schedules.data == null && schedules.failed -> ErrorText(string(Res.string.connect_failed), small = true)
                    schedules.data == null -> SkeletonRows(2)
                    items.isEmpty() -> EmptyNote(string(Res.string.work_schedules_empty))
                    else -> Panel {
                        items.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(color = c.line)
                            key(s.id) {
                                SwipeToTrash(onSwiped = {
                                    vm.archiveSchedule(s.id)
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        if (snackbar.showSnackbar(archivedWords, undoWord, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                            vm.restoreSchedule(s.id)
                                        }
                                    }
                                }) {
                                    Column(
                                        Modifier.fillMaxWidth().background(c.card).clickable(role = Role.Button) { onOpen(s.id) }.padding(vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(s.name, style = MaterialTheme.typography.bodyLarge, color = if (s.enabled) c.ink else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val sub = if (!s.enabled) string(Res.string.work_schedule_off) else chat.kelo.app.agent.scheduleText(s)
                                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(8.dp), containerColor = c.ink, contentColor = c.bg, actionColor = c.bg)
        }
    }
}

/**
 * 보관한 예약: schedules put away, each restorable until the server removes it. Swipe one to the left: it is deleted for good at
 * once (the message that follows has 실행 취소 for a few seconds; only then does the server delete it).
 */
@Composable
fun ArchivedSchedulesPage(vm: AgentViewModel, onBack: () -> Unit) {
    val c = Kelo.colors
    val state by vm.archive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedWords = string(Res.string.schedule_deleted_forever)
    val undoWord = string(Res.string.undo)
    LaunchedEffect(Unit) { vm.loadArchive() }
    DisposableEffect(vm) { onDispose { vm.commitArchivedErases() } }
    Box(Modifier.fillMaxSize()) {
        AgentSubPage(string(Res.string.work_archive), onBack) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val data = state.data
                when {
                    data == null && state.failed -> ErrorText(string(Res.string.connect_failed), small = true)
                    data == null -> SkeletonRows(2)
                    data.items.isEmpty() -> EmptyNote(string(Res.string.work_archive_empty))
                    else -> {
                        MutedText(string(Res.string.work_archive_note, data.keepDays), small = true)
                        Panel {
                            data.items.forEachIndexed { i, s ->
                                if (i > 0) HorizontalDivider(color = c.line)
                                key(s.id) {
                                    SwipeToTrash(onSwiped = {
                                        // the delete itself waits in the view model (not in this page's scope), so leaving the page
                                        // can't cancel it; the page closing sends it at once (DisposableEffect below)
                                        vm.eraseArchivedSoon(s.id)
                                        scope.launch {
                                            snackbar.currentSnackbarData?.dismiss()
                                            if (snackbar.showSnackbar(deletedWords, undoWord, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                                vm.undoEraseArchived(s.id)
                                            } else {
                                                vm.eraseArchived(s.id)
                                            }
                                        }
                                    }) {
                                        Row(Modifier.fillMaxWidth().background(c.card).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(s.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(chat.kelo.app.agent.scheduleText(s), style = MaterialTheme.typography.bodySmall, color = c.muted)
                                                s.purgeAt?.let { Text(string(Res.string.work_archive_until, relText(it)), style = MaterialTheme.typography.labelSmall, color = c.muted) }
                                            }
                                            SecondaryButton(string(Res.string.work_archive_restore), onClick = { vm.restoreSchedule(s.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(8.dp), containerColor = c.ink, contentColor = c.bg, actionColor = c.bg)
        }
    }
}

/** A row that, swiped to the left, shows a trash can and calls [onSwiped] (the row itself leaves with the list's next state). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToTrash(onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val c = Kelo.colors
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) { onSwiped(); true } else false
    })
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(c.danger).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(KeloIcons.Trash, contentDescription = string(Res.string.delete), tint = c.onDanger, modifier = Modifier.size(22.dp))
            }
        },
    ) { content() }
}
