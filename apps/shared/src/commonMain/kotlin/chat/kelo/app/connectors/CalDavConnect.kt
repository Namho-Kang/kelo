package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
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
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.ui.text.string

/**
 * A calendar from any provider that offers CalDAV (Fastmail, Zoho, Nextcloud ...): the provider's CalDAV address, user name and an app
 * password. Read only: the morning briefing and heads-ups read it; Kelo never changes it.
 */
@Composable
internal fun CalDavConnect(c: Connector, onChanged: () -> Unit, onNotify: ((String) -> Unit)?) {
    val sessions = LocalAppServices.current.sessions
    val vm = viewModel(key = "caldav-${c.id}") { CalDavConnectViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var url by remember(c.id) { mutableStateOf("") }
    var user by remember(c.id) { mutableStateOf("") }
    var password by remember(c.id) { mutableStateOf("") }
    var adding by remember(c.id) { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CalDavAccount?>(null) }

    LocalReloadOnReturn.current(c.id) { vm.returned() }
    val shown = ui.accounts
    if (shown == null) {
        SkeletonLines(2)
        return
    }
    removing?.let { a ->
        ConfirmDialog(
            string(Res.string.caldav_disconnect_confirm, a.address), string(Res.string.connector_disconnect),
            onYes = { vm.remove(a, onChanged) },
            onDismiss = { removing = null },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShieldTitle(a.address)
                MutedText(a.url.removePrefix("https://"), small = true)
                MutedText(string(Res.string.caldav_account_note), small = true)
                LinkButton(string(Res.string.connector_disconnect), onClick = { removing = a }, danger = true)
            }
        }
        val form = shown.isEmpty() || adding
        if (form) {
            LocalSecureWindow.current()   // an app password being typed
            MutedText(string(Res.string.caldav_help), small = true)
            FieldLabel(string(Res.string.caldav_url))
            KeloTextField(
                url, { url = it }, string(Res.string.caldav_url_hint), maxLength = 300,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            )
            KeloTextField(
                user, { user = it }, string(Res.string.caldav_user), maxLength = 200,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            )
            KeloTextField(
                password, { password = it }, string(Res.string.mail_password), password = true, maxLength = 200,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            )
            PrimaryButton(
                string(if (ui.busy) Res.string.mail_checking else Res.string.check_and_connect),
                enabled = !ui.busy && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = { vm.add(url, user, password) { password = ""; user = ""; url = ""; adding = false; onChanged() } },
            )
            MutedText(string(Res.string.mail_check_note), small = true)
            if (adding) LinkButton(string(Res.string.cancel), onClick = { adding = false })
        } else {
            if (onNotify != null) {
                val text = string(Res.string.caldav_notify)
                PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
            }
            LinkButton(string(Res.string.caldav_add_other), onClick = { adding = true })
        }
        ConnectErrorLine(ui.error)
    }
}
