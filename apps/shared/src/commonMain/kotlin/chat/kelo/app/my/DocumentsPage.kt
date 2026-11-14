package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.MarkdownText
import chat.kelo.app.core.ApiError
import chat.kelo.app.upload.UploadSizeText
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.PushHost
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.components.rememberSkeletonShown
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** What the server takes: PDF, Word, Excel, photos, videos and sound. */
private val UPLOAD_TYPES = listOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel.sheet.macroEnabled.12",
    "image/*",
    "video/*",
    "audio/*",
)

@Composable
private fun noteText(n: DocsNote): String = when (n) {
    DocsNote.Uploaded -> string(Res.string.docs_uploaded)
    is DocsNote.TooLarge -> string(Res.string.upload_too_large_limit, UploadSizeText.of(n.limit))
    DocsNote.ReadFailed -> string(Res.string.docs_read_failed)
    is DocsNote.Server -> n.text
}

/**
 * The files the user has uploaded, and only those (nothing the assistant came with is listed here): photos and videos as a grid
 * of previews, documents and sound files as rows. A photo opens on the whole screen; a video or sound file plays in the app.
 */
@Composable
fun DocumentsPage(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val vm = rememberViewModel { DocumentsViewModel(services.sessions, services.filePicker) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { vm.resetViewer() }

    val list = ui.docs
    val shown = open?.let { name -> list?.firstOrNull { it.name == name } ?: Upload("uploads/$name", kind = MediaKind.of(name)) }
    // a file's page slides over the list; the back gesture steps back to it
    androidx.compose.runtime.CompositionLocalProvider(LocalDocumentMedia provides vm) {
    PushHost(
        state = open.orEmpty(),
        depth = { if (it.isEmpty()) 0 else 1 },
        back = if (open == null) null else "",
        onBack = { open = null },
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        val u = if (state.isNotEmpty()) (shown?.takeIf { it.name == state } ?: Upload("uploads/$state", kind = MediaKind.of(state))) else null
        if (u != null) {
            FileViewer(u, vm, ui, onClose = { open = null }, onDeleted = { open = null })
        } else {
            DocumentList(
                list, ui.uploading, ui.progress, ui.note?.let { noteText(it) }.orEmpty(), onBack,
                onUpload = { vm.upload(UPLOAD_TYPES) }, onCancel = vm::cancelUpload,
                onOpen = { open = it },
            )
        }
    }
    }
}

@Composable
private fun DocumentList(
    docs: List<Upload>?, busy: Boolean, progress: UploadProgress, msg: String, onBack: () -> Unit,
    onUpload: () -> Unit, onCancel: () -> Unit, onOpen: (String) -> Unit,
) {
    SubPage(string(Res.string.docs_title), onBack) {
        MyCard {
            if (busy) {
                val total = progress.total
                if (total > 0) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), color = Kelo.colors.accent)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Kelo.colors.accent)
                Row(Modifier.fillMaxWidth()) {
                    MutedText(
                        if (total > 0) string(Res.string.docs_upload_progress, storageSize(progress.sent), storageSize(total), (progress.fraction * 100).toInt())
                        else string(Res.string.docs_uploading),
                        small = true, modifier = Modifier.weight(1f),
                    )
                    LinkButton(string(Res.string.cancel), onClick = onCancel)
                }
            } else {
                PrimaryButton(string(Res.string.docs_upload), onClick = onUpload)
            }
            MutedText(string(Res.string.docs_hint), small = true)
            if (msg.isNotEmpty()) MutedText(msg, small = true)
        }
        if (rememberSkeletonShown(docs == null)) {
            SkeletonRows(3)
        } else if (docs != null && docs.isEmpty()) {
            EmptyNote(string(Res.string.docs_empty))
        } else if (!docs.isNullOrEmpty()) {
            val media = docs.filter { it.isMedia }.sortedByDescending { it.modified }
            val files = docs.filterNot { it.isMedia }
            if (media.isNotEmpty()) {
                SectionLabel(string(Res.string.docs_media))
                MediaGrid(media, onOpen = { onOpen(it.name) })
            }
            if (files.isNotEmpty()) {
                if (media.isNotEmpty()) SectionLabel(string(Res.string.docs_files))
                MenuGroup {
                    files.forEach { d ->
                        row {
                            val day = string(Res.string.docs_size_day, fileSize(d.bytes), shortDay(d.modified))
                            MenuRow(
                                if (d.kind == "audio") KeloIcons.Music else KeloIcons.File, d.name, onClick = { onOpen(d.name) },
                                value = d.duration?.takeIf { d.kind == "audio" }?.let { string(Res.string.docs_size_day, clipLength(it), day) } ?: day,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One file: a photo on the whole screen, a video or sound file playing, or a document's text. Save the original, or delete. */
@Composable
private fun FileViewer(u: Upload, vm: DocumentsViewModel, ui: DocumentsUiState, onClose: () -> Unit, onDeleted: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val savedMsg = when (ui.saveNote) {
        SaveNote.SAVING -> string(Res.string.docs_saving)
        SaveNote.SAVED -> string(Res.string.docs_saved)
        SaveNote.FAILED -> string(Res.string.docs_save_failed)
        null -> ""
    }
    val err = ui.viewerError
    val note = listOf(savedMsg, err).filter { it.isNotEmpty() }.joinToString(" · ")
    val save = { vm.save(u) }

    when (u.kind) {
        "image" -> PhotoPage(u, onClose, onSave = save, onDelete = { confirm = true }, note = note)
        "video", "audio" -> PlayerPage(u, onClose, onSave = save, onDelete = { confirm = true }, note = note)
        else -> DocumentText(u.name, vm, ui, onClose, onSave = save, onDelete = { confirm = true }, savedMsg = savedMsg, err = err)
    }
    if (confirm) {
        ConfirmDialog(
            text = string(if (u.kind == "document") Res.string.docs_delete_confirm else Res.string.docs_delete_media_confirm),
            yes = string(Res.string.delete),
            onDismiss = { confirm = false },
            onYes = { vm.delete(u, onDeleted) },
        )
    }
}

/** A document: its text as the assistant reads it, the original to save, and delete. */
@Composable
private fun DocumentText(name: String, vm: DocumentsViewModel, ui: DocumentsUiState, onClose: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit, savedMsg: String, err: String) {
    LaunchedEffect(name) { vm.loadText(name) }
    val text = ui.text.takeIf { ui.textFor == name }
    val readErr = ui.textError.takeIf { ui.textFor == name }.orEmpty()
    SubPage(name, onClose) {
        MyCard {
            Row(Modifier.fillMaxWidth()) {
                LinkButton(string(Res.string.docs_download), onClick = onSave)
                Row(Modifier.weight(1f)) {}
                LinkButton(string(Res.string.delete), onClick = onDelete, danger = true)
            }
            if (savedMsg.isNotEmpty()) MutedText(savedMsg, small = true)
            val t = text
            when {
                err.isNotEmpty() -> ErrorText(err, small = true)
                readErr.isNotEmpty() -> ErrorText(readErr, small = true)
                t == null -> MutedText(string(Res.string.docs_reading), small = true)
                else -> MarkdownText(t)
            }
        }
    }
}
