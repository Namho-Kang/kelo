package chat.kelo.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Unofficial providers: ones that sign in with a consumer account (a Claude or ChatGPT subscription, Antigravity, Qwen, MiniMax)
 * whose maker has not confirmed it allows this use. They are listed last, under their own divider, and choosing one asks the
 * user once (per provider and consent version, recorded on the server so other phones do not ask again).
 */

/** What a provider list remembers about consent while it is open: what was agreed to here, and the question on screen. */
@Stable
class ConsentGate(private val scope: CoroutineScope, private val api: () -> KeloApi, private val memory: ConsentMemory) {
    var asking by mutableStateOf<ProviderInfo?>(null)
        private set
    var accepted by mutableStateOf(setOf<String>())
        private set
    private var onYes: () -> Unit = {}
    private var onNo: () -> Unit = {}
    var error by mutableStateOf<String?>(null)
        private set

    fun needs(p: ProviderInfo): Boolean = p.name !in accepted && ProviderConsentRules.needs(p)

    /** Runs [then] at once when [p] needs no agreement, else after the user agreed (and the server recorded it). */
    fun request(p: ProviderInfo?, onDecline: () -> Unit = {}, then: () -> Unit) {
        if (p == null || !needs(p)) { then(); return }
        onYes = then
        onNo = onDecline
        error = null
        asking = p
    }

    fun alreadyAsked(p: ProviderInfo): Boolean = memory.asked(ProviderConsentRules.askedKey(p))
    fun markAsked(p: ProviderInfo) = memory.markAsked(ProviderConsentRules.askedKey(p))

    internal fun decline() {
        asking?.let(::markAsked)
        asking = null
        onNo()
    }

    internal fun agree() {
        val p = asking ?: return
        scope.launch {
            try {
                api().put<JsonElement>("/api/providers/${KeloApi.segment(p.name)}/consent", buildJsonObject {
                    put("accepted", true)
                    put("version", p.consentVersion)
                })
                accepted = accepted + p.name
                markAsked(p)
                asking = null
                onYes()
            } catch (e: ApiError) {
                error = e.detail
            }
        }
    }
}

/** Which providers the user was already asked about (kept by the app: the phone on disk, the desktop for the run). */
interface ConsentMemory {
    fun asked(key: String): Boolean
    fun markAsked(key: String)

    /** Forgets everything when the app closes. */
    class InMemory : ConsentMemory {
        private val keys = mutableSetOf<String>()
        override fun asked(key: String) = key in keys
        override fun markAsked(key: String) { keys += key }
    }
}

val LocalConsentMemory = staticCompositionLocalOf<ConsentMemory> { ConsentMemory.InMemory() }

@Composable
fun rememberConsentGate(): ConsentGate {
    val sessions = LocalAppServices.current.sessions
    val scope = rememberCoroutineScope()
    val memory = LocalConsentMemory.current
    return remember(sessions, memory) { ConsentGate(scope, { sessions.requireApi() }, memory) }
}

/** The question itself; shown while [gate] asks. */
@Composable
fun ConsentDialog(gate: ConsentGate, displayName: (ProviderInfo) -> String = { it.displayName }) {
    val p = gate.asking ?: return
    val c = Kelo.colors
    var checked by remember(p.name) { mutableStateOf(false) }
    val service = when (ProviderConsentRules.service(p.name)) {
        ConsentService.CLAUDE_SUBSCRIPTION -> string(Res.string.consent_service_claude)
        ConsentService.CHATGPT_SUBSCRIPTION -> string(Res.string.consent_service_chatgpt)
        ConsentService.ANTIGRAVITY -> "Antigravity"
        ConsentService.QWEN -> "Qwen"
        ConsentService.MINIMAX -> "MiniMax"
        ConsentService.OTHER -> displayName(p)
    }
    val vendor = p.vendor.ifBlank { displayName(p) }
    AlertDialog(
        onDismissRequest = { gate.decline() }, containerColor = c.card,
        title = { Text(string(Res.string.consent_title, service), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    string(Res.string.consent_body, service, vendor + ProviderConsentRules.subjectParticle(vendor), vendor),
                    color = c.ink, style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted))
                    Text(string(Res.string.consent_check), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                gate.error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { gate.agree() }, enabled = checked) { Text(string(Res.string.consent_use), color = if (checked) c.accent else c.muted) }
        },
        dismissButton = { TextButton(onClick = { gate.decline() }) { Text(string(Res.string.cancel), color = c.ink) } },
    )
}
