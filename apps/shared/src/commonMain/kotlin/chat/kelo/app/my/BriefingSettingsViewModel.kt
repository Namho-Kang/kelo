package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.LocationAccess
import chat.kelo.app.platform.PhoneLocation
import chat.kelo.app.platform.SettingsPage
import chat.kelo.app.platform.SystemSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the morning briefing's settings and the "what it reads" list show. [settings] null with [missing] false = still loading;
 * [missing] = the server has no briefing (404). [granted] = the phone's location may be used (true where the device has no such
 * permission); [servicesOn] = the phone's own location switch is on (true where the device has none); [sent]: the "try it now" button (null = ready, false = sending, true = sent).
 */
data class BriefingUiState(
    val settings: BriefingSettings? = null,
    val missing: Boolean = false,
    val granted: Boolean = true,
    val servicesOn: Boolean = true,
    val sent: Boolean? = null,
    val sources: BriefingSources? = null,
    val checkingSources: Boolean = false,
)

/** 마이 → 알림 → 아침 브리핑 and "what it reads": the server calls and the phone's location. */
class BriefingSettingsViewModel(
    private val sessions: Sessions,
    private val location: PhoneLocation?,
    private val settingsPages: SystemSettings,
) : ViewModel() {
    private val _state = MutableStateFlow(BriefingUiState(granted = location?.granted() ?: true, servicesOn = location?.servicesOn() ?: true))
    val state: StateFlow<BriefingUiState> = _state.asStateFlow()
    private val _notices = Channel<String>(Channel.BUFFERED)
    /** error lines to show as a toast */
    val notices: Flow<String> = _notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            val api = runCatching { sessions.requireApi() }.getOrNull() ?: return@launch
            try { _state.update { it.copy(settings = api.get("/api/briefing")) } } catch (e: ApiError) { if (e.status == 404) _state.update { it.copy(missing = true) } }
        }
        viewModelScope.launch {
            try { _state.update { it.copy(sources = sessions.requireApi().get<BriefingSources>("/api/briefing/sources")) } } catch (_: ApiError) { }
        }
    }

    private fun call(block: suspend () -> BriefingSettings) {
        viewModelScope.launch { try { setSettings(block()) } catch (e: ApiError) { _notices.trySend(e.detail) } }
    }

    private fun setSettings(b: BriefingSettings) = _state.update { it.copy(settings = b) }

    fun put(body: JsonObject) = call { sessions.requireApi().put("/api/briefing", body) }

    fun deleteKey(id: String) = call { sessions.requireApi().delete("/api/briefing/keys/$id") }

    /** The owner's own key for a weather service: checked by the server with one real request; [onDone] gets the error line or null. */
    fun saveKey(id: String, key: String, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            try {
                setSettings(sessions.requireApi().post("/api/briefing/keys/$id", buildJsonObject { put("key", key.trim()) }))
                onDone(null)
            } catch (e: ApiError) { onDone(e.detail) }
        }
    }

    fun tryNow() {
        _state.update { it.copy(sent = false) }
        viewModelScope.launch {
            try { sessions.requireApi().post<JsonElement>("/api/briefing/run"); _state.update { it.copy(sent = true) } }
            catch (e: ApiError) { _state.update { it.copy(sent = null) }; _notices.trySend(e.detail) }
        }
    }

    /** The phone's location (precise or approximate, as the owner picks): sent now, then whenever the app is opened. */
    private fun sendPlace() = call {
        val api = sessions.requireApi()
        location?.send(api, force = true)
        api.get("/api/briefing")
    }

    /** The weather needs to know where the phone is: ask (the system asks at most twice; after that only the app's settings can allow it). */
    fun allowPlace() {
        val loc = location ?: return
        viewModelScope.launch {
            if (!loc.servicesOn()) { _state.update { it.copy(servicesOn = false) }; settingsPages.open(SettingsPage.LOCATION); return@launch }
            if (loc.granted()) { _state.update { it.copy(granted = true) }; sendPlace(); return@launch }
            when (loc.request()) {
                LocationAccess.GRANTED -> { _state.update { it.copy(granted = true) }; sendPlace() }
                LocationAccess.BLOCKED -> settingsPages.open(SettingsPage.APP_DETAILS)
                LocationAccess.DENIED -> _state.update { it.copy(granted = loc.granted()) }
            }
        }
    }

    /** Back from the app's settings: location may be allowed now. */
    fun recheckPlace() {
        val loc = location ?: return
        val now = loc.granted()
        if (now && !_state.value.granted) sendPlace()
        _state.update { it.copy(granted = now, servicesOn = loc.servicesOn()) }
    }

    fun flipSource(kind: String, id: String, on: Boolean) {
        val s = _state.value.sources ?: return
        _state.update { it.copy(sources = BriefingSourcesRules.flipped(s, kind, id, on)) }
        viewModelScope.launch {
            try { _state.update { it.copy(sources = sessions.requireApi().put("/api/briefing/sources", BriefingSourcesRules.toggleBody(kind, id, on))) } }
            catch (e: ApiError) { _notices.trySend(e.detail) }
        }
    }

    fun checkSources() {
        _state.update { it.copy(checkingSources = true) }
        viewModelScope.launch {
            try { _state.update { it.copy(sources = sessions.requireApi().post<BriefingSources>("/api/briefing/sources/check")) } }
            catch (e: ApiError) { _notices.trySend(e.detail) } finally { _state.update { it.copy(checkingSources = false) } }
        }
    }
}
