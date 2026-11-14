package chat.kelo.app.my

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.keloViewModel
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay

private const val TRASH = "trash"

/** The short meaning of a core file, shown as its row's value. */
@Composable
private fun coreMeaning(path: String): String? = when (path) {
    "assistant.md" -> string(Res.string.files_core_assistant)
    "user.md" -> string(Res.string.files_core_user)
    "MEMORY.md" -> string(Res.string.files_core_memory)
    "AGENTS.md" -> string(Res.string.files_core_agents)
    "CHECKLIST.md" -> string(Res.string.files_core_checklist)
    "NOTIFY_PREFS.md" -> string(Res.string.files_core_notify)
    else -> null
}

/**
 * 마이 → Kelo의 파일: the files Kelo keeps for itself. Folders and files push onto the page's own stack ("dir:<path>", "file:<path>",
 * "trash"); back pops it and leaves the page when it is empty. Writable files can be edited; core files go back to the start rather
 * than being deleted; deleted files wait 30 days in the trash.
 */
@Composable
fun KeloFilesPage(onBack: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val vm = keloViewModel("my-kelo-files") { KeloFilesViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var at by rememberSaveable { mutableStateOf(listOf<String>()) }
    var leaveAsk by remember { mutableStateOf(false) }
    val top = at.lastOrNull().orEmpty()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(top) {
        when {
            top.startsWith("file:") -> vm.openFile(top.removePrefix("file:"))
            top == TRASH -> vm.loadTrash()
        }
    }
    LaunchedEffect(ui.note) {
        if (ui.note != null) { delay(3000); vm.clearNote() }
    }
    fun pop() { if (at.isEmpty()) onBack() else at = at.dropLast(1) }
    val dirty = ui.draft != null && ui.draft != ui.open?.content
    val back = { if (top.startsWith("file:") && dirty) leaveAsk = true else pop() }

    val title = when {
        top == TRASH -> string(Res.string.files_trash_title)
        top.startsWith("file:") -> top.removePrefix("file:").substringAfterLast('/')
        top.startsWith("dir:") -> top.removePrefix("dir:").substringAfterLast('/')
        else -> string(Res.string.files_title)
    }
    SubPage(title, back) {
        when {
            top == TRASH -> TrashView(ui, vm)
            top.startsWith("file:") -> FileView(top.removePrefix("file:"), ui, vm, ::pop)
            else -> FolderView(top.removePrefix("dir:").takeIf { top.startsWith("dir:") }.orEmpty(), ui, vm, { at = at + it })
        }
        ui.note?.let { MutedText(string(it)) }
    }
    if (leaveAsk) {
        ConfirmDialog(string(Res.string.files_discard_q), string(Res.string.files_discard), onYes = { vm.cancelEdit(); pop() }, onDismiss = { leaveAsk = false })
    }
}

@Composable
private fun FolderView(folder: String, ui: KeloFilesUiState, vm: KeloFilesViewModel, push: (String) -> Unit) {
    var newFolder by remember { mutableStateOf(false) }
    if (folder.isEmpty()) MutedText(string(Res.string.files_about))
    val all = ui.entries
    if (all == null) {
        if (ui.failed) {
            ErrorText(string(Res.string.files_load_failed))
            SecondaryButton(string(Res.string.retry), onClick = vm::load)
        } else SkeletonRows(4)
        return
    }
    val rows = KeloFileRules.children(all, folder)
    MenuGroup {
        rows.forEach { e ->
            row {
                MenuRow(
                    KeloIcons.File, e.name,
                    { push(if (e.isDir) "dir:${e.path.trimEnd('/')}" else "file:${e.path}") },
                    value = if (e.isDir) null else coreMeaning(e.path) ?: e.bytes?.let { fileSize(it) },
                )
            }
        }
    }
    if (KeloFileRules.canMakeFolder(folder)) SecondaryButton(string(Res.string.files_new_folder), onClick = { newFolder = true })
    if (folder.isEmpty()) MenuGroup { row { MenuRow(KeloIcons.Trash, string(Res.string.files_trash_title), { push(TRASH) }) } }
    if (newFolder) NewFolderDialog({ newFolder = false }) { vm.makeFolder(folder, it); newFolder = false }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onMake: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val c = Kelo.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(string(Res.string.files_new_folder), color = c.ink) },
        text = { KeloTextField(name, { name = it }, string(Res.string.files_folder_name)) },
        confirmButton = { TextButton(onClick = { onMake(name) }) { Text(string(Res.string.files_make), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(string(Res.string.files_cancel), color = c.ink) } },
    )
}

@Composable
private fun FileView(path: String, ui: KeloFilesUiState, vm: KeloFilesViewModel, pop: () -> Unit) {
    val file = ui.open?.takeIf { it.path == path }
    val entry = ui.entries?.firstOrNull { it.path == path }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    var ask by remember { mutableStateOf("") }
    if (file == null) { SkeletonRows(3); return }
    val draft = ui.draft
    val noteText = draft.orEmpty()   // a whole text (a note/memory file), not one value: it may wrap
    if (draft != null) {
        KeloTextField(
            noteText, vm::setDraft, "", Modifier.heightIn(min = 360.dp), singleLine = false, textStyle = mono,
            maxLength = 100_000,
        )
        PrimaryButton(string(Res.string.files_save), onClick = vm::save, enabled = !ui.busy && draft != file.content)
        SecondaryButton(string(Res.string.files_cancel), onClick = vm::cancelEdit)
        return
    }
    SelectionContainer { Text(file.content, style = mono, color = Kelo.colors.ink) }
    if (entry?.writable == true) PrimaryButton(string(Res.string.files_edit), onClick = vm::edit)
    else MutedText(string(Res.string.files_read_only), small = true)
    if (KeloFileRules.isCore(path)) SecondaryButton(string(Res.string.files_reset), onClick = { ask = "reset" }, enabled = !ui.busy)
    if (KeloFileRules.canDelete(path)) SecondaryButton(string(Res.string.files_delete), onClick = { ask = "delete" }, enabled = !ui.busy)
    when (ask) {
        "reset" -> ConfirmDialog(string(Res.string.files_reset_q), string(Res.string.files_reset), onYes = { vm.reset(path) }, onDismiss = { ask = "" }, danger = false)
        "delete" -> ConfirmDialog(string(Res.string.files_delete_q), string(Res.string.files_delete), onYes = { vm.delete(path, pop) }, onDismiss = { ask = "" })
    }
}

@Composable
private fun TrashView(ui: KeloFilesUiState, vm: KeloFilesViewModel) {
    var purge by remember { mutableStateOf<String?>(null) }
    MutedText(string(Res.string.files_trash_about))
    val items = ui.trash
    if (items == null) { SkeletonRows(3); return }
    if (items.isEmpty()) { MutedText(string(Res.string.files_trash_empty)); return }
    val now = System.currentTimeMillis() / 1000.0
    items.forEach { t ->
        val days = kotlin.math.ceil((t.purgeAt - now) / 86400.0).toInt().coerceAtLeast(0)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.path, style = MaterialTheme.typography.bodyLarge, color = Kelo.colors.ink)
            MutedText(string(Res.string.files_trash_left, days), small = true)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.restore(t.id) }) { Text(string(Res.string.files_restore), color = Kelo.colors.accent) }
                TextButton(onClick = { purge = t.id }) { Text(string(Res.string.files_purge), color = Kelo.colors.danger) }
            }
        }
    }
    purge?.let { id -> ConfirmDialog(string(Res.string.files_purge_q), string(Res.string.files_purge), onYes = { vm.purge(id) }, onDismiss = { purge = null }) }
}
