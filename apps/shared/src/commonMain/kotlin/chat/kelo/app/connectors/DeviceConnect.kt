package chat.kelo.app.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.SelectField
import chat.kelo.app.chat.SelectOption
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/**
 * Connect a service by typing a short code on its own page (GitHub, Microsoft). The app shows the code,
 * the user types it on the service, and the token goes straight into the vault. When the server offers a web sign-in
 * (GitHub with an OAuth app), "GitHub로 로그인" opens the service's sign-in page in the browser instead, and the panel
 * shows the new state when the user comes back to the app.
 */
@Composable
fun DeviceConnect(id: String, onChanged: () -> Unit, onNotify: ((String) -> Unit)?) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "device-$id") { DeviceConnectViewModel(services.sessions, services.browser, id) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val changed by rememberUpdatedState(onChanged)
    var clientId by remember { mutableStateOf("") }
    var editId by remember { mutableStateOf(false) }
    var byCode by rememberSaveable(id) { mutableStateOf(false) }   // the sign-in button is offered first; the code is the fallback
    var confirming by remember { mutableStateOf(false) }

    LocalReloadOnReturn.current(id) { vm.load() }
    LaunchedEffect(vm) { vm.connected.collect { changed() } }

    val provider = ui.provider
    if (provider == null) {
        SkeletonLines(2)
        return
    }
    val name = provider.name

    if (confirming) {
        ConfirmDialog(
            string(Res.string.device_disconnect_confirm, name),
            string(Res.string.connector_disconnect),
            onYes = { vm.disconnect(onChanged) },
            onDismiss = { confirming = false },
        )
    }

    val f = ui.flow
    // connected
    if (provider.connected && (f == null || f.status == "connected")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldTitle(string(Res.string.connector_connected_name, name))
            MutedText(string(Res.string.device_connected_note, provider.account.ifBlank { name }), small = true)
            if (onNotify != null) {
                val text = string(Res.string.chat_connect_notify, name)
                PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
            }
            LinkButton(string(Res.string.connector_disconnect), onClick = { confirming = true }, danger = true)
            if (provider.settingsUrl.isNotBlank()) {
                LinkButton(string(Res.string.device_manage, name), onClick = vm::openSettings)
            }
        }
        return
    }

    // waiting for the code to be typed
    if (f != null && f.pending) {
        val c = Kelo.colors
        val codeLabel = string(Res.string.device_code_label)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoldLine(string(Res.string.device_enter_code, name))
            SelectionContainer {
                Text(
                    f.userCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.bubble)
                        .padding(horizontal = 8.dp, vertical = 14.dp)
                        .semantics { contentDescription = codeLabel },
                    color = c.ink, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 3.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    string(if (ui.copied) Res.string.device_copied else Res.string.device_copy),
                    onClick = { clipboard.setText(AnnotatedString(f.userCode)); vm.copied() },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    string(Res.string.device_open, name),
                    onClick = vm::openVerification,
                    modifier = Modifier.weight(1f),
                )
            }
            MutedText(string(Res.string.device_wait_help, name, ConnectorRules.mmss(ui.secondsLeft)), small = true)
            LinkButton(string(Res.string.cancel), onClick = vm::cancelCode)
            ConnectErrorLine(ui.error)
        }
        return
    }

    val ended = ConnectorRules.ended(f)
    val failedText = string(Res.string.device_failed)

    @Composable
    fun ScopePicker() {
        if (provider.scopes.isEmpty()) return
        FieldLabel(string(Res.string.device_scope))
        SelectField(
            string(Res.string.device_scope),
            provider.scopes.map { SelectOption(it.id, it.label) },
            ui.scopeId,
            onSelect = vm::selectScope,
        )
        provider.scopes.find { it.id == ui.scopeId }?.detail?.takeIf { it.isNotBlank() }?.let { MutedText(it, small = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ended != null) ErrorText(ended.message.ifBlank { failedText }, small = true)
        if (provider.web && !byCode) {
            ScopePicker()
            PrimaryButton(
                if (ui.busy) string(Res.string.connector_opening) else string(Res.string.device_sign_in, name),
                enabled = !ui.busy,
                onClick = vm::signInOnWeb,
            )
            MutedText(string(Res.string.device_sign_in_help, name), small = true)
            LinkButton(string(Res.string.device_by_code), onClick = { byCode = true })
        } else if (!provider.hasClientId || editId) {
            BoldLine(string(Res.string.device_register, name))
            Steps(provider.help)
            KeloTextField(
                clientId, { clientId = it }, string(Res.string.client_id), maxLength = 100,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            )
            PrimaryButton(string(Res.string.save), enabled = !ui.busy && clientId.trim().length >= 6, onClick = {
                vm.saveClientId(clientId) { clientId = ""; editId = false }
            })
            if (editId) LinkButton(string(Res.string.cancel), onClick = { editId = false })
        } else {
            ScopePicker()
            PrimaryButton(
                if (ui.busy) string(Res.string.device_preparing) else string(Res.string.device_connect, name),
                enabled = !ui.busy,
                onClick = vm::startCode,
            )
            LinkButton(string(Res.string.device_change_client), onClick = { editId = true })
            if (provider.web) LinkButton(string(Res.string.device_sign_in_link, name), onClick = { byCode = false })
        }
        ConnectErrorLine(ui.error)
        if (provider.id == "github") OperatorSettings("github", onSaved = vm::load)
    }
}
