package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.layout.Arrangement
import org.jetbrains.compose.resources.StringResource
import chat.kelo.app.ui.platform.LocalSecureWindow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.connectors.KeyForm
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.KeloModal
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The sheet for a message the server held back because it holds a key or password ([SecretGuard]). First it asks: Save in Keychain,
 * Send as is (the same text with "secrets": "allow"), or Edit (back to the composer, text intact). Save opens its second step in the
 * same sheet: the value from the message already filled in (hidden, with Show), what it is for, and a name or site guessed from the
 * server's label; Save there goes straight from the app to the vault. A card number goes on to the wallet's card form instead.
 * The value lives only in this sheet's memory and goes with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretSheet(
    ask: SecretAsk, onSaved: (value: String, reference: String) -> Unit, onCard: () -> Unit, onSendAsIs: () -> Unit, onEdit: () -> Unit,
) {
    val c = Kelo.colors
    var saving by remember(ask) { mutableStateOf(false) }
    var busy by remember(ask) { mutableStateOf(false) }
    val uses = remember(ask) { SecretGuard.uses(ask.found) }
    KeloModal(onDismiss = { if (!busy) onEdit() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!saving) {
                Text(string(Res.string.secret_title), style = MaterialTheme.typography.titleMedium, color = c.ink,
                    modifier = Modifier.semantics { heading() })
                MutedText(string(Res.string.secret_body), small = true)
                MutedText(string(Res.string.secret_body_save), small = true)
                PrimaryButton(string(Res.string.secret_save), onClick = { if (uses == listOf(SecretUse.CARD)) onCard() else saving = true })
                SecondaryButton(string(Res.string.secret_send_as_is), onClick = onSendAsIs, modifier = Modifier.fillMaxWidth())
                SecondaryButton(string(Res.string.secret_edit), onClick = onEdit, modifier = Modifier.fillMaxWidth())
            } else {
                SaveStep(ask, uses, onBusy = { busy = it }, onSaved = onSaved, onCard = onCard, onBack = { saving = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveStep(
    ask: SecretAsk, uses: List<SecretUse>, onBusy: (Boolean) -> Unit, onSaved: (String, String) -> Unit, onCard: () -> Unit, onBack: () -> Unit,
) {
    LocalSecureWindow.current()   // the value is on screen: out of screenshots and the recent-apps preview
    val c = Kelo.colors
    val sessions = LocalAppServices.current.sessions
    val offline = string(Res.string.agent_offline)
    val scope = rememberCoroutineScope()
    val found = ask.found
    // memory only (remember, never rememberSaveable): the value goes when the sheet goes
    val original = remember(ask) { ask.values.firstOrNull().orEmpty() }
    var value by remember(ask) { mutableStateOf(original) }
    var shown by remember(ask) { mutableStateOf(false) }
    var use by remember(ask) { mutableStateOf(uses.first()) }
    var label by remember(ask) { mutableStateOf(found.label) }
    var host by remember(ask) { mutableStateOf(SecretGuard.hostGuess(found.label)) }
    var site by remember(ask) { mutableStateOf("") }
    var user by remember(ask) { mutableStateOf("") }
    var form by remember(ask) { mutableStateOf<KeyForm?>(null) }
    val others = remember(ask) { mutableStateMapOf<String, String>() }
    var err by remember(ask) { mutableStateOf("") }
    var busy by remember(ask) { mutableStateOf(false) }

    LaunchedEffect(use) {
        if (use == SecretUse.CONNECTOR_KEY && form == null) {
            form = try { SecretSave.connectorForm(sessions.requireApi(), found.target.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
    val connector = form
    val name = when (use) {
        SecretUse.AI_KEY -> found.label.ifBlank { found.target.id }
        SecretUse.CONNECTOR_KEY -> connector?.name?.ifBlank { null } ?: found.label.ifBlank { found.target.id }
        SecretUse.LOGIN -> label.trim().ifBlank { site.trim() }
        SecretUse.SERVICE_KEY -> label.trim().ifBlank { host.trim() }
        SecretUse.CARD -> ""
    }
    val ready = !busy && value.trim().length >= (if (use == SecretUse.LOGIN) 1 else 8) && when (use) {
        SecretUse.AI_KEY -> found.target.id.isNotEmpty()
        SecretUse.CONNECTOR_KEY -> connector != null && connector.fields.filter { !it.secret }.all { others[it.id].orEmpty().isNotBlank() }
        SecretUse.LOGIN -> site.isNotBlank() && user.isNotBlank()
        SecretUse.SERVICE_KEY -> host.isNotBlank()
        SecretUse.CARD -> false
    }

    fun save() {
        busy = true; onBusy(true); err = ""
        val v = value
        scope.launch {
            try {
                val api = sessions.requireApi()
                when (use) {
                    SecretUse.AI_KEY -> SecretSave.aiKey(api, found.target.id, v)
                    SecretUse.CONNECTOR_KEY -> SecretSave.connectorKey(api, connector ?: return@launch, v, others.toMap())
                    SecretUse.LOGIN -> SecretSave.login(api, site, label, user, v)
                    SecretUse.SERVICE_KEY -> SecretSave.serviceKey(api, host, label, v)
                    SecretUse.CARD -> return@launch
                }
                value = ""
                onSaved(original, loadString(Res.string.secret_saved_ref, name.ifBlank { loadString(useText(use)) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                err = if (e.isNetwork) offline else e.detail   // not OkHttp's "Failed to connect to /10.0.2.2:…"
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false; onBusy(false)
            }
        }
    }

    Text(string(Res.string.secret_save), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.semantics { heading() })
    if (uses.size > 1) {
        Text(string(Res.string.secret_for), color = c.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            uses.forEach { u -> ChoiceChip(string(useText(u)), selected = use == u, onClick = { use = u; err = "" }) }
        }
    }
    if (use == SecretUse.CARD) {
        MutedText(string(Res.string.secret_card_help), small = true)
        PrimaryButton(string(Res.string.secret_card_next), onClick = onCard)
        LinkButton(string(Res.string.back), onClick = onBack)
        return
    }
    if ((use == SecretUse.AI_KEY || use == SecretUse.CONNECTOR_KEY) && name.isNotBlank()) {
        Text(string(Res.string.secret_key_for, name), color = c.ink, style = MaterialTheme.typography.bodyMedium)
    }
    when (use) {
        SecretUse.CONNECTOR_KEY -> connector?.fields?.filter { !it.secret }?.forEach { f ->
            Label(f.label.ifBlank { f.id })
            KeloTextField(others[f.id].orEmpty(), { others[f.id] = it }, f.placeholder.ifBlank { f.label },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
        } ?: chat.kelo.app.ui.components.SkeletonLines(1)
        SecretUse.LOGIN -> {
            Label(string(Res.string.vault_login_site))
            KeloTextField(site, { site = it }, string(Res.string.vault_login_site_placeholder),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            Label(string(Res.string.vault_username))
            KeloTextField(user, { user = it }, string(Res.string.vault_username), maxLength = 200,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email))
            Label(string(Res.string.secret_label))
            KeloTextField(label, { label = it }, string(Res.string.vault_login_name_placeholder), maxLength = 80)
        }
        SecretUse.SERVICE_KEY -> {
            Label(string(Res.string.secret_label))
            KeloTextField(label, { label = it }, string(Res.string.secret_label), maxLength = 80)
            Label(string(Res.string.secret_host))
            KeloTextField(host, { host = it }, string(Res.string.secret_host_hint),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
        }
        else -> Unit
    }
    Label(string(if (use == SecretUse.LOGIN) Res.string.vault_password else Res.string.secret_value))
    KeloTextField(
        value, { value = it }, string(Res.string.secret_value), password = !shown, maxLength = 4096,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(KeloIcons.Eye, contentDescription = string(if (shown) Res.string.secret_hide else Res.string.secret_show),
                    tint = if (shown) c.accent else c.muted, modifier = Modifier.size(20.dp))
            }
        },
    )
    if (err.isNotEmpty()) ErrorText(err, small = true)
    PrimaryButton(string(if (busy) Res.string.vault_saving else Res.string.save), onClick = ::save, enabled = ready)
    LinkButton(string(Res.string.back), onClick = onBack)
}

@Composable
private fun Label(text: String) =
    Text(text, color = Kelo.colors.ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)

private fun useText(u: SecretUse): StringResource = when (u) {
    SecretUse.AI_KEY -> Res.string.secret_use_ai
    SecretUse.CONNECTOR_KEY -> Res.string.secret_use_connector
    SecretUse.CARD -> Res.string.secret_use_card
    SecretUse.LOGIN -> Res.string.secret_use_login
    SecretUse.SERVICE_KEY -> Res.string.secret_use_service
}
