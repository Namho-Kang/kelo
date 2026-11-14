package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.Card
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.components.emphasized
import chat.kelo.app.ui.platform.rememberPageViewModelStoreOwner
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/**
 * For the person who runs Kelo for others, the OAuth app (GitHub or Google) everyone signs in with.
 * The secret can be replaced, never read back. Nothing is shown to a paired phone (it cannot change operator settings).
 */
@Composable
fun OperatorSettings(provider: String, onSaved: () -> Unit = {}, startOpen: Boolean = false) {
    val owner = rememberPageViewModelStoreOwner("operator-settings:$provider")
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        OperatorSettingsContent(provider, onSaved, startOpen)
    }
}

@Composable
private fun OperatorSettingsContent(provider: String, onSaved: () -> Unit, startOpen: Boolean) {
    val sessions = LocalAppServices.current.sessions
    val vm = viewModel(key = "operator-$provider") { OperatorSettingsViewModel(sessions, provider) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val github = provider == "github"
    var open by rememberSaveable(provider) { mutableStateOf(startOpen) }

    if (ui.owner == false) return
    if (!open) {
        if (ui.owner == true) LinkButton(string(Res.string.operator_open), onClick = { open = true })
        return
    }
    val a = ui.app
    if (a == null) {
        SkeletonLines(2)
        return
    }
    var cid by remember(a.clientId) { mutableStateOf(a.clientId) }
    var secret by remember(provider) { mutableStateOf("") }
    val secretHint = string(if (github) Res.string.operator_secret_github else Res.string.operator_secret_google)
    Card(Modifier.padding(top = 6.dp)) {
        BoldLine(string(if (github) Res.string.operator_title_github else Res.string.operator_title_google))
        MutedText(string(Res.string.operator_note), small = true)
        if (a.callback.isNotBlank()) {
            Text(
                emphasized(string(if (github) Res.string.operator_callback_github else Res.string.operator_callback_google), a.callback, code = true),
                style = MaterialTheme.typography.bodySmall, color = Kelo.colors.ink,
            )
        }
        KeloTextField(
            cid, { cid = it }, string(Res.string.client_id), maxLength = 100,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        )
        KeloTextField(
            secret, { secret = it },
            if (a.hasSecret) string(Res.string.operator_secret_saved, secretHint) else secretHint,
            password = true, maxLength = 200,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        )
        PrimaryButton(string(Res.string.save), enabled = cid.isNotBlank(), onClick = {
            vm.save(cid, secret) { secret = ""; onSaved() }
        })
        MutedText(string(if (a.isBroker) Res.string.operator_broker_on else Res.string.operator_broker_off), small = true)
        if (!github) GoogleOpenSwitch(a.openToAll, ui.switchBusy, ui.switchError, vm::setOpenToAll)
        when (val m = ui.message) {
            null -> {}
            OperatorMessage.Saved -> SmallText(string(Res.string.operator_saved))
            is OperatorMessage.Said -> SmallText(m.text)
        }
    }
}

/**
 * 운영자 설정 → Google OAuth: "모두에게 Google 로그인 열기". Off (the default) keeps new Google sign-ins closed for everyone while the
 * client waits for Google's verification; accounts already connected keep working either way.
 */
@Composable
private fun GoogleOpenSwitch(on: Boolean, busy: Boolean, error: String, onChange: (Boolean) -> Unit) {
    val c = Kelo.colors
    val title = string(Res.string.google_review_open_switch)
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(string(Res.string.google_review_open_warning), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Switch(
            checked = on, enabled = !busy,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
    if (error.isNotEmpty()) ErrorText(error, small = true)
}
