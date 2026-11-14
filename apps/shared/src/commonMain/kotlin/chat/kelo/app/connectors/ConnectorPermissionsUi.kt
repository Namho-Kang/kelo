package chat.kelo.app.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.Card
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.Segmented
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import org.jetbrains.compose.resources.StringResource

/** "What Kelo may do" on a connected connector's page: allow / ask / off per kind of action and per single action. */
@Composable
internal fun PermissionsSection(id: String) {
    val sessions = LocalAppServices.current.sessions
    val vm = viewModel(key = "connector-detail") { ConnectorDetailViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.loadPermissions(id) }
    val p = ui.permissions ?: return
    if (ui.permsMissing || p.kinds.isEmpty()) return
    var confirmReset by remember(id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(string(Res.string.perm_title), style = MaterialTheme.typography.titleSmall, color = Kelo.colors.muted, modifier = Modifier.padding(start = 2.dp))
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MutedText(string(Res.string.perm_about), small = true)
                p.kinds.forEach { k ->
                    KindRow(k, enabled = !ui.permsBusy, onKind = { m -> vm.setKind(id, k.kind, m) }, onAction = { a, m -> vm.setAction(id, a, m) })
                }
                if (PermissionRules.changed(p)) LinkButton(string(Res.string.perm_reset), onClick = { confirmReset = true })
            }
        }
    }
    if (confirmReset) ConfirmDialog(string(Res.string.perm_reset_q), string(Res.string.perm_reset), danger = false,
        onYes = { confirmReset = false; vm.resetPermissions(id) }, onDismiss = { confirmReset = false })
}

private fun kindName(kind: String): StringResource = when (kind) {
    "read" -> Res.string.perm_kind_read
    "change" -> Res.string.perm_kind_change
    "send" -> Res.string.perm_kind_send
    else -> Res.string.perm_kind_delete
}

private fun modeName(mode: String): StringResource = when (mode) {
    "allow" -> Res.string.perm_allow
    "ask" -> Res.string.perm_ask
    else -> Res.string.perm_off
}

@Composable
private fun KindRow(k: PermissionKind, enabled: Boolean, onKind: (String) -> Unit, onAction: (PermissionAction, String) -> Unit) {
    val c = Kelo.colors
    var open by rememberSaveable(k.kind) { mutableStateOf(false) }
    var confirmOff by remember(k.kind) { mutableStateOf(false) }
    val words = PermissionRules.MODES.map { string(modeName(it)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(string(kindName(k.kind)), color = c.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Segmented(words, PermissionRules.MODES.indexOf(k.mode)) { i ->
            val m = PermissionRules.MODES[i]
            if (enabled && m != k.mode) {
                if (k.kind == "read" && m == "off") confirmOff = true else onKind(m)
            }
        }
        val def = string(modeName(k.default))
        MutedText(if (k.mode == k.default) string(Res.string.perm_default_is, def) else string(Res.string.perm_changed_from, def), small = true)
        if (k.actions.isNotEmpty()) {
            LinkButton(string(Res.string.perm_actions_more, k.actions.size), onClick = { open = !open })
            if (open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                k.actions.forEach { a ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (a.mode != k.mode) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                            Text(a.label.ifBlank { a.id }, color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                        }
                        Segmented(words, PermissionRules.MODES.indexOf(a.mode)) { i ->
                            val m = PermissionRules.MODES[i]
                            if (enabled && m != a.mode) onAction(a, m)
                        }
                    }
                }
            }
        }
    }
    if (confirmOff) ConfirmDialog(string(Res.string.perm_read_off_q), string(Res.string.perm_off), danger = false,
        onYes = { confirmOff = false; onKind("off") }, onDismiss = { confirmOff = false })
}

/** A small muted chip in the connectors list: something differs from the defaults. */
@Composable
internal fun LimitedChip() {
    val c = Kelo.colors
    Text(
        string(Res.string.perm_limited),
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(c.neutralSoft).padding(horizontal = 9.dp, vertical = 3.dp),
        color = c.neutralInk, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
