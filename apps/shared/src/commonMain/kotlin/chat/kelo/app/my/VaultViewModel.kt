package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.agent.ScheduleGrantCalls
import chat.kelo.app.chat.vault.SecretCalls
import chat.kelo.app.chat.vault.SecretRequest
import chat.kelo.app.chat.vault.VaultCalls
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the keychain pages show. [info] null = still loading. The messages belong to the page that is open; [clearMessages] resets them. */
data class VaultUiState(
    val info: VaultState? = null,
    val providers: List<ProviderDetail> = emptyList(),
    /** keys, logins and cards the agent asked for that have no value yet */
    val waiting: List<SecretRequest> = emptyList(),
    /** the vault's log; null = loading */
    val log: List<VaultLogRow>? = null,
    /** an error line on a login or key page */
    val itemError: String = "",
    val addLoginError: String = "",
    /** the AI key page's line: [aiSaved] true = the "saved" sentence, else the server's words */
    val aiMessage: String = "",
    val aiSaved: Boolean = false,
)

/** 마이 → 키체인: every password and key the assistant may use. Values are added or replaced here, never read. */
class VaultViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init { load(); loadProviders() }

    fun load() {
        viewModelScope.launch {
            runCatching { sessions.requireApi().get<VaultState>("/api/vault") }.onSuccess { v -> _state.update { it.copy(info = v) } }
            loadWaiting()
        }
    }

    fun loadProviders() {
        viewModelScope.launch {
            runCatching { sessions.requireApi().get<List<ProviderDetail>>("/api/providers") }.onSuccess { p -> _state.update { it.copy(providers = p) } }
        }
    }

    private suspend fun loadWaiting() {
        runCatching { SecretCalls.list(sessions.requireApi()) }.onSuccess { l -> _state.update { it.copy(waiting = l) } }
    }

    fun clearMessages() = _state.update { it.copy(itemError = "", addLoginError = "", aiMessage = "", aiSaved = false) }

    fun toggleLock() {
        val locked = _state.value.info?.locked ?: return
        viewModelScope.launch {
            runCatching { sessions.requireApi().put<JsonElement>("/api/vault/lock", buildJsonObject { put("locked", !locked) }) }
            load()
        }
    }

    fun setMailCodes(on: Boolean) {
        viewModelScope.launch {
            runCatching { sessions.requireApi().put<JsonElement>("/api/vault/settings", buildJsonObject { put("mail_codes_auto", on) }) }
            load()
        }
    }

    // ---- waiting entries

    /** A waiting entry was answered or is gone: it leaves the list. */
    fun waitingDone(id: String) { _state.update { s -> s.copy(waiting = s.waiting.filterNot { it.id == id }) }; load() }

    /** 삭제: cancels the request (the agent is told and does not ask again). */
    fun cancelWaiting(r: SecretRequest) {
        _state.update { s -> s.copy(waiting = s.waiting.filterNot { it.id == r.id }) }
        viewModelScope.launch { runCatching { SecretCalls.cancel(sessions.requireApi(), r.id) }; load() }
    }

    // ---- one login or key

    /** Leaving a login or key that was started but never filled in: nothing stays behind in the vault. */
    fun leaveItem(name: String) {
        val v = _state.value.info
        val item = v?.let { (it.items + it.pending).find { i -> i.name == name } }
        if (item != null && !item.hasSecret) viewModelScope.launch { runCatching { VaultCalls.cancelDraft(sessions.requireApi(), item.name) } }
        load()
    }

    fun patchItem(name: String, build: JsonObjectBuilder.() -> Unit) {
        viewModelScope.launch {
            try {
                sessions.requireApi().patch<JsonElement>("/api/vault/${KeloApi.segment(name)}", buildJsonObject(build))
                load()
            } catch (e: ApiError) {
                _state.update { it.copy(itemError = e.detail) }
            }
        }
    }

    fun revokeGrant(g: chat.kelo.app.agent.ScheduleGrant) {
        viewModelScope.launch {
            try { ScheduleGrantCalls.revoke(sessions.requireApi(), g); load() } catch (e: ApiError) { _state.update { it.copy(itemError = e.detail) } }
        }
    }

    /** Removes a login or key; [onGone] runs when it is removed. */
    fun deleteItem(name: String, onGone: () -> Unit) {
        viewModelScope.launch {
            try {
                sessions.requireApi().delete<JsonElement>("/api/credentials/${KeloApi.segment(name)}")
                load(); onGone()
            } catch (e: ApiError) {
                _state.update { it.copy(itemError = e.detail) }
            }
        }
    }

    // ---- a new login

    fun addLogin(site: String, label: String, onDone: (String) -> Unit) {
        _state.update { it.copy(addLoginError = "") }
        viewModelScope.launch {
            try {
                val r = sessions.requireApi().post<NewLogin>("/api/vault/logins", buildJsonObject { put("site", site.trim()); put("label", label.trim()) })
                load(); onDone(r.name)
            } catch (e: ApiError) {
                _state.update { it.copy(addLoginError = e.detail) }
            }
        }
    }

    // ---- the key of an AI service

    fun saveAiKey(provider: String, key: String, onSaved: () -> Unit) {
        _state.update { it.copy(aiMessage = "", aiSaved = false) }
        viewModelScope.launch {
            try {
                sessions.requireApi().put<JsonElement>("/api/providers/${KeloApi.segment(provider)}/key", buildJsonObject { put("api_key", key.trim()) })
                _state.update { it.copy(aiSaved = true) }
                loadProviders(); onSaved()
            } catch (e: ApiError) {
                _state.update { it.copy(aiMessage = e.detail) }
            }
        }
    }

    fun deleteAiKey(provider: String, onGone: () -> Unit) {
        viewModelScope.launch {
            runCatching { sessions.requireApi().delete<JsonElement>("/api/providers/${KeloApi.segment(provider)}/key") }
            loadProviders(); onGone()
        }
    }

    // ---- the log

    fun loadLog() {
        viewModelScope.launch {
            val rows = try { sessions.requireApi().get<List<VaultLogRow>>("/api/vault/log?limit=200") } catch (_: ApiError) { emptyList() }
            _state.update { it.copy(log = rows) }
        }
    }
}
