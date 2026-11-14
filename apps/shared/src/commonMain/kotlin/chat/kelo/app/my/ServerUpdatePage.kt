package chat.kelo.app.my

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.KeyBox
import chat.kelo.app.ui.components.SubPage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch

/* 마이 → 서버 업데이트 (owner only) and its card at the top of My home. The rules are in ServerUpdate.kt (shared with the desktop). */

/** What the running job is doing, in words. */
@Composable
fun serverStepText(step: ServerUpdateRules.Step): String = string(
    when (step) {
        ServerUpdateRules.Step.PREFLIGHT -> Res.string.server_update_step_preflight
        ServerUpdateRules.Step.DOWNLOAD -> Res.string.server_update_step_download
        ServerUpdateRules.Step.VERIFY -> Res.string.server_update_step_verify
        ServerUpdateRules.Step.BACKUP -> Res.string.server_update_step_backup
        ServerUpdateRules.Step.RESTART -> Res.string.server_update_step_restart
        ServerUpdateRules.Step.HEALTH -> Res.string.server_update_step_health
        ServerUpdateRules.Step.ROLLBACK -> Res.string.server_update_step_rollback
        ServerUpdateRules.Step.SELF_UPDATE -> Res.string.server_update_step_self_update
        ServerUpdateRules.Step.DONE -> Res.string.server_update_step_done
        ServerUpdateRules.Step.WORKING -> Res.string.server_update_step_working
    },
)

/**
 * The highlighted card at the top of My home: a newer server release, an update running (with its step and progress), or an
 * update that failed, was undone or could not go back in the last day. Tapping it opens 서버 업데이트.
 */
@Composable
fun ServerUpdateCard(card: ServerUpdateRules.Card, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val (soft, ink) = when (card) {
        is ServerUpdateRules.Card.Available, is ServerUpdateRules.Card.Running -> c.infoSoft to c.infoInk
        ServerUpdateRules.Card.Failed, ServerUpdateRules.Card.RolledBack -> c.neutralSoft to c.neutralInk
        ServerUpdateRules.Card.RollbackFailed -> c.dangerSoft to c.dangerInk
    }
    val title = when (card) {
        is ServerUpdateRules.Card.Available -> string(Res.string.server_update_card_available, card.version)
        is ServerUpdateRules.Card.Running -> string(
            if (card.rollback) Res.string.server_update_card_going_back else Res.string.server_update_card_running, card.percent,
        )
        ServerUpdateRules.Card.Failed -> string(Res.string.server_update_card_failed)
        ServerUpdateRules.Card.RolledBack -> string(Res.string.server_update_card_rolled_back)
        ServerUpdateRules.Card.RollbackFailed -> string(Res.string.server_update_card_rollback_failed)
    }
    val sub = if (card is ServerUpdateRules.Card.Running) serverStepText(card.step) else string(Res.string.server_update_card_open)
    val icon = when (card) {
        is ServerUpdateRules.Card.Available -> KeloIcons.ArrowDown
        is ServerUpdateRules.Card.Running -> KeloIcons.Retry
        else -> KeloIcons.Alert
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(soft).clickable(role = Role.Button, onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(sub, color = ink, style = MaterialTheme.typography.bodySmall)
            }
            Icon(KeloIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        if (card is ServerUpdateRules.Card.Running) {
            LinearProgressIndicator(
                progress = { card.percent / 100f }, color = c.accent, trackColor = c.line,
                modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 6.dp, end = 4.dp),
            )
        }
    }
}

/**
 * 서버 업데이트: the version the server runs; a newer release with its date, what's new and [Update]; while an update runs its
 * step and progress (the server restarts, the app reconnects by itself); how the last one ended; "go back to <version>" when a
 * rollback point is kept; and the nightly automatic update. A server built from source shows only its version and the server's
 * message. The status and its following live in [ServerUpdateViewModel] (shared with the card on My home).
 */
@Composable
fun ServerUpdatePage(vm: ServerUpdateViewModel, onBack: () -> Unit) {
    val c = Kelo.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val st = ui.status
    val busy = ui.busy
    val checking = ui.checking
    val error = ui.error
    var confirmUpdate by remember { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.refreshOnVisit() }
    // this sign-in may no longer see it
    LaunchedEffect(ui.gone) { if (ui.gone) { vm.goneHandled(); onBack() } }
    val waiting = ui.waiting
    val gaveUp = ui.gaveUp

    SubPage(string(Res.string.server_update_title), onBack) {
        val s = st ?: return@SubPage
        val nowS = System.currentTimeMillis() / 1000.0
        val running = ServerUpdateRules.running(s)
        MyCard {
            LabelLine(string(Res.string.server_update_current), s.current?.takeIf { it.isNotBlank() } ?: "—")
            if (s.state == ServerUpdateRules.LOCAL) {
                MutedText(s.message?.takeIf { it.isNotBlank() } ?: string(Res.string.server_update_local_fallback), small = true)
                return@MyCard
            }
            when {
                running -> KeyBox(first = false) { Running(s.job, waiting, gaveUp) }
                ServerUpdateRules.canUpdate(s) -> KeyBox(first = false) {
                    val latest = s.latest!!
                    LabelLine(string(Res.string.server_update_new_version), latest.version)
                    ServerUpdateRules.releaseSeconds(latest.date)?.let { MutedText(string(Res.string.server_update_released, fullDate(it)), small = true) }
                    latest.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                        Text(string(Res.string.server_update_whats_new), style = MaterialTheme.typography.titleSmall, color = c.ink,
                            modifier = Modifier.padding(top = 4.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                    PrimaryButton(string(Res.string.server_update_button), onClick = { confirmUpdate = true }, enabled = !busy,
                        modifier = Modifier.padding(top = 6.dp))
                }
                !s.updateAvailable -> MutedText(string(Res.string.server_update_up_to_date), small = true)
            }
            if (!running) {
                KeyBox(first = false) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MutedText(
                            when {
                                checking -> string(Res.string.server_update_checking)
                                s.checkedAt == null || s.checkedAt!! <= 0.0 -> string(Res.string.server_update_never_checked)
                                else -> string(Res.string.server_update_checked, agoText(s.checkedAt))
                            },
                            small = true, modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(string(Res.string.server_update_check_now), enabled = !busy && !checking, onClick = {
                            vm.checkNow()
                        })
                    }
                }
            }
        }

        ServerUpdateRules.result(s, nowS)?.let { ResultCard(it) }

        if (ServerUpdateRules.canRollback(s)) {
            LinkButton(string(Res.string.server_update_go_back, s.rollback?.version.orEmpty()), onClick = { confirmBack = true },
                modifier = Modifier.padding(start = 4.dp))
        }

        if (ServerUpdateRules.showAuto(s)) {
            val title = string(Res.string.server_update_auto_title)
            MyCard {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = s.autoUpdate, enabled = !busy, role = Role.Switch) { on ->
                        vm.setAuto(on)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(
                        checked = s.autoUpdate, onCheckedChange = null, enabled = !busy, modifier = Modifier.semantics { contentDescription = title },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                    )
                }
                MutedText(string(Res.string.server_update_auto_body), small = true)
                ServerUpdateRules.tonight(s)?.let { Text(string(Res.string.server_update_auto_tonight, it), color = c.accent, style = MaterialTheme.typography.bodySmall) }
            }
        }
        error?.let { ErrorText(it, small = true) }
    }

    if (confirmUpdate) {
        val v = st?.latest?.version.orEmpty()
        AskDialog(
            title = string(Res.string.server_update_confirm_title, v), body = string(Res.string.server_update_confirm_body),
            yes = string(Res.string.server_update_button), onDismiss = { confirmUpdate = false },
            onYes = vm::update,
        )
    }
    if (confirmBack) {
        val v = st?.rollback?.version.orEmpty()
        AskDialog(
            title = string(Res.string.server_update_go_back_title, v), body = string(Res.string.server_update_go_back_body),
            yes = string(Res.string.server_update_go_back_yes), onDismiss = { confirmBack = false }, danger = true,
            onYes = vm::goBack,
        )
    }
}

/** A label over its value ("지금 버전" / "0.4.0"). */
@Composable
private fun LabelLine(label: String, value: String) {
    val c = Kelo.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.ink, fontWeight = FontWeight.SemiBold)
    }
}

/** The running job: its step, the bar, the note that the server restarts, and whether the server answers. */
@Composable
private fun Running(job: ServerUpdateJob?, waiting: Boolean, gaveUp: Boolean) {
    val c = Kelo.colors
    val pct = ServerUpdateRules.percent(job)
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val versions = listOfNotNull(job?.from?.takeIf { it.isNotBlank() }, job?.to?.takeIf { it.isNotBlank() })
        if (versions.size == 2) Text("${versions[0]} → ${versions[1]}", style = MaterialTheme.typography.bodySmall, color = c.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(serverStepText(ServerUpdateRules.step(job)), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text("$pct%", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        LinearProgressIndicator(progress = { pct / 100f }, color = c.accent, trackColor = c.line, modifier = Modifier.fillMaxWidth())
        MutedText(string(Res.string.server_update_running_note), small = true)
        when {
            gaveUp -> ErrorText(string(Res.string.server_update_gave_up), small = true)
            waiting -> Text(string(Res.string.server_update_waiting), style = MaterialTheme.typography.bodySmall, color = c.accent)
        }
    }
}

/** How the last update (or go-back) ended. */
@Composable
private fun ResultCard(r: ServerUpdateRules.Result) {
    val c = Kelo.colors
    val (soft, ink) = when (r) {
        is ServerUpdateRules.Result.Updated, is ServerUpdateRules.Result.WentBack -> c.successSoft to c.successInk
        is ServerUpdateRules.Result.Failed, is ServerUpdateRules.Result.RolledBack -> c.neutralSoft to c.neutralInk
        is ServerUpdateRules.Result.RollbackFailed -> c.dangerSoft to c.dangerInk
    }
    val title = when (r) {
        is ServerUpdateRules.Result.Updated ->
            r.version?.let { string(Res.string.server_update_done, it) } ?: string(Res.string.server_update_done_plain)
        is ServerUpdateRules.Result.WentBack ->
            r.version?.let { string(Res.string.server_update_went_back, it) } ?: string(Res.string.server_update_done_plain)
        is ServerUpdateRules.Result.Failed -> string(if (r.goBack) Res.string.server_update_go_back_failed else Res.string.server_update_failed)
        is ServerUpdateRules.Result.RolledBack ->
            r.version?.let { string(Res.string.server_update_rolled_back, it) } ?: string(Res.string.server_update_rolled_back_plain)
        is ServerUpdateRules.Result.RollbackFailed -> string(Res.string.server_update_rollback_failed)
    }
    val data = when (r) {
        is ServerUpdateRules.Result.RolledBack -> string(if (r.dataRestored) Res.string.server_update_data_restored else Res.string.server_update_data_kept)
        is ServerUpdateRules.Result.WentBack -> if (r.dataRestored) string(Res.string.server_update_data_restored) else null
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(soft).semantics { liveRegion = LiveRegionMode.Polite }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (r.auto) Text(string(Res.string.server_update_auto_tag), style = MaterialTheme.typography.labelMedium, color = ink)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = ink, fontWeight = FontWeight.SemiBold)
        data?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ink) }
        r.error?.let { Text(string(Res.string.server_update_error_detail, it), style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.8f)) }
    }
}

/** A question with a title, the explanation under it, and yes / cancel. */
@Composable
private fun AskDialog(title: String, body: String, yes: String, onYes: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    val c = Kelo.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(title, color = c.ink, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, color = c.muted, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { onDismiss(); onYes() }) { Text(yes, color = if (danger) c.danger else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}
