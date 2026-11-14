package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.chat.ProviderInfo
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Sessions
import chat.kelo.app.res.*
import chat.kelo.app.wallet.PaymentPin
import chat.kelo.app.wallet.PinCalls
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

/** 마이 → 프로필: my name and the assistant's name (PUT /api/profile). */
class ProfileViewModel(private val sessions: Sessions) : ViewModel() {
    data class State(val saved: Boolean = false, val error: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** An edit in the form: the "saved" check goes. */
    fun edited() = _state.update { it.copy(saved = false) }

    fun save(user: String, agent: String, onSaved: (Profile) -> Unit) {
        _state.update { it.copy(error = "") }
        viewModelScope.launch {
            try {
                onSaved(sessions.requireApi().put<Profile>("/api/profile", buildJsonObject {
                    put("user_name", user.trim()); put("agent_name", agent.trim())
                }))
                _state.update { it.copy(saved = true) }
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.detail) }
            }
        }
    }
}

/** 마이 → 사용량: the provider names for the limits' labels. */
class UsageViewModel(private val sessions: Sessions) : ViewModel() {
    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val providers: StateFlow<List<ProviderInfo>> = _providers.asStateFlow()
    private val _adminNote = MutableStateFlow<StringResource?>(null)
    val adminNote: StateFlow<StringResource?> = _adminNote.asStateFlow()
    private val _adminBusy = MutableStateFlow(false)
    val adminBusy: StateFlow<Boolean> = _adminBusy.asStateFlow()

    init { reloadProviders() }

    private fun reloadProviders() {
        viewModelScope.launch { runCatching { sessions.requireApi().get<List<ProviderInfo>>("/api/providers") }.onSuccess { _providers.value = it } }
    }

    /** Saves an admin key; [then] refreshes the usage numbers. */
    fun saveAdminKey(provider: String, key: String, then: () -> Unit) {
        _adminBusy.value = true
        _adminNote.value = null
        viewModelScope.launch {
            try {
                sessions.requireApi().put<JsonElement>("/api/providers/${KeloApi.segment(provider)}/admin-key", buildJsonObject { put("api_key", key.trim()) })
                _adminNote.value = Res.string.admin_key_saved
                reloadProviders()
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _adminNote.value = when (e.code) {
                    "key_refused" -> Res.string.admin_key_refused
                    "keyring_off" -> Res.string.admin_key_keyring_off
                    else -> Res.string.admin_key_failed
                }
            } finally {
                _adminBusy.value = false
            }
        }
    }

    fun removeAdminKey(provider: String, then: () -> Unit) {
        viewModelScope.launch {
            runCatching { sessions.requireApi().delete<JsonElement>("/api/providers/${KeloApi.segment(provider)}/admin-key") }
            reloadProviders()
            then()
        }
    }

    fun clearAdminNote() { _adminNote.value = null }
}

/** The payment PINs saved for sites (name, site, digits; never the PIN), in the keychain page. */
class PaymentPinsViewModel(private val sessions: Sessions) : ViewModel() {
    private val _pins = MutableStateFlow<List<PaymentPin>>(emptyList())
    val pins: StateFlow<List<PaymentPin>> = _pins.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch { runCatching { PinCalls(sessions.requireApi()).list() }.onSuccess { _pins.value = it } }
    }

    fun delete(id: String) {
        viewModelScope.launch { runCatching { PinCalls(sessions.requireApi()).delete(id) }; reload() }
    }
}
