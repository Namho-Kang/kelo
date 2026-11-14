package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.Segmented
import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.Badge
import chat.kelo.app.ui.components.IconTitle
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.PushHost
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.components.rememberSkeletonShown
import androidx.compose.foundation.layout.fillMaxSize
import chat.kelo.app.chat.SelectField
import chat.kelo.app.chat.SelectOption
import chat.kelo.app.chat.vault.SecureEntryForm
import chat.kelo.app.chat.vault.VaultCalls
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.rememberViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Where an API key goes in the request. */
private val PLACES = listOf(
    "bearer_header" to Res.string.vault_place_bearer, "custom_header" to Res.string.vault_place_header,
    "query_param" to Res.string.vault_place_query, "url_path_segment" to Res.string.vault_place_path,
)

/** The log's actions in words. */
private val ACTIONS = mapOf(
    "used" to Res.string.vault_action_used, "denied" to Res.string.vault_action_denied, "requested" to Res.string.vault_action_requested,
    "added" to Res.string.vault_action_added, "saved" to Res.string.vault_action_saved, "replaced" to Res.string.vault_action_replaced,
    "entered-once" to Res.string.vault_action_entered_once, "deleted" to Res.string.vault_action_deleted, "setting" to Res.string.vault_action_setting,
    "locked" to Res.string.vault_action_locked, "unlocked" to Res.string.vault_action_unlocked, "result" to Res.string.vault_action_result,
    "failed" to Res.string.vault_action_failed, "rejected" to Res.string.vault_action_rejected, "cancelled" to Res.string.vault_action_cancelled,
    "code-typed" to Res.string.vault_action_code_typed,
)

/** What the last real use showed. */
private val RESULTS = mapOf(
    "ok" to Res.string.vault_result_ok, "failed" to Res.string.vault_result_failed, "rejected" to Res.string.vault_result_rejected,
    "code_needed" to Res.string.vault_result_code_needed, "blocked" to Res.string.vault_result_blocked, "unknown" to Res.string.vault_result_unknown,
)

/** The vault's own sub pages. */
private sealed interface VaultView {
    data object List : VaultView
    data class Item(val name: String) : VaultView
    data class Ai(val name: String) : VaultView
    data object AddLogin : VaultView
    data object Log : VaultView
}

private fun VaultView.key(): String = when (this) {
    VaultView.List -> "list"
    is VaultView.Item -> "item:$name"
    is VaultView.Ai -> "ai:$name"
    VaultView.AddLogin -> "addLogin"
    VaultView.Log -> "log"
}

private fun viewOf(key: String): VaultView = when {
    key.startsWith("item:") -> VaultView.Item(key.removePrefix("item:"))
    key.startsWith("ai:") -> VaultView.Ai(key.removePrefix("ai:"))
    key == "addLogin" -> VaultView.AddLogin
    key == "log" -> VaultView.Log
    else -> VaultView.List
}

/**
 * Every password and key the assistant may use, what each is for, and the lock. Values can be added or replaced
 * here, never read. The window is kept out of screenshots and the recent-apps preview while it is open. [noLock]: the phone has
 * no screen lock, so it was opened without a check; the page says so.
 */
@Composable
fun VaultPage(onBack: () -> Unit, onGoModel: () -> Unit, noLock: Boolean = false) {
    LocalSecureWindow.current()
    val sessions = LocalAppServices.current.sessions
    val vm = rememberViewModel { VaultViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val info = ui.info
    val providers = ui.providers
    var viewKey by rememberSaveable { mutableStateOf(VaultView.List.key()) }
    fun go(v: VaultView) { vm.clearMessages(); viewKey = v.key() }
    val list = { go(VaultView.List) }
    val load = vm::load

    val v = info
    if (rememberSkeletonShown(v == null) || v == null) {
        SubPage(string(Res.string.vault_title), onBack) { MyCard { SkeletonLines(2) }; SkeletonRows(3) }
        return
    }
    // leaving a login or key that was started but never filled in: nothing stays behind in the vault
    fun leaveItem(name: String) { vm.leaveItem(name); list() }
    val stepBack: () -> Unit = {
        when (val now = viewOf(viewKey)) {
            is VaultView.Item -> leaveItem(now.name)
            else -> list()
        }
    }
    // the vault's own pages slide over its list; the back gesture steps back one page at a time
    PushHost(
        state = viewKey,
        depth = { if (viewOf(it) == VaultView.List) 0 else 1 },
        back = if (viewOf(viewKey) == VaultView.List) null else VaultView.List.key(),
        onBack = stepBack,
        modifier = Modifier.fillMaxSize(),
    ) { key ->
        when (val view = viewOf(key)) {
            VaultView.Log -> LogPage(vm, onBack = list)
            VaultView.AddLogin -> AddLogin(vm, onBack = list, onDone = { name -> go(VaultView.Item(name)) })
            is VaultView.Ai -> providers.find { it.name == view.name }?.let { p ->
                AiKeyPage(p, vm, onBack = list, onGoModel = onGoModel)
            } ?: VaultList(v, providers, noLock, vm, onBack, ::go)
            is VaultView.Item -> (v.items + v.pending).find { it.name == view.name }?.let { item ->
                ItemPage(item, vm, locked = v.locked, verifying = item.name in v.verifying, onBack = { leaveItem(item.name) })
            } ?: VaultList(v, providers, noLock, vm, onBack, ::go)
            VaultView.List -> VaultList(v, providers, noLock, vm, onBack, ::go)
        }
    }
}

@Composable
private fun VaultList(v: VaultState, providers: List<ProviderDetail>, noLock: Boolean, vm: VaultViewModel, onBack: () -> Unit, go: (VaultView) -> Unit) {
    val logins = v.items.filter { it.kind == VaultEntry.KIND_LOGIN }
    val mailboxes = v.items.filter { it.kind == VaultEntry.KIND_EMAIL }
    val keys = v.items.filter { it.kind == VaultEntry.KIND_API }
    val ai = providers.filter { it.keySource == "app" }
    val c = Kelo.colors
    SubPage(string(Res.string.vault_title), onBack) {
        if (noLock) MutedText(string(Res.string.vault_gate_no_lock), small = true, modifier = Modifier.padding(horizontal = 4.dp))
        MyCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTitle(KeloIcons.Shield, string(if (v.locked) Res.string.vault_locked_title else Res.string.vault_safe_title), Modifier.weight(1f))
                if (v.locked) PrimaryButton(string(Res.string.vault_unlock), onClick = vm::toggleLock, modifier = Modifier.weight(0.6f))
                else SecondaryButton(string(Res.string.vault_lock), onClick = vm::toggleLock)
            }
            MutedText(string(if (v.locked) Res.string.vault_locked_body else Res.string.vault_safe_body), small = true)
        }

        WaitingEntries(vm)

        SectionLabel(string(Res.string.vault_logins))
        MenuGroup { logins.forEach { i -> row { ItemRow(i, KeloIcons.Globe) { go(VaultView.Item(i.name)) } } } }
        MenuGroup { row { MenuRow(KeloIcons.Plus, string(Res.string.vault_add_login), onClick = { go(VaultView.AddLogin) }) } }
        MailCodesSwitch(v.mailCodesAuto, vm::setMailCodes)
        PaymentPinSection(v.locked)

        if (mailboxes.isNotEmpty()) {
            SectionLabel(string(Res.string.vault_mailboxes))
            MenuGroup { mailboxes.forEach { i -> row { ItemRow(i, KeloIcons.Key, i.label.split(" · ").first()) { go(VaultView.Item(i.name)) } } } }
        }

        if (keys.isNotEmpty()) {
            SectionLabel(string(Res.string.vault_keys))
            MutedText(string(Res.string.vault_keys_help), small = true, modifier = Modifier.padding(horizontal = 4.dp))
            MenuGroup { keys.forEach { i -> row { ItemRow(i, KeloIcons.Key) { go(VaultView.Item(i.name)) } } } }
        }

        if (ai.isNotEmpty()) {
            SectionLabel(string(Res.string.vault_ai_keys))
            MenuGroup {
                ai.forEach { p ->
                    row {
                        MenuRow(
                            KeloIcons.Key, p.displayName, onClick = { go(VaultView.Ai(p.name)) },
                            value = p.keyHint?.let { string(Res.string.vault_key_hint, it) } ?: string(Res.string.vault_stored),
                        )
                    }
                }
            }
        }

        SectionLabel(string(Res.string.vault_records))
        MenuGroup { row { MenuRow(KeloIcons.Shield, string(Res.string.vault_log), onClick = { go(VaultView.Log) }) } }
    }
}

/**
 * "입력 대기": the keys, logins and cards Kelo asked for that have no value yet (GET /api/secret-requests), newest first, at the top
 * of the Keychain. Each opens the same secure entry as in the chat, right here (saving it lets the agent go on); 삭제 cancels it.
 */
@Composable
private fun WaitingEntries(vm: VaultViewModel) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val waiting = chat.kelo.app.chat.vault.SecretRules.waiting(ui.waiting)
    if (waiting.isEmpty()) return
    val c = Kelo.colors
    SectionLabel(string(Res.string.secret_waiting_title))
    MutedText(string(Res.string.secret_waiting_help), small = true, modifier = Modifier.padding(horizontal = 4.dp))
    waiting.forEach { r ->
        MyCard {
            if (open == r.id) {
                chat.kelo.app.chat.vault.SecretEntryCard(
                    r, framed = false,
                    onSaved = { _, _ -> open = null; vm.waitingDone(r.id) },
                    onCancel = { open = null; vm.cancelWaiting(r) },
                    onGone = { open = null; vm.waitingDone(r.id) },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Icon(if (r.isCard) KeloIcons.Card else if (r.isLogin) KeloIcons.Globe else KeloIcons.Key,
                        contentDescription = null, tint = c.ink, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.label.ifEmpty { r.name }, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val kind = string(if (r.isCard) Res.string.secret_kind_card else if (r.isLogin) Res.string.secret_kind_login else Res.string.secret_kind_key)
                        val hosts = chat.kelo.app.chat.vault.SecretRules.hostsText(r.hosts)
                        Text(if (hosts.isEmpty()) kind else "$kind · $hosts", style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(string(Res.string.secret_fill), onClick = { open = r.id }, modifier = Modifier.weight(1f))
                    SecondaryButton(string(Res.string.delete), onClick = { vm.cancelWaiting(r) })
                }
            }
        }
    }
}

/** "Use codes from my mail automatically": on, a code the site mails during a sign-in Kelo started is typed without asking. */
@Composable
private fun MailCodesSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Kelo.colors
    val title = string(Res.string.vault_mail_codes_title)
    MyCard {
        Row(
            Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(
                checked = on, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = title },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
        MutedText(string(if (on) Res.string.vault_mail_codes_on else Res.string.vault_mail_codes_off), small = true)
    }
}

/** A vault row; every item listed holds a value (requests without one are not shown). */
@Composable
private fun ItemRow(i: VaultEntry, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String = i.label, onClick: () -> Unit) {
    val c = Kelo.colors
    val stored = i.usernameHint ?: string(Res.string.vault_stored)
    when {
        i.isBad -> MenuRow(
            icon, label, onClick,
            value = string(if (i.isLogin) Res.string.vault_status_login_failed else Res.string.vault_status_key_rejected),
            valueColor = c.danger,
        )
        i.lastResult == "ok" -> MenuRow(icon, label, onClick, value = string(Res.string.vault_status_ok, stored))
        else -> MenuRow(icon, label, onClick, value = stored)
    }
}

/** One login or key: where it can be used, how it last went, how a login is allowed, and replacing or removing it. */
@Composable
private fun ItemPage(item: VaultEntry, vm: VaultViewModel, locked: Boolean, verifying: Boolean, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val bad = item.isBad
    val signedIn = item.signedIn
    var open by rememberSaveable(item.name) { mutableStateOf(!item.hasSecret || bad) }
    var confirm by remember { mutableStateOf(false) }
    val c = Kelo.colors
    val onChanged = vm::load
    fun patch(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = vm.patchItem(item.name, build)

    SubPage(item.label, onBack) {
        MyCard {
            val kind = string(
                when {
                    item.isLogin -> Res.string.vault_kind_login
                    item.kind == VaultEntry.KIND_EMAIL -> Res.string.vault_kind_email
                    signedIn -> Res.string.vault_kind_signed_in
                    else -> Res.string.vault_kind_key
                },
            )
            Text(
                chat.kelo.app.ui.components.emphasized(string(Res.string.vault_kind_hosts, kind), item.hosts.joinToString(", "), code = true),
                color = c.ink,
            )
            val parts = mutableListOf<String>()
            parts += if (item.hasSecret) {
                val s = string(if (bad) Res.string.vault_saved_plain else Res.string.vault_saved_ok)
                if (item.usernameHint != null) string(Res.string.vault_join, s, item.usernameHint!!) else s
            } else string(Res.string.vault_not_entered)
            parts += string(Res.string.vault_last_used, whenText(item.lastUsed))
            if (item.kind == VaultEntry.KIND_API && !signedIn) {
                val p = item.placement
                val place = when (p.type) {
                    "custom_header" -> string(Res.string.vault_place_header_named, p.header.orEmpty())
                    "query_param" -> string(Res.string.vault_place_query_named, p.param.orEmpty())
                    else -> PLACES.firstOrNull { it.first == p.type }?.let { string(it.second) }.orEmpty()
                }
                parts += string(Res.string.vault_place, place)
            }
            if (item.endpoint.isNotEmpty()) parts += string(Res.string.vault_mcp, item.endpoint)
            MutedText(parts.joinToString(string(Res.string.usage_group_joiner)), small = true)
            if (locked) ErrorText(string(Res.string.vault_item_locked), small = true)
            if (verifying) Text(string(Res.string.vault_verifying), style = MaterialTheme.typography.bodySmall, color = c.ink)
            val result = item.lastResult
            if (result != null) {
                val words = RESULTS[result]?.let { string(it) } ?: result
                val line = buildList {
                    add(words); add(whenText(item.lastResultAt))
                    if (item.kind == VaultEntry.KIND_API && item.lastResultDetail.isNotEmpty()) add(item.lastResultDetail)
                }.joinToString(string(Res.string.usage_group_joiner))
                if (bad) ErrorText(line, small = true) else MutedText(line, small = true)
            } else if (item.hasSecret) {
                MutedText(string(Res.string.vault_not_used_yet), small = true)
            }
            if (bad && !signedIn) {
                ErrorText(string(if (item.isLogin) Res.string.vault_bad_login else Res.string.vault_bad_key), small = true)
                LinkButton(string(Res.string.vault_try_again), onClick = { patch { put("clear_result", true) } })
            }
        }
        if (item.isLogin) {
            MyCard {
                Text(string(Res.string.vault_when_login), style = MaterialTheme.typography.titleSmall, color = c.ink)
                Segmented(
                    listOf(string(Res.string.vault_ask_every_time), string(Res.string.vault_without_asking)),
                    selected = if (item.autoAllow) 1 else 0,
                    onSelect = { i -> patch { put("auto_allow", i == 1) } },
                )
                MutedText(string(if (item.autoAllow) Res.string.vault_auto_allow_on else Res.string.vault_auto_allow_off), small = true)
            }
            if (item.scheduleGrants.isNotEmpty()) {
                MyCard {
                    Text(string(Res.string.grant_login_section), style = MaterialTheme.typography.titleSmall, color = c.ink)
                    chat.kelo.app.agent.ScheduleGrantRows(item.scheduleGrants, showSchedule = true, onRevoke = vm::revokeGrant)
                    MutedText(string(Res.string.grant_section_note), small = true)
                }
            }
        }
        MyCard {
            when {
                signedIn -> MutedText(
                    (if (bad) string(Res.string.vault_disconnected) else "") + string(Res.string.vault_reconnect_in_connectors),
                    small = true,
                )
                open -> SecureEntryForm(item.asItem(), onSaved = { open = false; onChanged() })
                else -> SecondaryButton(
                    string(if (item.isLogin) Res.string.vault_change_login else Res.string.vault_change_key),
                    onClick = { open = true }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        MyCard { LinkButton(string(Res.string.vault_remove), onClick = { confirm = true }, danger = true) }
        if (ui.itemError.isNotEmpty()) ErrorText(ui.itemError, small = true)
    }
    if (confirm) {
        ConfirmDialog(
            text = string(Res.string.vault_remove_confirm, item.label), yes = string(Res.string.delete),
            onDismiss = { confirm = false },
            onYes = { vm.deleteItem(item.name, onGone = onBack) },
        )
    }
}

/** The sign-in page's address and a name; the next page takes the username and password. */
@Composable
private fun AddLogin(vm: VaultViewModel, onDone: (String) -> Unit, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var site by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    SubPage(string(Res.string.vault_add_login), onBack) {
        MyCard {
            FieldLabel(string(Res.string.vault_login_site))
            KeloTextField(
                site, { site = it }, string(Res.string.vault_login_site_placeholder),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            )
            FieldLabel(string(Res.string.vault_login_name))
            KeloTextField(label, { label = it }, string(Res.string.vault_login_name_placeholder), maxLength = 80)
            MutedText(string(Res.string.vault_login_site_help), small = true)
            if (ui.addLoginError.isNotEmpty()) ErrorText(ui.addLoginError, small = true)
            PrimaryButton(string(Res.string.onboarding_next), onClick = { vm.addLogin(site, label, onDone) }, enabled = site.isNotBlank())
        }
    }
}

/** The key of an AI service. Changing or removing it happens here; choosing which model answers is on the model page. */
@Composable
private fun AiKeyPage(p: ProviderDetail, vm: VaultViewModel, onBack: () -> Unit, onGoModel: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val msg = if (ui.aiSaved) string(Res.string.operator_saved) else ui.aiMessage
    SubPage(p.displayName, onBack) {
        MyCard {
            Text(string(Res.string.vault_ai_key_title), color = Kelo.colors.ink)
            MutedText(
                if (p.keyHint != null) string(Res.string.vault_ai_key_saved_hint, p.keyHint!!) else string(Res.string.vault_ai_key_saved),
                small = true,
            )
            MutedText(string(Res.string.vault_ai_key_note), small = true)
        }
        MyCard {
            FieldLabel(string(Res.string.model_key_replace))
            KeloTextField(
                key, { key = it }, string(Res.string.model_key_paste), password = true, maxLength = 512,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            )
            PrimaryButton(string(Res.string.save), onClick = { vm.saveAiKey(p.name, key) { key = "" } }, enabled = key.trim().length >= 8)
            if (msg.isNotEmpty()) MutedText(msg, small = true)
        }
        MyCard { LinkButton(string(Res.string.model_key_delete), onClick = { confirm = true }, danger = true) }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MutedText(string(Res.string.vault_ai_model_choice), small = true, modifier = Modifier.weight(1f))
            LinkButton(string(Res.string.chat_model_settings), onClick = onGoModel)
        }
    }
    if (confirm) {
        ConfirmDialog(
            text = string(Res.string.vault_ai_key_delete_confirm, p.displayName), yes = string(Res.string.delete),
            onDismiss = { confirm = false },
            onYes = { vm.deleteAiKey(p.name, onGone = onBack) },
        )
    }
}

/** What happened in the vault. The agent can neither read nor erase it. */
@Composable
private fun LogPage(vm: VaultViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadLog() }
    val rows = ui.log
    val c = Kelo.colors
    SubPage(string(Res.string.vault_log), onBack) {
        MutedText(string(Res.string.vault_log_help), small = true)
        val list = rows
        if (list != null && list.isEmpty()) EmptyNote(string(Res.string.vault_log_empty))
        if (!list.isNullOrEmpty()) {
            MenuGroup {
                list.forEach { r ->
                    row {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Badge(ACTIONS[r.action]?.let { string(it) } ?: r.action, denied = !r.ok)
                            Column(Modifier.weight(1f)) {
                                Text(r.item, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (r.detail.isNotEmpty()) MutedText(r.detail, small = true)
                            }
                            MutedText(whenText(r.ts), small = true)
                        }
                    }
                }
            }
        }
    }
}
