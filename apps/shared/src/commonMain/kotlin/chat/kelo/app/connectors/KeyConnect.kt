package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.components.emphasized
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/** A key or token made on the service (or a Zoom app, or a Google service account). The server tries it once before saving it. */
@Composable
internal fun KeyConnect(c: Connector, onChanged: () -> Unit, onNotify: ((String) -> Unit)?) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "key-${c.via}") { KeyConnectViewModel(services.sessions, c.via, services.filePicker) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var values by remember(c.via) { mutableStateOf(mapOf<String, String>()) }
    var confirming by remember { mutableStateOf(false) }
    LocalReloadOnReturn.current(c.via) { vm.load() }

    val form = ui.form
    if (form == null) {
        SkeletonLines(2)
        return
    }
    val google = form.id == "google"

    if (confirming) {
        ConfirmDialog(
            string(Res.string.key_disconnect_confirm, form.name), string(Res.string.connector_disconnect),
            onYes = { vm.disconnect(onChanged) },
            onDismiss = { confirming = false },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (form.connected) {
            ShieldTitle(string(Res.string.connector_connected_name, form.label))
            val account = ConnectorRules.keyAccount(form.label)
            if (google && account.isNotEmpty()) {
                SmallText(string(Res.string.key_google_share))
                Text(emphasized("%1\$s", account, code = true), style = MaterialTheme.typography.bodySmall, color = Kelo.colors.ink)
            }
            MutedText(string(Res.string.key_connected_note), small = true)
            if (onNotify != null) {
                val text = string(Res.string.chat_connect_notify, form.name)
                PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
            }
            LinkButton(string(Res.string.connector_disconnect), onClick = { confirming = true }, danger = true)
            return@Column
        }
        LocalSecureWindow.current()   // a key being typed: keep it out of screenshots and the recent-apps preview
        Steps(form.help)
        form.fields.forEach { x ->
            if (google && x.id == "key") {
                // the Google service account's JSON file, picked with the system's file picker
                SecondaryButton(
                    string(Res.string.key_pick_json),
                    onClick = { vm.pickKeyFile { text -> values = values + ("key" to text) } },
                )
                KeloTextField(
                    values["key"].orEmpty(), { values = values + ("key" to it) }, string(Res.string.key_paste_json),
                    singleLine = false, maxLength = 20_000,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            } else {
                KeloTextField(
                    values[x.id].orEmpty(), { values = values + (x.id to it) }, x.placeholder.ifBlank { x.label },
                    password = x.secret,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false, keyboardType = if (x.secret) KeyboardType.Password else KeyboardType.Ascii,
                    ),
                )
            }
        }
        val ready = ConnectorRules.keyReady(form, values)
        PrimaryButton(
            string(if (ui.busy) Res.string.key_checking else if (form.checked) Res.string.check_and_connect else Res.string.save),
            enabled = !ui.busy && ready,
            onClick = { vm.save(values) { values = emptyMap(); onChanged() } },
        )
        MutedText(string(if (form.checked) Res.string.key_note_checked else Res.string.key_note_unchecked), small = true)
        ConnectErrorLine(ui.error)
    }
}
