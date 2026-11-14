package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloModal
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/**
 * Under the "Google sign-in is getting ready" note of Gmail / Google Calendar: ask to be let in early ([BetaRules]). The request (the
 * Google address the person will connect) goes to this server, which forwards it to the Kelo developer and keeps its state, so
 * "Requested" survives reopening. Once the developer approves, GET /api/google/beta says [GoogleBetaState.open] and [onOpened] reloads
 * the screen, which then shows the normal Google sign-in button.
 */
@Composable
internal fun BetaRequest(c: Connector, onOpened: () -> Unit) {
    val product = BetaRules.product(c.id) ?: return
    val services = LocalAppServices.current
    val vm = viewModel(key = "beta-${c.id}") { BetaRequestViewModel(services.sessions, services.appVersion) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable(c.id) { mutableStateOf(false) }
    LaunchedEffect(c.id) { vm.load() }
    val s = ui.beta
    when (BetaRules.shown(s)) {
        BetaRules.Shown.REQUEST -> PrimaryButton(string(Res.string.beta_request), onClick = { sheet = true })
        BetaRules.Shown.REQUESTED -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton(string(Res.string.beta_requested), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
            MutedText(string(Res.string.beta_sent, s?.email.orEmpty()), small = true)
        }
        BetaRules.Shown.DECLINED -> MutedText(string(Res.string.beta_declined), small = true)
        BetaRules.Shown.OPENED -> LaunchedEffect(Unit) { onOpened() }
        BetaRules.Shown.NOTHING -> {}
    }
    if (sheet) BetaSheet(ui.sending, ui.error, onClose = { sheet = false }, onSend = { email -> vm.send(email, product) { sheet = false } }, onEdit = vm::clearError)
}

@Composable
private fun BetaSheet(sending: Boolean, error: String, onClose: () -> Unit, onSend: (email: String) -> Unit, onEdit: () -> Unit) {
    val c = Kelo.colors
    var email by rememberSaveable { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val valid = BetaRules.email(email)
    val send: () -> Unit = {
        tried = true
        if (valid != null && !sending) onSend(valid)
    }
    KeloModal(onDismiss = onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(string(Res.string.beta_request), style = MaterialTheme.typography.titleMedium, color = c.ink)
            MutedText(string(Res.string.beta_sheet_note), small = true)
            KeloTextField(
                email, { email = it; onEdit() }, string(Res.string.beta_email_hint), maxLength = 254,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            if (tried && valid == null) ErrorText(string(Res.string.beta_email_invalid), small = true)
            if (error.isNotEmpty()) ErrorText(error, small = true)
            PrimaryButton(string(if (sending) Res.string.beta_sending else Res.string.beta_send), enabled = !sending && email.isNotBlank(), onClick = send)
        }
    }
}
