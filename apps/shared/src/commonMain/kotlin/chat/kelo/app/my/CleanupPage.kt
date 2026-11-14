package chat.kelo.app.my

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.KeyBox
import chat.kelo.app.ui.components.SubPage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.components.TokenText
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.platform.keloViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A group's name and its one-line explanation, in the app's words (an unknown newer group: the server's own name). */
@Composable
private fun groupTitle(g: CleanupGroup): String = when (g.id) {
    "uploads" -> string(Res.string.storage_group_uploads)
    "images" -> string(Res.string.storage_group_images)
    "browser_history" -> string(Res.string.storage_group_browser)
    "results" -> string(Res.string.storage_group_results)
    "backups" -> string(Res.string.storage_group_backups)
    else -> g.name.ifBlank { g.id }
}

@Composable
private fun groupNote(g: CleanupGroup, days: Int): String? = when (g.id) {
    "uploads" -> string(Res.string.storage_group_uploads_desc, days)
    "images" -> string(Res.string.storage_group_images_desc)
    "browser_history" -> string(Res.string.storage_group_browser_desc)
    "results" -> string(Res.string.storage_group_results_desc, days)
    "backups" -> string(Res.string.storage_group_backups_desc)
    else -> null
}

/** What else is worth knowing about one item ("읽은 글은 남음", "휴지통", a backup's kind). */
@Composable
private fun itemNote(i: CleanupItem): String? = when {
    i.keepsText -> string(Res.string.storage_item_text_kept)
    i.trashed -> string(Res.string.storage_item_trashed)
    i.kind == "update" -> string(Res.string.storage_backup_update)
    i.kind == "memory" -> string(Res.string.storage_backup_memory)
    i.kind == "migration" -> string(Res.string.storage_backup_migration)
    else -> null
}

/**
 * 마이 → Kelo 서버 자원 → 정리하기: what can safely be removed, grouped with sizes and dates. Nothing is chosen at first; the user
 * ticks items (or a whole group), presses 지우기 and confirms in a dialog. Only then does the server delete, and only items on its
 * own current list (notes, memory and the chat history are never on it). The result line says how much room was freed.
 */
@Composable
fun CleanupPage(onBack: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val vm = keloViewModel("my-cleanup") { CleanupViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val c = Kelo.colors
    val list = ui.list
    val selected = ui.selected
    val busy = ui.busy
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }

    SubPage(string(Res.string.storage_cleanup_title), onBack) {
        val l = list
        if (l == null) {
            val why = ui.failed
            if (why != null) {
                ErrorText(string(why))
                if (!ui.ownerOnly) SecondaryButton(string(Res.string.retry), onClick = vm::load)
            } else {
                SkeletonRows(4)
            }
            return@SubPage
        }
        ui.result?.let { r ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.successSoft).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(string(Res.string.storage_deleted, storageSize(r.freed)), color = c.successInk, style = MaterialTheme.typography.bodyMedium)
                if (r.skipped.isNotEmpty()) Text(string(Res.string.storage_skipped), color = c.successInk, style = MaterialTheme.typography.bodySmall)
            }
        }
        MutedText(string(Res.string.storage_cleanup_intro), small = true)
        val groups = StorageRules.shown(l)
        if (groups.isEmpty()) {
            MyCard { MutedText(string(Res.string.storage_nothing)) }
            return@SubPage
        }
        groups.forEach { g ->
            MyCard {
                CheckRow(
                    checked = StorageRules.groupChecked(selected, g), enabled = !busy,
                    onToggle = { vm.toggleGroup(g) },
                ) {
                    Text(groupTitle(g), style = MaterialTheme.typography.titleSmall, color = c.ink, fontWeight = FontWeight.SemiBold)
                    Text(storageSize(g.bytes), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                groupNote(g, l.olderThanDays)?.let { MutedText(it, small = true) }
                g.items.forEach { i ->
                    KeyBox(first = false) {
                        CheckRow(checked = i.id in selected, enabled = !busy, onToggle = { vm.toggle(i.id) }) {
                            // a file name never breaks mid-word: one line, shortened in the middle (a backup with no name: its kind)
                            val note = itemNote(i)
                            Text(TokenText.fit(i.name.ifBlank { note ?: i.id }), style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            val line = string(Res.string.storage_item_line, storageSize(i.bytes), fullDate(i.date))
                            Text(if (note != null && i.name.isNotBlank()) string(Res.string.storage_item_line, line, note) else line,
                                style = MaterialTheme.typography.bodySmall, color = c.muted)
                        }
                    }
                }
            }
        }
        val bytes = StorageRules.selectedBytes(l, selected)
        MutedText(string(Res.string.storage_selected, selected.size, storageSize(bytes)), small = true)
        ui.error?.let { ErrorText(string(it), small = true) }
        PrimaryButton(string(Res.string.storage_delete_button), onClick = { confirm = true }, enabled = selected.isNotEmpty() && !busy)
    }

    if (confirm) {
        val bytes = StorageRules.selectedBytes(list, selected)
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = c.card,
            title = { Text(string(Res.string.storage_confirm_title), color = c.ink, style = MaterialTheme.typography.titleMedium) },
            text = { Text(string(Res.string.storage_confirm_body, storageSize(bytes)), color = c.muted, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.delete() }) { Text(string(Res.string.storage_delete_button), color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    }
}

/** A checkbox and its words; the whole row toggles (48dp high at least, as a touch target). */
@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}
