package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import chat.kelo.app.platform.FilePicker
import chat.kelo.app.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.StringResource

/** A line under an operator card that the page puts into words. */
sealed interface OperatorNote {
    data object FirebaseSaved : OperatorNote
    data object Saved : OperatorNote
    data object ReclaimedNone : OperatorNote
    data class Reclaimed(val count: Int) : OperatorNote
    data class Mailed(val email: String) : OperatorNote
    /** the server's own words */
    data class Server(val text: String) : OperatorNote
}

/** A problem under an operator card: the server's words, or one of the app's own sentences. */
sealed interface OperatorProblem {
    data class Server(val text: String) : OperatorProblem
    data class App(val res: StringResource) : OperatorProblem
}

data class FirebaseUiState(
    val config: FirebaseConfig? = null,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val note: OperatorNote? = null,
    val problem: OperatorProblem? = null,
)

data class CloudflareUiState(
    val config: CloudflareConfig? = null,
    val failed: Boolean = false,
    val form: CloudflareForm = CloudflareForm(),
    val busy: Boolean = false,
    val note: OperatorNote? = null,
    val problem: OperatorProblem? = null,
)

data class BetaUiState(
    val data: BetaRequests? = null,
    val failed: Boolean = false,
    val note: OperatorNote? = null,
    val error: String = "",
)

data class ReportsUiState(
    val data: ReceivedReports? = null,
    val failed: Boolean = false,
    val showHandled: Boolean = false,
    val error: String = "",
)

/** What 마이 → 운영자 shows: one block per card. */
data class OperatorUiState(
    val firebase: FirebaseUiState = FirebaseUiState(),
    val cloudflare: CloudflareUiState = CloudflareUiState(),
    val beta: BetaUiState = BetaUiState(),
    val reports: ReportsUiState = ReportsUiState(),
)

/**
 * 마이 → 운영자 (owner only): the push key, the free-address service, beta requests and the reports other copies sent. Secrets go in,
 * never come back out: nothing here keeps or logs a key, a token or a picked key file beyond the one call that sends it.
 */
class OperatorViewModel(private val sessions: Sessions, private val files: FilePicker) : ViewModel() {
    private val _state = MutableStateFlow(OperatorUiState())
    val state: StateFlow<OperatorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { sessions.requireApi().get<FirebaseConfig>("/api/operator/firebase") }
                .onSuccess { c -> _state.update { it.copy(firebase = it.firebase.copy(config = c)) } }
                .onFailure { _state.update { it.copy(firebase = it.firebase.copy(failed = true)) } }
        }
        viewModelScope.launch {
            runCatching { sessions.requireApi().get<CloudflareConfig>("/api/operator/cloudflare") }
                .onSuccess { c -> _state.update { it.copy(cloudflare = it.cloudflare.copy(config = c, form = OperatorRules.formOf(c))) } }
                .onFailure { _state.update { it.copy(cloudflare = it.cloudflare.copy(failed = true)) } }
        }
        viewModelScope.launch { loadBeta() }
        viewModelScope.launch { loadReports() }
    }

    private fun firebase(f: (FirebaseUiState) -> FirebaseUiState) = _state.update { it.copy(firebase = f(it.firebase)) }
    private fun cloudflare(f: (CloudflareUiState) -> CloudflareUiState) = _state.update { it.copy(cloudflare = f(it.cloudflare)) }
    private fun beta(f: (BetaUiState) -> BetaUiState) = _state.update { it.copy(beta = f(it.beta)) }
    private fun reports(f: (ReportsUiState) -> ReportsUiState) = _state.update { it.copy(reports = f(it.reports)) }

    // ---- push key (Firebase service account)

    /** The owner picks the service-account key file; it is checked, sent, and wiped from memory. */
    fun pickFirebaseKey(types: List<String>) {
        viewModelScope.launch {
            val file = files.pickFile(types) ?: return@launch
            firebase { it.copy(busy = true, problem = null, note = null) }
            val bytes = withContext(Dispatchers.IO) {
                runCatching { file.open().use { s -> s.readNBytesCompat(OperatorRules.MAX_KEY_FILE + 1) } }.getOrNull()
            }
            try {
                if (bytes == null || !OperatorRules.keyFileOk(bytes)) { firebase { it.copy(problem = OperatorProblem.App(Res.string.operator_fb_not_key)) }; return@launch }
                val out = sessions.requireApi().requestBytes("PUT", "/api/operator/firebase", bytes)
                val cfg = KeloApi.json.decodeFromJsonElement(FirebaseConfig.serializer(), out)
                firebase { it.copy(config = cfg, note = OperatorNote.FirebaseSaved) }
            } catch (e: ApiError) {
                firebase { it.copy(problem = OperatorProblem.Server(e.detail)) }   // the server's words never contain the key
            } catch (e: IllegalArgumentException) {
                firebase { it.copy(problem = OperatorProblem.App(Res.string.operator_fb_not_key)) }
            } finally {
                bytes?.fill(0)
                firebase { it.copy(busy = false) }
            }
        }
    }

    fun removeFirebase() {
        viewModelScope.launch {
            runCatching { sessions.requireApi().delete<FirebaseConfig>("/api/operator/firebase") }
                .onSuccess { c -> firebase { it.copy(config = c, note = null, problem = null) } }
                .onFailure { e -> firebase { it.copy(problem = OperatorProblem.Server((e as? ApiError)?.detail.orEmpty())) } }
        }
    }

    // ---- free-address service (Cloudflare)

    fun editCloudflare(form: CloudflareForm) = cloudflare { it.copy(form = form) }

    fun saveCloudflare() {
        cloudflare { it.copy(note = null, problem = null) }
        val form = _state.value.cloudflare.form
        val problem = OperatorRules.problem(form)
        if (problem != null) { cloudflare { it.copy(problem = OperatorProblem.App(problem)) }; return }
        cloudflare { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val out = sessions.requireApi().put<CloudflareConfig>("/api/operator/cloudflare", OperatorRules.cloudflareBody(form))
                cloudflare { it.copy(config = out, form = OperatorRules.formOf(out), note = OperatorNote.Saved) }   // the token field empties: it is never shown again
            } catch (e: ApiError) {
                cloudflare { it.copy(problem = OperatorProblem.Server(e.detail)) }   // Cloudflare's own message, never the token
            } finally {
                cloudflare { it.copy(busy = false) }
            }
        }
    }

    fun reclaimCloudflare() {
        cloudflare { it.copy(note = null, problem = null, busy = true) }
        viewModelScope.launch {
            try {
                val r = sessions.requireApi().post<Reclaimed>("/api/operator/cloudflare/reclaim")
                cloudflare { it.copy(note = if (r.released.isEmpty()) OperatorNote.ReclaimedNone else OperatorNote.Reclaimed(r.released.size)) }
                runCatching { sessions.requireApi().get<CloudflareConfig>("/api/operator/cloudflare") }.onSuccess { c -> cloudflare { it.copy(config = c) } }
            } catch (e: ApiError) {
                cloudflare { it.copy(problem = OperatorProblem.Server(e.detail)) }
            } finally {
                cloudflare { it.copy(busy = false) }
            }
        }
    }

    fun turnOffCloudflare() {
        viewModelScope.launch {
            runCatching { sessions.requireApi().put<CloudflareConfig>("/api/operator/cloudflare", OperatorRules.cloudflareOff()) }
                .onSuccess { c -> cloudflare { it.copy(config = c, form = OperatorRules.formOf(c)) } }
                .onFailure { e -> cloudflare { it.copy(problem = OperatorProblem.Server((e as? ApiError)?.detail.orEmpty())) } }
        }
    }

    // ---- beta requests

    private suspend fun loadBeta() {
        runCatching { sessions.requireApi().get<BetaRequests>("/api/operator/beta") }
            .onSuccess { d -> beta { it.copy(data = d, failed = false) } }.onFailure { beta { it.copy(failed = true) } }
    }

    fun answerBeta(r: BetaRequestRow, action: String, mail: Boolean) {
        beta { it.copy(error = "", note = null) }
        viewModelScope.launch {
            try {
                val a = sessions.requireApi().post<BetaAnswer>("/api/operator/beta/${r.id}", buildJsonObject { put("action", action); put("email", mail) })
                beta { it.copy(note = if (a.mailed) OperatorNote.Mailed(r.email) else OperatorNote.Server(a.mailError)) }
                loadBeta()
            } catch (e: ApiError) {
                beta { it.copy(error = e.detail) }
            }
        }
    }

    fun deleteBeta(id: Long) {
        viewModelScope.launch {
            try {
                sessions.requireApi().request("DELETE", "/api/operator/beta/$id")
                loadBeta()
            } catch (e: ApiError) {
                beta { it.copy(error = e.detail) }
            }
        }
    }

    // ---- received reports

    private suspend fun loadReports() {
        runCatching { sessions.requireApi().get<ReceivedReports>(OperatorReportRules.path(_state.value.reports.showHandled)) }
            .onSuccess { d -> reports { it.copy(data = d, failed = false) } }.onFailure { reports { it.copy(failed = true) } }
    }

    fun toggleHandledShown() {
        reports { it.copy(showHandled = !it.showHandled) }
        viewModelScope.launch { loadReports() }
    }

    fun markReport(id: Long, handled: Boolean) {
        viewModelScope.launch {
            try {
                sessions.requireApi().request("POST", "/api/operator/reports/$id", buildJsonObject { put("handled", handled) })
                loadReports()
            } catch (e: ApiError) {
                reports { it.copy(error = e.detail) }
            }
        }
    }

    fun deleteReport(id: Long) {
        viewModelScope.launch {
            try {
                sessions.requireApi().request("DELETE", "/api/operator/reports/$id")
                loadReports()
            } catch (e: ApiError) {
                reports { it.copy(error = e.detail) }
            }
        }
    }
}

/** InputStream.readNBytes (Java 11) for API 26: at most [max] bytes. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    var total = 0
    while (total < max) {
        val n = read(buf, 0, minOf(buf.size, max - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    buf.fill(0)
    return out.toByteArray()
}
