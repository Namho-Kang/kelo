package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Device
import chat.kelo.app.core.DeviceList
import chat.kelo.app.core.PairingLinks
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.whileObserved
import chat.kelo.app.core.e2e.ServerKey
import chat.kelo.app.res.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

/** A pairing in progress: the link and when it stops working. */
data class Pairing(val link: String, val untilMillis: Long)

/** What the devices page shows. [error] is the server's words or [noLinkError] (the page says which). */
data class DevicesUiState(
    val devices: List<Device> = emptyList(),
    val current: Long? = null,
    val pairing: Pairing? = null,
    val secondsLeft: Long = 0,
    val error: String = "",
    val noLinkError: Boolean = false,
    val copied: Boolean = false,
    /** the result of checking a scanned key against the pinned one */
    val keyNote: StringResource? = null,
)

/** 마이 → 내 기기: every phone or computer that can open this assistant; add one with a pairing link, remove one, verify the server key. */
class DevicesViewModel(private val sessions: Sessions, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()
    private var countdown: Job? = null

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        runCatching { sessions.requireApi().get<DeviceList>("/api/devices") }.onSuccess { l -> _state.update { it.copy(devices = l.devices, current = l.current) } }
    }

    fun add() {
        _state.update { it.copy(error = "", noLinkError = false, copied = false) }
        viewModelScope.launch {
            try {
                val api = sessions.requireApi()
                val p = api.post<PairStart>("/api/devices/pair", buildJsonObject { put("origin", api.baseUrl) })
                val link = p.link
                if (link.isNullOrEmpty()) { _state.update { it.copy(noLinkError = true) }; return@launch }
                _state.update { it.copy(pairing = Pairing(link, now() + p.expiresIn * 1000L), secondsLeft = p.expiresIn.toLong()) }
                startCountdown()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.detail) }
            }
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = _state.whileObserved(viewModelScope) {
            val p = _state.value.pairing ?: return@whileObserved
            while (true) {
                val s = maxOf(0L, Math.round((p.untilMillis - now()) / 1000.0))
                _state.update { it.copy(secondsLeft = s) }
                if (s == 0L) { _state.update { it.copy(pairing = null) }; return@whileObserved }
                delay(2000)
                load()   // the new device shows up in the list as soon as it joins
            }
        }
    }

    fun closePairing() { countdown?.cancel(); _state.update { it.copy(pairing = null) } }
    fun copied() = _state.update { it.copy(copied = true) }

    /** Removes a device; [onSignedOut] runs when it was this one. */
    fun remove(d: Device, onSignedOut: () -> Unit) {
        viewModelScope.launch {
            try {
                sessions.requireApi().delete<JsonElement>("/api/devices/${d.id}")
                if (d.id == _state.value.current) onSignedOut() else load()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.detail) }
            }
        }
    }

    fun clearKeyNote() = _state.update { it.copy(keyNote = null) }

    /** A pairing QR from the server machine: the same key as the pinned one marks it verified (the code is not used). */
    fun verifyScanned(raw: String) {
        val link = PairingLinks.parse(raw)
        val k = link?.serverKey?.let { runCatching { ServerKey.fromBase64Url(it) }.getOrNull() }
        val note = when {
            link?.serverKey == null -> Res.string.devices_e2e_verify_no_key
            k != null && sessions.verifyWith(k) -> Res.string.devices_e2e_verify_done
            else -> Res.string.devices_e2e_verify_mismatch
        }
        _state.update { it.copy(keyNote = note) }
    }
}
