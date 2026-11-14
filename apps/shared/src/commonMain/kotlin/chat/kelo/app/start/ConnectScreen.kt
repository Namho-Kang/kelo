package chat.kelo.app.start

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.PairingLinks
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.platform.LocalQrScanner
import chat.kelo.app.ui.platform.LocalBackHandler
import chat.kelo.app.ui.platform.keloViewModel
import androidx.compose.material3.AlertDialog
import chat.kelo.app.ui.components.CenterCard
import chat.kelo.app.ui.components.CenterMessage
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.emphasized
import chat.kelo.app.ui.theme.Kelo

/**
 * Signed out: the connect screen and, where the device has a way to scan a QR code, its scanner ([LocalQrScanner]: the phone's
 * camera; the desktop takes the pasted link). [initialLink] is a pairing link the system handed over at start (the desktop).
 */
@Composable
fun ConnectFlow(initialLink: String = "") {
    val services = LocalAppServices.current
    val vm = keloViewModel("connect") { ConnectViewModel(services, initialLink) }
    val state by vm.state.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }
    val scanner = LocalQrScanner.current
    if (scanning) LocalBackHandler.current { scanning = false }

    if (state.pairing) {
        CenterMessage(string(Res.string.connect_pairing))
        return
    }
    state.confirm?.let { link ->
        val host = remember(link.serverUrl) { java.net.URI(link.serverUrl).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }
        val c = Kelo.colors
        AlertDialog(
            onDismissRequest = vm::cancelPair,
            containerColor = c.card,
            title = { Text(string(Res.string.connect_confirm_title), color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host, color = c.ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(string(Res.string.connect_confirm_body), color = c.muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmPair) { Text(string(Res.string.connect_confirm_yes), color = c.accent) } },
            dismissButton = { TextButton(onClick = vm::cancelPair) { Text(string(Res.string.cancel), color = c.ink) } },
        )
    }
    if (scanning && scanner != null) {
        scanner.Scan(
            onClose = { scanning = false },
            accept = { raw -> PairingLinks.parse(raw) != null },
            onScanned = { raw ->
                scanning = false
                vm.useLink(raw)
            },
        )
    } else {
        ConnectScreen(vm, state, onScan = if (scanner != null) ({ scanning = true }) else null)
    }
}

@Composable
private fun ConnectScreen(vm: ConnectViewModel, state: ConnectState, onScan: (() -> Unit)?) {
    val c = Kelo.colors
    val clipboard = LocalClipboardManager.current
    // without a camera the pasted link is the way in, so its field is open at once
    var withLink by rememberSaveable { mutableStateOf(onScan == null) }
    var link by rememberSaveable { mutableStateOf("") }
    var withKey by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(vm.lastServerUrl) }
    var key by rememberSaveable { mutableStateOf("") }

    CenterCard {
        Text(string(Res.string.connect_title), style = MaterialTheme.typography.headlineMedium)
        state.notice?.let { ErrorText(it.resolve()) }
        MutedText(string(Res.string.connect_lead))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Step(1, emphasized(string(Res.string.connect_step1), string(Res.string.connect_step1_path)))
            Step(2, AnnotatedString(string(Res.string.connect_step2)))
        }
        Text(
            emphasized(string(Res.string.connect_first_device), string(Res.string.connect_first_device_cmd), code = true),
            color = c.muted, style = MaterialTheme.typography.bodySmall,
        )

        if (onScan != null) PrimaryButton(string(Res.string.connect_scan), onClick = onScan)

        if (withLink) {
            KeloTextField(
                value = link, onValueChange = { link = it; vm.clearLinkError() },
                placeholder = string(Res.string.connect_link_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) vm.useLink(link) }),
            )
            state.linkError?.let { ErrorText(it.resolve(), small = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(string(Res.string.connect_link_paste), onClick = {
                    clipboard.getText()?.text?.trim()?.let { link = it; vm.clearLinkError() }
                })
                PrimaryButton(string(Res.string.connect_submit), onClick = { vm.useLink(link) },
                    enabled = link.isNotBlank(), modifier = Modifier.weight(1f))
            }
        } else {
            SecondaryButton(string(Res.string.connect_paste_link), onClick = { withLink = true }, modifier = Modifier.fillMaxWidth())
        }

        if (withKey) {
            Text(string(Res.string.connect_server_label), color = c.muted, style = MaterialTheme.typography.bodySmall)
            KeloTextField(
                value = server, onValueChange = { server = it },
                placeholder = string(Res.string.connect_server_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
            KeloTextField(
                value = key, onValueChange = { key = it }, password = true,
                placeholder = string(Res.string.connect_key_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    if (!state.keyBusy && key.isNotBlank() && server.isNotBlank()) vm.connectWithKey(server, key)
                }),
            )
            MutedText(string(Res.string.connect_key_hint), small = true)
            state.keyError?.let { ErrorText(it.resolve()) }
            PrimaryButton(
                string(if (state.keyBusy) Res.string.connect_checking else Res.string.connect_submit),
                onClick = { vm.connectWithKey(server, key) },
                enabled = !state.keyBusy && key.isNotBlank() && server.isNotBlank(),
            )
        } else {
            LinkButton(string(Res.string.connect_with_key), onClick = { withKey = true })
        }
    }
}

@Composable
private fun Step(n: Int, text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(string(Res.string.step_number, n), style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
