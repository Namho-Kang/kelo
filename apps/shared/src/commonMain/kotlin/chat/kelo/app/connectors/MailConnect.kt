package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.SelectField
import chat.kelo.app.chat.SelectOption
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

/** A mailbox with an app password (or Google sign-in for Gmail). The server signs in once to check it; only a mailbox that lets it in is saved. */
@Composable
internal fun MailConnect(c: Connector, onChanged: () -> Unit, onNotify: ((String) -> Unit)?) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "mail-${c.id}") { MailConnectViewModel(services.sessions, services.browser) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val gmail = c.id == "gmail"
    var provider by rememberSaveable(c.id) { mutableStateOf(if (gmail) "gmail" else "naver") }
    var address by remember(c.id) { mutableStateOf("") }
    var password by remember(c.id) { mutableStateOf("") }
    var adding by remember(c.id) { mutableStateOf(false) }
    var byPassword by rememberSaveable(c.id) { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MailAccount?>(null) }

    // back from Google's page (or shown again): the mailboxes are read again and the button is ready
    LocalReloadOnReturn.current(c.id) { vm.returned() }

    val data = ui.data
    if (data == null) {   // not the password form for a moment before the connected mailbox shows
        SkeletonLines(2)
        return
    }
    val providers = data.providers
    val shown = ConnectorRules.mailAccounts(c.id, data.accounts)
    val choices = ConnectorRules.mailChoices(c.id, providers)
    val p = providers.find { it.id == provider }

    removing?.let { a ->
        ConfirmDialog(
            string(Res.string.mail_disconnect_confirm, a.label), string(Res.string.connector_disconnect),
            onYes = { vm.remove(a, onChanged) },
            onDismiss = { removing = null },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShieldTitle(a.label)
                MutedText(string(Res.string.mail_account_note), small = true)
                LinkButton(string(Res.string.connector_disconnect), onClick = { removing = a }, danger = true)
            }
        }
        if (shown.isNotEmpty() && onNotify != null) {
            val text = string(Res.string.mail_notify)
            PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
        }
        val form = shown.isEmpty() || adding
        if (form && gmail && ConnectorRules.pendingReview(c) && !byPassword) {
            // the Google sign-in waits for Google's review: the note, and the app password as a small link
            ReviewNote(c, onOpened = { vm.load(); onChanged() })
            LinkButton(string(Res.string.mail_by_password), onClick = { byPassword = true })
        } else if (form && gmail && data.googleWeb && !byPassword) {
            PrimaryButton(
                if (ui.busy) string(Res.string.connector_opening) else string(Res.string.mail_google_sign_in),
                enabled = !ui.busy,
                onClick = vm::signInWithGoogle,
            )
            MutedText(string(Res.string.mail_google_help), small = true)
            LinkButton(string(Res.string.mail_by_password), onClick = { byPassword = true })
        } else if (form) {
            LocalSecureWindow.current()   // an app password being typed
            if (choices.size > 1) {
                FieldLabel(string(Res.string.mail_provider))
                SelectField(string(Res.string.mail_provider), choices.map { SelectOption(it.id, it.name) }, provider, onSelect = { provider = it })
            }
            p?.let { Steps(it.help) }
            KeloTextField(
                address, { address = it }, string(Res.string.mail_address), maxLength = 200,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            )
            KeloTextField(
                password, { password = it }, string(Res.string.mail_password), password = true, maxLength = 200,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            )
            PrimaryButton(
                string(if (ui.busy) Res.string.mail_checking else Res.string.check_and_connect),
                enabled = !ui.busy && address.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    vm.add(provider, address, password) { password = ""; address = ""; adding = false; onChanged() }
                },
            )
            MutedText(string(Res.string.mail_check_note), small = true)
            if (adding) LinkButton(string(Res.string.cancel), onClick = { adding = false })
        } else if (!gmail) {
            LinkButton(string(Res.string.mail_add_other), onClick = { adding = true })
        }
        ConnectErrorLine(ui.error)
        if (gmail) OperatorSettings("google", onSaved = vm::load)
    }
}
