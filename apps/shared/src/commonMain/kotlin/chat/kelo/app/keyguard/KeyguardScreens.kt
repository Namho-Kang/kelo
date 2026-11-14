package chat.kelo.app.keyguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.ui.text.*
import chat.kelo.app.ui.theme.Kelo
import org.jetbrains.compose.resources.StringResource

internal val KeyguardProblem.text: StringResource
    get() = when (this) {
        KeyguardProblem.NO_LOCK -> Res.string.keyguard_no_lock
        KeyguardProblem.KEY_LOST -> Res.string.keyguard_key_lost
        KeyguardProblem.FAILED -> Res.string.keyguard_failed
        KeyguardProblem.LOCKED_OTHER -> Res.string.keyguard_locked_other
        KeyguardProblem.ENV_KEY -> Res.string.keyguard_env_key
        KeyguardProblem.LAST_PHONE -> Res.string.keyguard_last_phone
        KeyguardProblem.NOT_OWNER -> Res.string.keyguard_not_owner
        KeyguardProblem.CODE_WRONG -> Res.string.keyguard_code_wrong
    }

@Composable
private fun rememberKeyguardPrompts(): KeyguardPrompts {
    val wrap = string(Res.string.keyguard_prompt_wrap)
    val title = string(Res.string.keyguard_prompt_title)
    val subtitle = string(Res.string.keyguard_prompt_subtitle)
    return remember(wrap, title, subtitle) { KeyguardPrompts(wrap, title, subtitle) }
}

/**
 * 마이 → Security → Keyguard. [startWithCode]: opened from the locked banner, at the recovery code. [enableNow]: the enable flow (auto-pay
 * asked for it), the "turn it on?" question first; [onEnabled] once it is on.
 */
@Composable
fun KeyguardPage(onBack: () -> Unit, startWithCode: Boolean = false, enableNow: Boolean = false, onEnabled: () -> Unit = {}) {
    val services = LocalAppServices.current
    val keys = services.keyguardKeys
    val vm = rememberViewModel { KeyguardViewModel(services.sessions, keys, startWithCode, enableNow) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val prompts = rememberKeyguardPrompts()
    val enabledNow by rememberUpdatedState(onEnabled)
    LaunchedEffect(vm) { vm.enabled.collect { enabledNow() } }
    val c = Kelo.colors

    ui.recoveryCode?.let { rc ->
        RecoveryCodePage(rc, onDone = vm::recoveryDone)
        return
    }

    SubPage(string(Res.string.keyguard_title), onBack) {
        val v = ui.view
        MyCard {
            MutedText(string(Res.string.keyguard_intro), small = false)
            MutedText(string(Res.string.keyguard_restart_note), small = true)
            if (!keys.checkEveryUse) MutedText(string(Res.string.keyguard_window_note), small = true)
        }
        when {
            ui.notOwner -> MyCard { MutedText(string(Res.string.keyguard_not_owner), small = true) }
            v == null -> {}
            !v.on -> MyCard {
                if (keys.available) {
                    PrimaryButton(string(if (ui.busy) Res.string.keyguard_turning_on else Res.string.keyguard_turn_on),
                        onClick = { vm.ask(KeyguardConfirm.TURN_ON) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                } else {
                    MutedText(string(Res.string.keyguard_on_phone_only), small = true)
                }
            }
            else -> {
                if (v.locked) MyCard {
                    Text(string(Res.string.keyguard_locked_title), fontWeight = FontWeight.Bold, color = c.ink)
                    if (keys.available && v.holds(ui.me)) {
                        PrimaryButton(string(if (ui.busy) Res.string.keyguard_unlocking else Res.string.keyguard_unlock),
                            onClick = { vm.unlock(prompts) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                    } else {
                        MutedText(string(if (keys.available) Res.string.keyguard_locked_other else Res.string.desktop_keyguard_locked_body), small = true)
                    }
                }
                MyCard {
                    Text(string(Res.string.keyguard_phones_title), fontWeight = FontWeight.Bold, color = c.ink)
                    v.phones.forEach { p ->
                        Text(if (p.deviceId == ui.me) string(Res.string.keyguard_this_phone, p.name) else p.name, color = c.ink,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (keys.available && !v.locked && !v.holds(ui.me)) SecondaryButton(string(Res.string.keyguard_add_this_phone),
                        onClick = { vm.enroll(prompts) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                    if (v.holds(ui.me)) SecondaryButton(string(Res.string.keyguard_remove_this_phone),
                        onClick = { vm.ask(KeyguardConfirm.REMOVE) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                    if (!v.locked) SecondaryButton(string(Res.string.keyguard_turn_off),
                        onClick = { vm.ask(KeyguardConfirm.TURN_OFF) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (v?.on == true || ui.codeOpen) MyCard {
            if (!ui.codeOpen) {
                SecondaryButton(string(Res.string.keyguard_use_code), onClick = vm::openCode, modifier = Modifier.fillMaxWidth())
            } else {
                Text(string(Res.string.keyguard_use_code), fontWeight = FontWeight.Bold, color = c.ink)
                KeloTextField(ui.code, vm::typeCode, placeholder = string(Res.string.keyguard_code_hint), modifier = Modifier.fillMaxWidth(),
                    // Password type opens a Latin keyboard even when the phone types Korean/Japanese; the text stays visible.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password))
                PrimaryButton(string(Res.string.keyguard_unlock), onClick = vm::recover,
                    enabled = !ui.busy && KeyguardRules.codeReady(ui.code), modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.unlocked) MutedText(string(Res.string.keyguard_unlocked), small = true)
        ui.problem?.let { ErrorText(string(it.text), small = true) } ?: ui.message?.let { ErrorText(it, small = true) }
    }

    ui.confirm?.let { which ->
        val (text, yes) = when (which) {
            KeyguardConfirm.TURN_ON -> Res.string.keyguard_turn_on_confirm to Res.string.keyguard_turn_on
            KeyguardConfirm.REMOVE -> Res.string.keyguard_remove_confirm to Res.string.keyguard_remove_this_phone
            KeyguardConfirm.TURN_OFF -> Res.string.keyguard_turn_off_confirm to Res.string.keyguard_turn_off
        }
        ConfirmDialog(string(text), string(yes), onYes = { vm.confirmed(prompts) }, onDismiss = vm::dismissConfirm,
            danger = which != KeyguardConfirm.TURN_ON)
    }
}

/** The recovery code, once: nothing else on the page (and no way back) until "I wrote it down". */
@Composable
private fun RecoveryCodePage(code: String, onDone: () -> Unit) {
    val c = Kelo.colors
    var wroteDown by remember { mutableStateOf(false) }
    SubPage(string(Res.string.keyguard_recovery_title), onBack = {}) {
        MyCard {
            Text(string(Res.string.keyguard_recovery_warning), color = c.ink, style = MaterialTheme.typography.bodyMedium)
            SelectionContainer {
                Text(KeyguardRules.codeLines(code), fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = c.ink, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            // the whole line ticks the box, not just the box itself
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .toggleable(value = wroteDown, role = Role.Checkbox, onValueChange = { wroteDown = it }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(wroteDown, null, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                Text(string(Res.string.keyguard_recovery_confirm), color = c.ink, style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryButton(string(Res.string.keyguard_done), onClick = onDone, enabled = wroteDown, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * "Kelo is locked" under the header while the server holds no key (423, agent status "locked", the keyguard_locked notification). A
 * device with a copy unlocks here; every device can go to the recovery code ([onRecover]). [autoStart]: opened from the notification,
 * the check starts by itself once. The caller shows it only while locked.
 */
@Composable
fun LockedBanner(onRecover: () -> Unit, modifier: Modifier = Modifier, autoStart: Boolean = false, onAutoStarted: () -> Unit = {}) {
    val services = LocalAppServices.current
    val keys = services.keyguardKeys
    val vm = rememberViewModel { LockedBannerViewModel(services.sessions, keys) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val prompts = rememberKeyguardPrompts()
    LaunchedEffect(autoStart) { if (autoStart && keys.available) { onAutoStarted(); vm.unlock(prompts) } }
    val c = Kelo.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        // floats over the chat like the other cards under the header: a shadow and a line so it never reads as part of a message
        modifier.fillMaxWidth().shadow(8.dp, shape).clip(shape).background(c.card).border(1.dp, c.line, shape).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(KeloIcons.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            Text(string(Res.string.keyguard_locked_title), style = MaterialTheme.typography.titleMedium, color = c.ink,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        val other = !keys.available || ui.problem == KeyguardProblem.LOCKED_OTHER
        MutedText(string(when {
            !keys.available -> Res.string.desktop_keyguard_locked_body
            other -> Res.string.keyguard_locked_other
            else -> Res.string.keyguard_locked_body
        }), small = true)
        ui.problem?.takeIf { !other }?.let { ErrorText(string(it.text), small = true) }
        if (!other) PrimaryButton(string(if (ui.busy) Res.string.keyguard_unlocking else Res.string.keyguard_unlock),
            onClick = { vm.unlock(prompts) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
        SecondaryButton(string(Res.string.keyguard_use_code), onRecover, modifier = Modifier.fillMaxWidth())
    }
}

/** Whether Keyguard is on for this server (null = not known yet) and a way to ask again. */
class KeyguardState(val on: Boolean?, val refresh: () -> Unit)

/** GET /api/keyguard's "on", asked again by [KeyguardState.refresh]. */
@Composable
fun rememberKeyguardOn(): KeyguardState {
    val sessions = LocalAppServices.current.sessions
    var on by remember { mutableStateOf<Boolean?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { on = runCatching { KeyguardCalls.view(sessions.requireApi()).on }.getOrNull() }
    return remember(on) { KeyguardState(on) { tick++ } }
}

/**
 * Where the enable flow opens over the whole app: a layer the shell draws above every page (not a dialog window, so the phone's
 * status bar keeps the app's look). The page that asked keeps its state underneath.
 */
class KeyguardEnableHost {
    class Request(val onEnabled: () -> Unit, val onClose: () -> Unit)
    var request by mutableStateOf<Request?>(null)
}

val LocalKeyguardEnableHost = staticCompositionLocalOf<KeyguardEnableHost?> { null }

/** The enable flow over the app while [host] has a request; back (the page's own) closes it, finishing runs the request's onEnabled. */
@Composable
fun KeyguardEnableOverlay(host: KeyguardEnableHost) {
    val r = host.request ?: return
    val c = Kelo.colors
    Box(
        Modifier.fillMaxSize().background(c.bg)
            // touches stop here: nothing under the page reacts
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        KeyguardPage(onBack = { host.request = null; r.onClose() }, enableNow = true, onEnabled = { host.request = null; r.onEnabled() })
    }
}

/**
 * "Auto-pay needs Keyguard" (owner's decision). A device that can hold the key: "Turn on Keyguard" opens the enable flow and, once it
 * is on, [onEnabled] goes on with what was asked. A device that cannot (the desktop): it only says so, with Close.
 */
@Composable
fun KeyguardNeededDialog(onEnabled: () -> Unit, onDismiss: () -> Unit) {
    val c = Kelo.colors
    val host = LocalKeyguardEnableHost.current
    val canTurnOn = LocalAppServices.current.keyguardKeys.available
    var enabling by remember { mutableStateOf(false) }
    if (enabling) {
        if (host != null) {
            LaunchedEffect(host) {
                host.request = KeyguardEnableHost.Request(onEnabled = { enabling = false; onEnabled() }, onClose = { enabling = false; onDismiss() })
            }
            return
        }
        // no host (outside the main shell): a full-window dialog
        Dialog(onDismissRequest = { enabling = false; onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(c.bg)) {
                KeyguardPage(onBack = { enabling = false; onDismiss() }, enableNow = true, onEnabled = { enabling = false; onEnabled() })
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(string(Res.string.wallet_keyguard_needed_title), color = c.ink) },
        text = { Text(string(Res.string.wallet_keyguard_needed_body), color = c.ink, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (canTurnOn) TextButton(onClick = { enabling = true }) { Text(string(Res.string.keyguard_turn_on_named), color = c.accent) }
            else TextButton(onClick = onDismiss) { Text(string(Res.string.close), color = c.accent) }
        },
        dismissButton = if (canTurnOn) ({ TextButton(onClick = onDismiss) { Text(string(Res.string.cancel), color = c.ink) } }) else null,
    )
}
