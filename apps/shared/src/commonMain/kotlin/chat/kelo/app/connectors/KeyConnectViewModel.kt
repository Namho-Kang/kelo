package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.FilePicker
import chat.kelo.app.platform.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** [form]: what the service asks for (null until the first answer). */
data class KeyConnectState(val form: KeyForm? = null, val busy: Boolean = false, val error: ConnectError? = null)

/** A key or token made on the service (or a Zoom app, or a Google service account). The server tries it once before saving it. */
class KeyConnectViewModel(
    private val sessions: Sessions,
    private val via: String,
    private val files: FilePicker,
) : ViewModel() {
    private val _state = MutableStateFlow(KeyConnectState())
    val state: StateFlow<KeyConnectState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        runCatching { ConnectorCalls.keyForm(sessions.requireApi(), via) }.onSuccess { f -> _state.update { it.copy(form = f) } }
    }

    /** Checks and saves the key; [onSaved] once the server has it (the fields then empty). */
    fun save(values: Map<String, String>, onSaved: () -> Unit) {
        val form = _state.value.form ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                ConnectorCalls.saveKey(sessions.requireApi(), form.id, values)
                onSaved()
                reload()
            } catch (e: ApiError) {
                _state.update { it.copy(error = e.said()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun disconnect(onChanged: () -> Unit) {
        val form = _state.value.form ?: return
        viewModelScope.launch {
            sessions.disconnectQuietly(form.item)
            reload()
            onChanged()
        }
    }

    /** The Google service account's JSON file, picked with the system's file picker; [onText] gets its text. */
    fun pickKeyFile(onText: (String) -> Unit) {
        viewModelScope.launch {
            val file = files.pickFile(listOf("application/json", "text/*", "application/octet-stream")) ?: return@launch
            val text = readSmallText(file)
            if (text == null) _state.update { it.copy(error = ConnectError.FileUnreadable) } else onText(text)
        }
    }

    private companion object {
        const val MAX_KEY_FILE = 64 * 1024

        /** The text of a picked file, or null when it cannot be read or is too large to be a key file. */
        suspend fun readSmallText(file: PickedFile): String? = withContext(Dispatchers.IO) {
            runCatching {
                file.open().use { input ->
                    val bytes = input.readUpTo(MAX_KEY_FILE + 1)
                    if (bytes.size > MAX_KEY_FILE) null else bytes.toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }

        fun InputStream.readUpTo(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
