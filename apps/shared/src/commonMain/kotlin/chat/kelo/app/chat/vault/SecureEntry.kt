package chat.kelo.app.chat.vault

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.platform.LocalPasswordApp
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.emphasized
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch

/**
 * The form where a login or a key is typed. The value goes to the server and is never shown again;
 * the assistant never sees it. A login form is marked for Android autofill (username / password), so 1Password and other
 * password managers can fill it; a service key is never offered for autofill.
 *
 * Used on the chat's secure card and (later) in 마이 → 안전한 보관함.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SecureEntryForm(item: VaultItem, onSaved: (VaultMode) -> Unit, defaultMode: VaultMode = VaultMode.NOW) {
    val sessions = LocalAppServices.current.sessions
    val vm = rememberViewModel(item.name) { SecureFormViewModel(sessions) }
    val form by vm.state.collectAsStateWithLifecycle()
    var user by remember(item.name) { mutableStateOf("") }
    var pass by remember(item.name) { mutableStateOf("") }
    var mode by rememberSaveable(item.name) { mutableStateOf(defaultMode) }
    val msg = form.error
    val busy = form.busy
    val login = item.isLogin
    // a password or key being typed: keep the window out of screenshots and the recent-apps preview
    LocalSecureWindow.current()
    // the first field takes the focus at once, so a password app's suggestion shows up without a tap
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val passwordApp = LocalPasswordApp.current
    androidx.compose.runtime.LaunchedEffect(item.name) { runCatching { first.requestFocus() } }
    fun askPasswordApp() {
        runCatching { first.requestFocus() }
        passwordApp?.request()
    }

    fun submit() = vm.submit(item, user, pass, mode) { user = ""; pass = ""; onSaved(mode) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (login) {
            // only for phones that have a password app set up as the autofill service (nothing to explain otherwise)
            val hasPasswordApp = remember { passwordApp?.available == true }
            if (hasPasswordApp) {
                chat.kelo.app.ui.components.SecondaryButton(string(Res.string.vault_fill_from_app), onClick = ::askPasswordApp, modifier = Modifier.fillMaxWidth())
            }
            FieldLabel(string(Res.string.vault_username))
            KeloTextField(
                user, { user = it }, string(Res.string.vault_username), maxLength = 200,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                modifier = Modifier.focusRequester(first).autofill(listOf(AutofillType.Username, AutofillType.EmailAddress)) { user = it },
            )
        }
        FieldLabel(string(if (login) Res.string.vault_password else Res.string.vault_key))
        KeloTextField(
            pass, { pass = it }, string(if (login) Res.string.vault_password else Res.string.vault_key),
            password = true, maxLength = 512,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            modifier = if (login) Modifier.autofill(listOf(AutofillType.Password)) { pass = it } else Modifier.focusRequester(first),
        )
        if (login) {
            FieldLabel(string(Res.string.vault_mode))
            ModePicker(mode) { mode = it }
        }
        val ready = pass.isNotEmpty() && (if (login) user.isNotBlank() else pass.trim().length >= 8)
        PrimaryButton(string(if (busy) Res.string.vault_saving else Res.string.vault_save), onClick = ::submit, enabled = !busy && ready)
        if (msg.isNotEmpty()) ErrorText(msg, small = true)
        MutedText(string(Res.string.vault_footer), small = true)
    }
}

/**
 * How a login is kept, as one compact row: the chosen way's name (a tap lists the three ways, each with its one-line explanation),
 * and the chosen way's explanation under it.
 */
@Composable
internal fun ModePicker(mode: VaultMode, onPick: (VaultMode) -> Unit) {
    val c = Kelo.colors
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(string(modeText(mode)), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                androidx.compose.material3.Icon(KeloIcons.ChevronRight, contentDescription = null, tint = c.muted,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                VaultMode.entries.forEach { m ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(string(modeText(m)), style = MaterialTheme.typography.bodyMedium, color = c.ink,
                                    fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal)
                                Text(string(modeHelp(m)), style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                        },
                        onClick = { onPick(m); open = false },
                    )
                }
            }
        }
        Text(string(modeHelp(mode)), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Kelo.colors.ink)
}

private fun modeText(m: VaultMode) = when (m) {
    VaultMode.AFTER_SUCCESS -> Res.string.vault_mode_after_success
    VaultMode.NOW -> Res.string.vault_mode_now
    VaultMode.ONCE -> Res.string.vault_mode_once
}

private fun modeHelp(m: VaultMode) = when (m) {
    VaultMode.AFTER_SUCCESS -> Res.string.vault_mode_after_success_help
    VaultMode.NOW -> Res.string.vault_mode_now_help
    VaultMode.ONCE -> Res.string.vault_mode_once_help
}

/** "로그인 정보" / "키" phrase for an item: "<label> 로그인 정보" or "<label> 키". */
@Composable
fun vaultWhat(item: VaultItem): String =
    string(if (item.isLogin) Res.string.vault_what_login else Res.string.vault_what_key, item.label)

/**
 * The card that appears in the chat when the assistant asks for a login or a key. [onNotify] sends a message to
 * the agent ("…를 보관함에 저장했어요. 이어서 진행해 줘.").
 */
@Composable
fun SecureCard(name: String, onNotify: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val sessions = LocalAppServices.current.sessions
    val vm = rememberViewModel(name) { SecureCardViewModel(sessions, name) }
    val item = vm.state.collectAsStateWithLifecycle().value.item
    var done by rememberSaveable(name) { mutableStateOf<VaultMode?>(null) }
    var closed by rememberSaveable(name) { mutableStateOf(false) }
    val it = item ?: return
    val what = vaultWhat(it)
    val c = Kelo.colors
    CardBox(modifier) {
        if (closed) {
            MutedText(string(Res.string.vault_card_cancelled, what), small = true)
            return@CardBox
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KeloIcons.Shield, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(string(Res.string.vault_card_needed, what), fontWeight = FontWeight.Bold, color = c.ink)
        }
        MutedText(string(Res.string.vault_card_hosts, it.hosts.joinToString(", ")), small = true)
        val d = done
        if (d != null) {
            Text(
                string(
                    when (d) {
                        VaultMode.AFTER_SUCCESS -> Res.string.vault_card_done_after_success
                        VaultMode.ONCE -> Res.string.vault_card_done_once
                        VaultMode.NOW -> Res.string.vault_card_done_now
                    },
                ),
                style = MaterialTheme.typography.bodySmall, color = c.ink,
            )
            if (onNotify != null) {
                val text = string(if (d == VaultMode.NOW) Res.string.vault_card_notify_saved else Res.string.vault_card_notify_entered, what)
                PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
            }
        } else {
            SecureEntryForm(it, onSaved = { m -> done = m }, defaultMode = if (it.isLogin) VaultMode.AFTER_SUCCESS else VaultMode.NOW)
            LinkButton(string(Res.string.cancel), onClick = {
                // nothing was entered: the request is forgotten, and nothing is left in the vault
                vm.cancel { closed = true }
            })
        }
    }
}

/** Marks a field for the Android autofill service (password managers fill it on focus). */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.autofill(types: List<AutofillType>, onFill: (String) -> Unit): Modifier = composed {
    val fill by rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = types, onFill = { fill(it) }) }
    val autofill = LocalAutofill.current
    LocalAutofillTree.current += node
    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { f ->
            autofill?.run { if (f.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node) }
        }
}
