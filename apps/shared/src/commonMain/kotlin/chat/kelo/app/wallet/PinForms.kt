package chat.kelo.app.wallet

import androidx.compose.runtime.LaunchedEffect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch

data class PinFormUiState(val busy: Boolean = false, val problem: CardSendError? = null)

/**
 * Saving a payment PIN for one site: [save] asks for the owner's check, seals the PIN with this device's key and sends only that
 * ciphertext. The PIN never goes to the chat, the agent or a log.
 */
class PinFormViewModel(private val sessions: Sessions, private val cardKeys: CardKeys, private val requestId: String?) : ViewModel() {
    private val _state = MutableStateFlow(PinFormUiState())
    val state: StateFlow<PinFormUiState> = _state.asStateFlow()
    private val _saved = Channel<PinSaved>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    fun save(site: String, name: String, secret: PinSecret, title: String, subtitle: String) {
        _state.value = PinFormUiState(busy = true)
        viewModelScope.launch {
            val (saved, err) = savePin(sessions.requireApi(), cardKeys, site, name, secret, title, subtitle, requestId)
            _state.value = PinFormUiState(problem = err.takeIf { saved == null })
            if (saved != null) _saved.trySend(saved)
        }
    }
}

/**
 * Saving a payment PIN for one site: the site (fixed when the agent asked for a site's PIN), a name, the PIN twice (masked, digits
 * only, 4-8). [저장] asks for the owner's fingerprint / face, encrypts the PIN with this phone's key and sends only that ciphertext.
 * The PIN never goes to the chat, the agent or a log. [onSaved]: the server's answer.
 */
@Composable
fun PinForm(
    site: String = "",
    siteFixed: Boolean = false,
    requestId: String? = null,
    onSaved: (PinSaved) -> Unit,
    modifier: Modifier = Modifier,
) {
    LocalSecureWindow.current()
    val services = LocalAppServices.current
    val vm = rememberViewModel(requestId) { PinFormViewModel(services.sessions, services.cardKeys, requestId) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var where by remember { mutableStateOf(site) }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val busy = ui.busy
    val problem = ui.problem
    val title = string(Res.string.pin_auth_save_title)
    val subtitle = string(Res.string.pin_auth_save_subtitle)
    LaunchedEffect(vm) { vm.saved.collect { saved -> pin = ""; again = ""; onSaved(saved) } }
    fun save() {
        val secret = PinSecret.of(pin) ?: return
        vm.save(where, name, secret, title, subtitle)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!siteFixed) {
            Label(string(Res.string.pin_form_site))
            KeloTextField(where, { where = it.take(200) }, string(Res.string.pin_form_site_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        }
        Label(string(Res.string.pin_form_name))
        KeloTextField(name, { name = it.filterNot { ch -> ch.isDigit() }.take(60) }, string(Res.string.pin_form_name_hint))
        Label(string(Res.string.pin_form_pin))
        KeloTextField(pin, { pin = PinRules.clean(it) }, string(Res.string.pin_form_pin_hint), password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false))
        KeloTextField(again, { again = PinRules.clean(it) }, string(Res.string.pin_form_again), password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false))
        if (PinRules.mismatch(pin, again)) ErrorText(string(Res.string.pin_form_mismatch), small = true)
        problem?.let { ErrorText(pinErrorText(it), small = true) }
        PrimaryButton(string(if (busy) Res.string.vault_saving else Res.string.save), onClick = ::save,
            enabled = !busy && PinRules.ready(pin, again, where))
        MutedText(string(Res.string.pin_form_note), small = true)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Kelo.colors.ink)
}

@Composable
fun pinErrorText(e: CardSendError): String = string(
    when (e) {
        CardSendError.NO_LOCK -> Res.string.chat_card_error_no_lock
        CardSendError.CANCELLED -> Res.string.chat_card_error_cancelled
        CardSendError.NETWORK -> Res.string.chat_card_error_network
        CardSendError.NOT_ON_PHONE -> Res.string.pin_error_not_on_phone
        CardSendError.MISMATCH -> Res.string.pin_error_rejected
        CardSendError.NOT_WAITING -> Res.string.chat_card_error_not_waiting
        CardSendError.OTHER -> Res.string.chat_card_error_other
        CardSendError.KEYGUARD_REQUIRED -> Res.string.wallet_keyguard_needed_title
    },
)
