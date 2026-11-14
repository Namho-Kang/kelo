package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.res.Res
import chat.kelo.app.res.storage_delete_failed
import chat.kelo.app.res.storage_load_failed
import chat.kelo.app.res.storage_owner_only
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** [failed]: why the list could not be read (null = no failure); [error]: why the delete did not work. */
data class CleanupUiState(
    val list: CleanupList? = null,
    val failed: StringResource? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val result: CleanupResult? = null,
    val error: StringResource? = null,
) {
    val ownerOnly: Boolean get() = failed == Res.string.storage_owner_only
}

/** 마이 → Kelo 서버 자원 → 정리하기: what can safely be removed. Nothing is chosen at first; only items on the server's current list are deleted. */
class CleanupViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(CleanupUiState())
    val state: StateFlow<CleanupUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(failed = null) }
        viewModelScope.launch {
            try {
                val l = StorageRules.list(sessions.requireApi())
                _state.update { it.copy(list = l, selected = StorageRules.keep(it.selected, l)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failed = if (e is ApiError && e.status == 403) Res.string.storage_owner_only else Res.string.storage_load_failed) }
            }
        }
    }

    fun toggle(id: String) = _state.update { it.copy(selected = StorageRules.toggle(it.selected, id)) }
    fun toggleGroup(g: CleanupGroup) = _state.update { it.copy(selected = StorageRules.toggleGroup(it.selected, g)) }

    fun delete() {
        val ids = _state.value.selected.toList()
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val r = StorageRules.remove(sessions.requireApi(), ids)
                _state.update { s ->
                    val fresh = CleanupList(r.groups, r.total, s.list?.olderThanDays ?: 14)
                    s.copy(result = r, list = fresh, selected = StorageRules.keep(s.selected - r.removed.toSet(), fresh))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = Res.string.storage_delete_failed) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
