package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import chat.kelo.app.res.Res
import chat.kelo.app.res.files_core_reset_hint
import chat.kelo.app.res.files_deleted
import chat.kelo.app.res.files_folder_bad
import chat.kelo.app.res.files_full
import chat.kelo.app.res.files_load_failed
import chat.kelo.app.res.files_reset_done
import chat.kelo.app.res.files_restored
import chat.kelo.app.res.files_save_failed
import chat.kelo.app.res.files_saved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

/** [entries]: null = loading; [failed]: the list could not be read; [open]: the file shown; [draft]: its text while editing (null = not
 *  editing); [trash]: null = not loaded. [note]: the last message (saved, deleted, restored, failed …) for a snackbar-like line. */
data class KeloFilesUiState(
    val entries: List<HomeEntry>? = null,
    val failed: Boolean = false,
    val open: HomeFile? = null,
    val draft: String? = null,
    val busy: Boolean = false,
    val trash: List<HomeTrashed>? = null,
    val note: StringResource? = null,
)

/** 마이 → Kelo의 파일: browse, read, edit and delete the files Kelo keeps for itself; a 30-day trash (api/files.py FILE MANAGER). */
class KeloFilesViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(KeloFilesUiState())
    val state: StateFlow<KeloFilesUiState> = _state.asStateFlow()

    private fun api(p: String) = KeloFileRules.apiPath(p) { KeloApi.segment(it) }
    private fun note(n: StringResource?) = _state.update { it.copy(note = n) }

    /** Runs [block] on the view model's scope; an ApiError goes to [onError] (cancellation is rethrown). */
    private fun run(onError: (ApiError) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: ApiError) { onError(e) }
        }
    }

    fun load() {
        run({ _state.update { s -> s.copy(failed = true) } }) {
            val list = sessions.requireApi().get<List<HomeEntry>>("/api/files?folders=1")
            _state.update { it.copy(entries = list, failed = false) }
        }
    }

    fun openFile(path: String) {
        _state.update { it.copy(open = null, draft = null) }
        run({ note(Res.string.files_load_failed) }) {
            val f = sessions.requireApi().get<HomeFile>("/api/files/${api(path)}")
            _state.update { it.copy(open = f, draft = null) }
        }
    }

    fun edit() = _state.update { it.copy(draft = it.open?.content) }
    fun setDraft(text: String) = _state.update { if (it.draft == null) it else it.copy(draft = text) }
    fun cancelEdit() = _state.update { it.copy(draft = null) }

    fun save() {
        val s = _state.value
        val file = s.open ?: return
        val text = s.draft ?: return
        _state.update { it.copy(busy = true) }
        run({ e -> _state.update { it.copy(busy = false, note = if (e.status == 507) Res.string.files_full else Res.string.files_save_failed) } }) {
            sessions.requireApi().put<JsonElement>("/api/files/${api(file.path)}", buildJsonObject { put("content", text) })
            _state.update { it.copy(busy = false, open = file.copy(content = text), draft = null, note = Res.string.files_saved) }
            load()
        }
    }

    fun delete(path: String, then: () -> Unit) {
        _state.update { it.copy(busy = true) }
        run({ e -> _state.update { it.copy(busy = false, note = if (e.status == 409) Res.string.files_core_reset_hint else Res.string.files_save_failed) } }) {
            sessions.requireApi().delete<JsonElement>("/api/files/${api(path)}")
            _state.update { it.copy(busy = false, note = Res.string.files_deleted) }
            load()
            then()
        }
    }

    fun reset(path: String) {
        _state.update { it.copy(busy = true) }
        run({ _state.update { it.copy(busy = false, note = Res.string.files_save_failed) } }) {
            sessions.requireApi().post<JsonElement>("/api/files/${api(path)}/reset")
            _state.update { it.copy(busy = false, note = Res.string.files_reset_done) }
            openFile(path)
            load()
        }
    }

    fun makeFolder(parent: String, rawName: String) {
        val name = KeloFileRules.folderName(rawName)
        if (name == null) { note(Res.string.files_folder_bad); return }
        val full = if (parent.isEmpty()) name else "$parent/$name"
        run({ note(Res.string.files_save_failed) }) {
            sessions.requireApi().post<JsonElement>("/api/files/${api(full)}/folder")
            load()
        }
    }

    fun loadTrash() {
        run({ note(Res.string.files_load_failed) }) {
            val t = sessions.requireApi().get<List<HomeTrashed>>("/api/home-trash")
            _state.update { it.copy(trash = t) }
        }
    }

    fun restore(id: String) {
        run({ note(Res.string.files_save_failed) }) {
            sessions.requireApi().post<JsonElement>("/api/home-trash/${KeloApi.segment(id)}/restore")
            note(Res.string.files_restored)
            loadTrash()
            load()
        }
    }

    fun purge(id: String) {
        run({ note(Res.string.files_save_failed) }) {
            sessions.requireApi().delete<JsonElement>("/api/home-trash/${KeloApi.segment(id)}")
            loadTrash()
        }
    }

    fun clearNote() = note(null)
}
