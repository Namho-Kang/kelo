package chat.kelo.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.vault.CardBox
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/** The server's line after `save_found_secret` (connect_tools.py) as a card's facts; pure, so it is tested. */
object KeySavedRules {
    data class Saved(val name: String, val hosts: List<String>)

    private val LINE = Regex("^Saved the key for '([^']+)' in the keychain \\(hosts: ([^)]*)\\)")

    fun parse(output: String): Saved? {
        val m = LINE.find(output.trim()) ?: return null
        return Saved(m.groupValues[1], m.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
}

/** "Saved in the Keychain": the key's name, where it is used, and the way to the Keychain (behind its own lock). */
@Composable
fun KeySavedCard(item: ChatItem.KeySaved) {
    val c = Kelo.colors
    val services = LocalAppServices.current
    CardBox {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KeloIcons.Key, contentDescription = null, tint = c.accent)
                Text(string(Res.string.keysaved_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            if (item.hosts.isNotEmpty()) {
                Text(string(Res.string.keysaved_hosts, item.hosts.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            SecondaryButton(string(Res.string.keysaved_open), onClick = { services.openRequests.value = OpenTarget.Keychain },
                modifier = Modifier.fillMaxWidth())
        }
    }
}
