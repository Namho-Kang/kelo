package chat.kelo.desktop.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.CardCountdown
import chat.kelo.app.chat.PinNeed
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay

// The desktop's own card for a payment PIN: only the phone that saved it can release it, so this computer says where to do it.
// (The phone's PinNeedCard has a "send" button; the payment step card itself is the shared one.)

@Composable
private fun rememberNowS(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/**
 * A payment's PIN waits for the phone that saved it: only that phone can release it (with fingerprint or face), so this computer
 * says where to do it and how long is left; once the time is over, one line says so.
 */
@Composable
fun PinNeedCard(need: PinNeed, nowS: Long? = null) {
    val c = Kelo.colors
    val now = nowS ?: rememberNowS()
    val left = (need.expiresAt - now).coerceAtLeast(0)
    CardBox(Modifier.animateContentSize()) {
        if (left <= 0L) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.Clock, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(string(Res.string.pin_need_over, need.site), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        } else {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.Phone, contentDescription = null, tint = c.accent, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Text(string(Res.string.pin_need_title), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            }
            Text(string(Res.string.desktop_pin_need_body, need.pinLabel.ifEmpty { need.site }, need.site, need.amountText),
                style = MaterialTheme.typography.bodyMedium, color = c.ink)
            MutedText(string(Res.string.chat_card_needed_left, CardCountdown.text(left)), small = true)
        }
    }
}

private const val PAD_REFRESH_MS = 700L
