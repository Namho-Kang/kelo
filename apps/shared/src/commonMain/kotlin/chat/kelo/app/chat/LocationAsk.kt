package chat.kelo.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.platform.LocationAccess
import chat.kelo.app.platform.PhoneLocation
import chat.kelo.app.platform.SettingsPage
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalAppLifecycle
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** What the location card does for each tap (pure, so it is tested). */
object LocationAskRules {
    enum class Step { SEND, ASK_SYSTEM, OPEN_SETTINGS, TURN_ON_LOCATION }

    /**
     * "Share my location": the phone's own location switch first ([servicesOn] false: nothing else helps, its settings do); then with
     * the permission, read and send; without it, the system prompt, unless the server says the owner refused before ([settings]) or the
     * system no longer shows the prompt ([blocked]: asked before and no rationale): then the phone's settings for Kelo.
     */
    fun onShare(granted: Boolean, settings: Boolean, blocked: Boolean, servicesOn: Boolean = true): Step = when {
        !servicesOn -> Step.TURN_ON_LOCATION
        granted -> Step.SEND
        settings || blocked -> Step.OPEN_SETTINGS
        else -> Step.ASK_SYSTEM
    }

    /** The one line an ended card leaves, or null while it is open. */
    fun endLine(state: String): StringResource? = when (state) {
        ChatItem.LocationAsk.ALLOWED -> Res.string.location_ask_shared
        ChatItem.LocationAsk.REFUSED -> Res.string.location_ask_refused
        ChatItem.LocationAsk.WAITING, ChatItem.LocationAsk.SENDING -> null
        else -> Res.string.location_ask_expired
    }
}

/**
 * The reply waits for this phone's place (LOCATION REQUEST): why (the agent's words), "Share my location" and "Not now"; when the
 * owner refused before, the button opens Kelo's settings instead of the system prompt, and coming back with the permission sends it.
 * The place goes only to the owner's own server ([PhoneLocation.read]: position, accuracy, town, country, neighbourhood). A device
 * that cannot read a place (the desktop) says so, points to the phone and offers "Not now".
 */
@Composable
fun LocationAskCard(item: ChatItem.LocationAsk, vm: ChatViewModel) {
    val c = Kelo.colors
    val services = LocalAppServices.current
    val phone = services.phoneLocation
    val scope = rememberCoroutineScope()
    var settingsOpened by remember(item.approvalId) { mutableStateOf(false) }
    var checked by remember(item.approvalId) { mutableStateOf(0) }
    var unavailable by remember(item.approvalId) { mutableStateOf(false) }

    fun send(place: PhoneLocation) {
        vm.locationReading(item.approvalId)
        scope.launch {
            val read = runCatching { place.read() }.getOrNull()
            unavailable = read == null
            vm.answerLocation(item.approvalId, read)
        }
    }

    // back from the settings page: allowed there -> send at once
    LocalAppLifecycle.current.OnEvents(
        onResume = {
            if (phone != null && settingsOpened && item.state == ChatItem.LocationAsk.WAITING) {
                settingsOpened = false
                checked++
                if (phone.servicesOn() && phone.granted()) send(phone)
            }
        },
        onStop = {},
    )

    CardBox {
        val end = LocationAskRules.endLine(item.state)
        if (end != null) {
            ResultLine(if (item.state == ChatItem.LocationAsk.ALLOWED) KeloIcons.Check else KeloIcons.Close, string(end),
                muted = item.state != ChatItem.LocationAsk.ALLOWED)
            if (item.state == ChatItem.LocationAsk.REFUSED && unavailable) {
                Text(string(Res.string.location_ask_unavailable), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@CardBox
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.MapPin, contentDescription = null, tint = c.accent)
                Text(string(Res.string.location_ask_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            }
            if (phone == null) {
                // this computer cannot read a place (the phone app shares it)
                if (item.reason.isNotBlank()) Text(item.reason, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                Text(string(Res.string.desktop_location_on_phone), style = MaterialTheme.typography.bodySmall, color = c.muted)
                SecondaryButton(string(Res.string.location_ask_not_now), enabled = item.state == ChatItem.LocationAsk.WAITING,
                    onClick = { vm.answerLocation(item.approvalId, null) }, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            Text(item.reason.ifBlank { string(Res.string.location_ask_body) }, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Text(string(Res.string.location_ask_private), style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (item.state == ChatItem.LocationAsk.SENDING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                    Text(string(Res.string.location_ask_reading), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                return@Column
            }
            val step = checked.let { LocationAskRules.onShare(phone.granted(), item.settings, phone.blocked(), phone.servicesOn()) }
            if (step == LocationAskRules.Step.OPEN_SETTINGS) {
                Text(string(Res.string.location_ask_settings_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
            } else if (step == LocationAskRules.Step.TURN_ON_LOCATION) {
                Text(string(Res.string.location_services_off), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            // one under the other, so long words in any language fit at 320 dp
            PrimaryButton(
                string(if (step == LocationAskRules.Step.OPEN_SETTINGS || step == LocationAskRules.Step.TURN_ON_LOCATION) Res.string.location_ask_settings else Res.string.location_ask_share),
                onClick = {
                    when (step) {
                        LocationAskRules.Step.SEND -> send(phone)
                        LocationAskRules.Step.ASK_SYSTEM -> scope.launch {
                            if (phone.request() == LocationAccess.GRANTED) send(phone) else vm.answerLocation(item.approvalId, null)
                        }
                        LocationAskRules.Step.OPEN_SETTINGS -> { settingsOpened = true; services.systemSettings.open(SettingsPage.APP_DETAILS) }
                        LocationAskRules.Step.TURN_ON_LOCATION -> { settingsOpened = true; services.systemSettings.open(SettingsPage.LOCATION) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(string(Res.string.location_ask_not_now), onClick = { vm.answerLocation(item.approvalId, null) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
