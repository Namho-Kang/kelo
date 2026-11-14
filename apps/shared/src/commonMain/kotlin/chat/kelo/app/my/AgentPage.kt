package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.agent.AgentPicture
import chat.kelo.app.agent.RenameDialog
import chat.kelo.app.chat.AgentActivityRules
import chat.kelo.app.chat.AttachmentTooLarge
import chat.kelo.app.chat.AttachmentUnreadable
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.rememberViewModel
import chat.kelo.app.platform.LocalToaster
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 마이 → 에이전트: the agent's picture (pick one from the phone, or go back to Kelo's character), its name and its tone (친근 / 공손, each
 * with an example sentence; [ToneRules]) as settings rows, and what it is doing right now. The profile is read again when the page
 * opens, so a tone the agent changed in the chat shows as chosen.
 */
@Composable
fun AgentPage(profile: Profile, onProfile: (Profile) -> Unit, onBack: () -> Unit) {
    val c = Kelo.colors
    val services = LocalAppServices.current
    val vm = rememberViewModel("my-agent") { AgentPageViewModel(services.sessions, services.agentAvatar, services.filePicker) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHaptics.current
    val toaster = LocalToaster.current
    val image by services.agentAvatar.image.collectAsStateWithLifecycle()
    val status by services.agentActivity.status.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val working = ui.working
    val savingTone = ui.savingTone
    val failedText = string(Res.string.agent_avatar_failed)
    val tooLarge = string(Res.string.agent_avatar_too_large)
    val toneFailed = string(Res.string.my_agent_tone_failed)
    LaunchedEffect(vm) {
        vm.notices.collect { n ->
            when (n) {
                AgentNotice.Done -> haptics.confirm()
                AgentNotice.AvatarFailed -> toaster.show(failedText)
                AgentNotice.AvatarTooLarge -> toaster.show(tooLarge)
                AgentNotice.ToneFailed -> toaster.show(toneFailed)
                is AgentNotice.Server -> toaster.show(n.text)
            }
        }
    }
    LaunchedEffect(vm) { vm.reload(onProfile) }
    fun pickTone(tone: String) = vm.pickTone(profile, tone, onProfile)

    SubPage(string(Res.string.my_agent_title), onBack) {
        SectionLabel(string(Res.string.my_agent_picture))
        MyCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    AgentPicture(profile.agentName, image, 56)
                    if (working) CircularProgressIndicator(Modifier.size(56.dp), color = c.accent, strokeWidth = 2.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MutedText(string(if (image != null) Res.string.my_agent_picture_hint else Res.string.my_agent_picture_default), small = true)
                    if (image != null) LinkButton(string(Res.string.agent_avatar_remove), onClick = { confirmRemove = true }, danger = true)
                }
            }
            SecondaryButton(
                string(Res.string.agent_avatar_pick), enabled = !working,
                onClick = vm::pickPicture,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionLabel(string(Res.string.my_agent_identity))
        MenuGroup {
            row { MenuRow(KeloIcons.Edit, string(Res.string.profile_agent_name), { rename = true }, value = profile.agentName) }
        }
        SectionLabel(string(Res.string.my_agent_tone))
        val shownTone = savingTone ?: ToneRules.selected(profile)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            ToneRow(string(Res.string.my_agent_tone_friendly), string(Res.string.my_agent_tone_friendly_example),
                selected = shownTone == ToneRules.FRIENDLY, enabled = savingTone == null) { pickTone(ToneRules.FRIENDLY) }
            ToneRow(string(Res.string.my_agent_tone_polite), string(Res.string.my_agent_tone_polite_example),
                selected = shownTone == ToneRules.POLITE, enabled = savingTone == null) { pickTone(ToneRules.POLITE) }
        }
        SectionLabel(string(Res.string.my_agent_now))
        MyCard {
            val line = status?.let { AgentActivityRules.line(it) }
            Text(line ?: string(Res.string.agent_idle), style = MaterialTheme.typography.bodyMedium, color = if (line != null) c.ink else c.muted)
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            text = string(Res.string.agent_avatar_remove_confirm), yes = string(Res.string.agent_avatar_remove_yes),
            onDismiss = { confirmRemove = false },
            onYes = vm::removePicture,
        )
    }
    if (rename) RenameDialog(profile, onProfile, onClose = { rename = false })
}

/** One tone: a radio, its name and, under it, an example sentence in that tone (wraps in any language). */
@Composable
private fun ToneRow(label: String, example: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.muted))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            Text(example, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}
