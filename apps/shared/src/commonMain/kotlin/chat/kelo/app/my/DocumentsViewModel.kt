package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.chat.AttachmentTooLarge
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.FilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** What the upload last said. */
sealed interface DocsNote {
    data object Uploaded : DocsNote
    /** More than [limit] bytes: what this server takes on this route */
    data class TooLarge(val limit: Long) : DocsNote
    data object ReadFailed : DocsNote
    data class Server(val text: String) : DocsNote
}

/** How saving the original of a file went. */
enum class SaveNote { SAVING, SAVED, FAILED }

/** Upload progress: bytes sent of the total (-1 unknown). */
data class UploadProgress(val sent: Long = 0, val total: Long = -1, val fraction: Float = 0f)

data class DocumentsUiState(
    /** null = loading */
    val docs: List<Upload>? = null,
    val note: DocsNote? = null,
    val uploading: Boolean = false,
    val progress: UploadProgress = UploadProgress(),
    /** the open file's page: the save line and the error line */
    val saveNote: SaveNote? = null,
    val viewerError: String = "",
    /** a document's text, for [textFor] */
    val textFor: String = "",
    val text: String? = null,
    val textError: String = "",
)

/** 마이 → 문서: the files the user uploaded; upload (progress, cancel), save the original, delete, read a document's text. */
class DocumentsViewModel(private val sessions: Sessions, private val files: FilePicker) : ViewModel(), DocumentMediaSource {
    override fun cachedThumb(u: Upload, size: Int) = Thumbs.cached("${u.name}|$size|${u.version()}")
    override suspend fun thumb(u: Upload, size: Int) = runCatching { Thumbs.load(sessions.requireApi(), u.name, size, u.version()) }.getOrNull()
    override fun api() = sessions.requireApi()

    private val _state = MutableStateFlow(DocumentsUiState())
    val state: StateFlow<DocumentsUiState> = _state.asStateFlow()
    private var upload: Job? = null

    init { load() }

    fun load() {
        viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        val list = try { sessions.requireApi().get<List<Upload>>("/api/uploads") } catch (_: ApiError) { emptyList() }
        _state.update { it.copy(docs = list) }
    }

    fun upload(types: List<String>) {
        viewModelScope.launch {
            val p = files.pickFile(types) ?: return@launch
            _state.update { it.copy(note = null, progress = UploadProgress()) }
            upload = launch {
                _state.update { it.copy(uploading = true) }
                try {
                    val name = MediaKind.withSuffix(p.name, p.type.ifEmpty { null })
                    // the same uploader as the chat's tray: one request, or in parts where the route has a limit; refuses over what it takes
                    sessions.uploader().upload(name, p.size, p.type.ifEmpty { null }, p.open) { sent, total ->
                        val f = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
                        val shown = _state.value.progress
                        if (sent == total || f - shown.fraction >= 0.005f || sent - shown.sent >= 1_000_000) {
                            _state.update { it.copy(progress = UploadProgress(sent, total, f)) }
                        }
                    }
                    loadNow()
                    _state.update { it.copy(note = DocsNote.Uploaded) }
                } catch (e: AttachmentTooLarge) {
                    _state.update { it.copy(note = DocsNote.TooLarge(e.limit)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    _state.update { it.copy(note = DocsNote.ReadFailed) }
                } catch (e: ApiError) {
                    _state.update { it.copy(note = DocsNote.Server(e.detail)) }
                } finally {
                    _state.update { it.copy(uploading = false) }
                    upload = null
                }
            }
        }
    }

    fun cancelUpload() { upload?.cancel(); upload = null; _state.update { it.copy(uploading = false) } }

    // ---- one file

    /** Another file was opened (or the viewer closed): its save and error lines start empty. */
    fun resetViewer() = _state.update { it.copy(saveNote = null, viewerError = "") }

    /** The original: the owner picks where it goes, then it is streamed with the token straight into that file. */
    fun save(u: Upload) {
        viewModelScope.launch {
            val target = files.saveFile(u.name, u.type.ifEmpty { "application/octet-stream" }) ?: return@launch
            _state.update { it.copy(saveNote = SaveNote.SAVING) }
            try {
                withContext(Dispatchers.IO) {
                    target.open().use { out -> sessions.requireApi().downloadTo("/api/uploads/${KeloApi.segment(u.name)}/file", out) }
                }
                _state.update { it.copy(saveNote = SaveNote.SAVED) }
            } catch (e: ApiError) {
                _state.update { it.copy(saveNote = null, viewerError = e.detail) }
            } catch (_: IOException) {
                _state.update { it.copy(saveNote = SaveNote.FAILED) }
            }
        }
    }

    fun delete(u: Upload, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                sessions.requireApi().delete<JsonElement>("/api/uploads/${KeloApi.segment(u.name)}")
                onDeleted()
                loadNow()
            } catch (e: ApiError) {
                _state.update { it.copy(viewerError = e.detail) }
            }
        }
    }

    fun loadText(name: String) {
        _state.update { it.copy(textFor = name, text = null, textError = "") }
        viewModelScope.launch {
            try {
                val t = sessions.requireApi().get<UploadText>("/api/uploads/${KeloApi.segment(name)}/text").text
                _state.update { if (it.textFor == name) it.copy(text = t) else it }
            } catch (e: ApiError) {
                _state.update { if (it.textFor == name) it.copy(textError = e.detail) else it }
            }
        }
    }
}
