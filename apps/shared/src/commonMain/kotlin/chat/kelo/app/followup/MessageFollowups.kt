package chat.kelo.app.followup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.ActionOutcome
import chat.kelo.app.chat.ChatReducer
import chat.kelo.app.chat.ChatState
import chat.kelo.app.chat.ChatViewModel
import chat.kelo.app.chat.ResultLine
import chat.kelo.app.chat.vault.SecureCard
import chat.kelo.app.chat.vault.SecureCardViewModel
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.KeloModal
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.ui.text.*
import chat.kelo.app.ui.theme.Kelo

/**
 * A follow-up's buttons under the agent's message (sign in, allow, go on, ask later, stop asking); once one was used, one line
 * says what happened. 로그인하기 opens the sign-in sheet here.
 */
@Composable
fun MessageFollowups(f: Followup, state: ChatState, vm: ChatViewModel) {
    val haptics = LocalHaptics.current
    var login by remember(f.id) { mutableStateOf<String?>(null) }
    val outcome = ChatReducer.closedOutcome(f, state.outcomes[f.id])
    if (outcome != null) {
        ResultLine(if (outcome == ActionOutcome.GONE) KeloIcons.Clock else KeloIcons.Check, outcomeText(outcome), muted = outcome == ActionOutcome.GONE)
        return
    }
    val buttons = remember(f.actions, f.id) { FollowupRules.buttons(f.actions, f.id) }
    FollowupButtons(buttons, busy = f.id in state.acting, onPress = { step ->
        haptics.tick()
        if (step is FollowupStep.OpenLogin) login = step.name else vm.runAction(f, step)
    })
    login?.let { name ->
        SignInSheet(name, onNotify = { text -> vm.actionSignedIn(f); vm.send(text) }, onClose = { login = null })
    }
}

/**
 * The sign-in sheet for one Kelo keychain item, opened from a follow-up button: the same secure form the chat shows (a bottom sheet
 * on the phone, a dialog on the desktop). When it is filled in, [onNotify] gets the line that tells the agent.
 */
@Composable
fun SignInSheet(name: String, onNotify: (String) -> Unit, onClose: () -> Unit) {
    val c = Kelo.colors
    val sessions = LocalAppServices.current.sessions
    val vm = rememberViewModel("signin-$name") { SecureCardViewModel(sessions, name) }
    val looked by vm.state.collectAsStateWithLifecycle()
    val state = when { !looked.looked -> 0; looked.item != null -> 1; else -> 2 }   // 0 looking, 1 found, 2 gone
    KeloModal(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.Shield, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
                Text(string(Res.string.signin_sheet_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
            when (state) {
                0 -> CircularProgressIndicator(Modifier.size(22.dp), color = c.muted, strokeWidth = 2.dp)
                1 -> SecureCard(name, onNotify = { text -> onNotify(text); onClose() })
                else -> MutedText(string(Res.string.signin_sheet_gone), small = true)
            }
        }
    }
}
