package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.connectors.OperatorSettings
import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import org.jetbrains.compose.resources.StringResource
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.ui.components.Card
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GET /api/operator/firebase: the operator's push key, never the key itself. */
@Serializable
data class FirebaseConfig(
    val enabled: Boolean = false,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("client_email") val clientEmail: String = "",
)

/** GET /api/operator/cloudflare: the free-address service's settings (the API token is only "stored or not"). */
@Serializable
data class CloudflareConfig(
    @SerialName("account_id") val accountId: String = "",
    @SerialName("zone_id") val zoneId: String = "",
    @SerialName("zone_name") val zoneName: String = "",
    @SerialName("has_token") val hasToken: Boolean = false,
    @SerialName("max_names") val maxNames: Int = 0,
    val used: Int = 0,
    val enabled: Boolean = false,
    @SerialName("idle_days") val idleDays: Int = 30,
)

@Serializable
data class Reclaimed(val released: List<String> = emptyList())

/** GET /api/operator/status (owner sign-ins only): whether this server is the operator's (the one other copies lean on). */
@Serializable
data class OperatorStatus(val owner: Boolean = false, val operator: Boolean = false)

/** What the Cloudflare form holds before it is sent. */
data class CloudflareForm(val token: String = "", val accountId: String = "", val zoneId: String = "", val zoneName: String = "", val maxNames: String = "")

/** The operator page's checks and request bodies, apart from the UI so they can be tested. Nothing here keeps a secret. */
object OperatorRules {
    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val DOMAIN = Regex("^([a-z0-9-]+\\.)+[a-z]{2,63}$")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{20,200}$")
    const val MAX_KEY_FILE = 20_000

    /** The Cloudflare id fields: empty (left as they are) or the 32-character id the dashboard shows. */
    fun idOk(s: String): Boolean = s.isEmpty() || HEX32.matches(s.trim().lowercase())

    fun zoneOk(s: String): Boolean = s.isEmpty() || DOMAIN.matches(s.trim().lowercase())

    /** A new API token: empty keeps the stored one. */
    fun tokenOk(s: String): Boolean = s.isEmpty() || TOKEN.matches(s.trim())

    fun maxOk(s: String): Boolean = s.isBlank() || (s.trim().toIntOrNull() ?: 0) in 1..100_000

    /** Which field is wrong first (its sentence), or null when the form can be sent. */
    fun problem(f: CloudflareForm): StringResource? = when {
        !tokenOk(f.token) -> Res.string.operator_cf_bad_token
        !idOk(f.accountId) -> Res.string.operator_cf_bad_account
        !idOk(f.zoneId) -> Res.string.operator_cf_bad_zone_id
        !zoneOk(f.zoneName) -> Res.string.operator_cf_bad_zone
        !maxOk(f.maxNames) -> Res.string.operator_cf_bad_max
        else -> null
    }

    /** PUT /api/operator/cloudflare: only what is filled in; an empty token field leaves the stored token alone. */
    fun cloudflareBody(f: CloudflareForm): JsonObject = buildJsonObject {
        if (f.token.isNotBlank()) put("api_token", f.token.trim())
        if (f.accountId.isNotBlank()) put("account_id", f.accountId.trim().lowercase())
        if (f.zoneId.isNotBlank()) put("zone_id", f.zoneId.trim().lowercase())
        if (f.zoneName.isNotBlank()) put("zone_name", f.zoneName.trim().lowercase())
        f.maxNames.trim().toIntOrNull()?.let { put("max_names", it) }
    }

    /** Turning the service off: the token is removed, everything else stays. */
    fun cloudflareOff(): JsonObject = buildJsonObject { put("api_token", "") }

    /** The form, filled from what the server has (never the token). */
    fun formOf(c: CloudflareConfig): CloudflareForm =
        CloudflareForm(accountId = c.accountId, zoneId = c.zoneId, zoneName = c.zoneName, maxNames = if (c.maxNames > 0) c.maxNames.toString() else "")

    /** A picked file that can be a service-account key: small, and a JSON object. Its content is never shown. */
    fun keyFileOk(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_KEY_FILE) return false
        val text = bytes.toString(Charsets.UTF_8)
        return runCatching { KeloApi.json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)
    }

    /** The sign-in this app uses is the owner's own token: an operator GET answered (403 = a paired phone, not the owner). */
    fun ownerFrom(status: Int?): Boolean? = when (status) {
        null -> true
        403 -> false
        else -> null
    }

    /**
     * Whether 마이 shows the operator section, from GET /api/operator/status: only an owner sign-in ([status] null) on the operator's
     * server ([operator] true). Every self-hosted copy answers false, so its owner's own phone does not see it. 403 = not an owner
     * sign-in. 404 = a server from before this call: [fallback] (the older rule, [ownerFrom] on GET /api/operator/firebase).
     * Anything else: not known (null), the last value stays.
     */
    fun operatorFrom(status: Int?, operator: Boolean?, fallback: () -> Boolean? = { null }): Boolean? = when (status) {
        null -> operator == true
        403 -> false
        404 -> fallback()
        else -> null
    }
}

/**
 * 마이 → 운영자: only for the person whose server other copies of Kelo lean on (it signs them in, sends their pushes, hands out
 * their addresses, receives their users' reports of bad replies), and only when this app is signed in with that server's own access key. Secrets go in, never come back out:
 * password fields, nothing echoed, and the window is kept out of screenshots. It also holds the sign-in providers' own settings
 * cards ("github", "google": [OperatorSettings]).
 */
@Composable
fun OperatorPage(onBack: () -> Unit) {
    LocalSecureWindow.current()
    val services = LocalAppServices.current
    val vm = rememberViewModel { OperatorViewModel(services.sessions, services.filePicker) }
    val ui by vm.state.collectAsStateWithLifecycle()
    SubPage(string(Res.string.operator_page_title), onBack) {
        MutedText(string(Res.string.operator_page_intro), small = true)
        SectionLabel(string(Res.string.operator_section_signin))
        OperatorSettings("github", startOpen = true)
        OperatorSettings("google", startOpen = true)
        SectionLabel(string(Res.string.operator_section_push))
        FirebaseCard(ui.firebase, vm)
        SectionLabel(string(Res.string.operator_section_addresses))
        CloudflareCard(ui.cloudflare, vm)
        SectionLabel(string(Res.string.operator_section_beta))
        BetaRequestsCard(ui.beta, vm)
        SectionLabel(string(Res.string.operator_section_reports))
        ReportsCard(ui.reports, vm)
    }
}

@Composable
internal fun noteText(note: OperatorNote): String = when (note) {
    OperatorNote.FirebaseSaved -> string(Res.string.operator_fb_saved)
    OperatorNote.Saved -> string(Res.string.operator_saved)
    OperatorNote.ReclaimedNone -> string(Res.string.operator_cf_reclaim_none)
    is OperatorNote.Reclaimed -> plural(Res.plurals.operator_cf_reclaimed, note.count, note.count)
    is OperatorNote.Mailed -> string(Res.string.operator_beta_mailed, note.email)
    is OperatorNote.Server -> note.text
}

@Composable
private fun problemText(p: OperatorProblem): String = when (p) {
    is OperatorProblem.Server -> p.text
    is OperatorProblem.App -> string(p.res)
}

@Composable
private fun FirebaseCard(ui: FirebaseUiState, vm: OperatorViewModel) {
    var confirmOff by remember { mutableStateOf(false) }
    val busy = ui.busy
    Card {
        Text(string(Res.string.operator_fb_title), fontWeight = FontWeight.Bold, color = Kelo.colors.ink, style = MaterialTheme.typography.bodyLarge)
        MutedText(string(Res.string.operator_fb_note), small = true)
        val c = ui.config
        when {
            c == null && ui.failed -> ErrorText(string(Res.string.connect_failed), small = true)
            c == null -> SkeletonLines(2)
            c.enabled -> {
                KeyLine(string(Res.string.operator_fb_project), c.projectId)
                KeyLine(string(Res.string.operator_fb_account), c.clientEmail)
            }
            else -> MutedText(string(Res.string.operator_fb_off), small = true)
        }
        if (c != null) {
            PrimaryButton(
                string(if (busy) Res.string.operator_fb_checking else if (c.enabled) Res.string.operator_fb_replace else Res.string.operator_fb_upload),
                enabled = !busy,
                onClick = { vm.pickFirebaseKey(listOf("application/json", "text/plain", "application/octet-stream")) },
            )
            if (c.enabled) LinkButton(string(Res.string.operator_fb_remove), onClick = { confirmOff = true }, danger = true)
        }
        ui.note?.let { MutedText(noteText(it), small = true) }
        ui.problem?.let { ErrorText(problemText(it), small = true) }
    }
    if (confirmOff) {
        ConfirmDialog(string(Res.string.operator_fb_remove_confirm), string(Res.string.operator_fb_remove), onDismiss = { confirmOff = false }, onYes = vm::removeFirebase)
    }
}

@Composable
private fun CloudflareCard(ui: CloudflareUiState, vm: OperatorViewModel) {
    var confirmOff by remember { mutableStateOf(false) }
    val form = ui.form
    val busy = ui.busy
    Card {
        Text(string(Res.string.operator_cf_title), fontWeight = FontWeight.Bold, color = Kelo.colors.ink, style = MaterialTheme.typography.bodyLarge)
        MutedText(string(Res.string.operator_cf_note), small = true)
        val c = ui.config
        if (c == null) {
            if (ui.failed) ErrorText(string(Res.string.connect_failed), small = true) else SkeletonLines(3)
            return@Card
        }
        KeyLine(string(Res.string.operator_cf_state), string(if (c.enabled) Res.string.operator_cf_on else Res.string.operator_cf_off))
        KeyLine(string(Res.string.operator_cf_used), string(Res.string.operator_cf_used_value, c.used, c.maxNames))
        val ascii = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)
        OpFieldLabel(string(Res.string.operator_cf_token))
        KeloTextField(
            form.token, { vm.editCloudflare(form.copy(token = it)) },
            string(if (c.hasToken) Res.string.operator_cf_token_saved else Res.string.operator_cf_token),
            password = true, maxLength = 200,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        )
        OpFieldLabel(string(Res.string.operator_cf_account))
        KeloTextField(form.accountId, { vm.editCloudflare(form.copy(accountId = it)) }, string(Res.string.operator_cf_account), maxLength = 32, keyboardOptions = ascii)
        OpFieldLabel(string(Res.string.operator_cf_zone_id))
        KeloTextField(form.zoneId, { vm.editCloudflare(form.copy(zoneId = it)) }, string(Res.string.operator_cf_zone_id), maxLength = 32, keyboardOptions = ascii)
        OpFieldLabel(string(Res.string.operator_cf_zone))
        KeloTextField(form.zoneName, { vm.editCloudflare(form.copy(zoneName = it)) }, string(Res.string.operator_cf_zone), maxLength = 253, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
        OpFieldLabel(string(Res.string.operator_cf_max))
        KeloTextField(form.maxNames, { vm.editCloudflare(form.copy(maxNames = it.filter(Char::isDigit))) }, string(Res.string.operator_cf_max), maxLength = 6,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PrimaryButton(string(if (busy) Res.string.operator_cf_checking else Res.string.save), enabled = !busy, onClick = vm::saveCloudflare)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(string(Res.string.operator_cf_reclaim), enabled = c.enabled && !busy, modifier = Modifier.weight(1f), onClick = vm::reclaimCloudflare)
        }
        MutedText(plural(Res.plurals.operator_cf_idle, c.idleDays, c.idleDays), small = true)
        if (c.hasToken) LinkButton(string(Res.string.operator_cf_turn_off), onClick = { confirmOff = true }, danger = true)
        ui.note?.let { MutedText(noteText(it), small = true) }
        ui.problem?.let { ErrorText(problemText(it), small = true) }
    }
    if (confirmOff) {
        ConfirmDialog(string(Res.string.operator_cf_off_confirm), string(Res.string.operator_cf_turn_off), onDismiss = { confirmOff = false }, onYes = vm::turnOffCloudflare)
    }
}

@Composable
private fun OpFieldLabel(text: String) {
    Text(text, color = Kelo.colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun KeyLine(label: String, value: String) {
    val c = Kelo.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
        Text(value.ifBlank { "—" }, color = c.ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
    }
}
