package chat.kelo.app.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/** A status badge: its colours, light and dark. */
@Composable
fun StatusBadge(badge: Badge?, modifier: Modifier = Modifier) {
    if (badge == null) return
    val c = Kelo.colors
    val (bg, fg) = when (badge) {
        Badge.CONNECTED -> c.successSoft to c.successInk
        Badge.AVAILABLE -> c.infoSoft to c.infoInk
        Badge.BROWSER -> c.neutralSoft to c.neutralInk
        Badge.UNSUPPORTED -> Color.Transparent to c.muted
        Badge.DISABLED, Badge.COMING_SOON -> c.bubble to c.muted
    }
    val shape = RoundedCornerShape(99.dp)
    Text(
        string(badge.label),
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (badge == Badge.UNSUPPORTED) Modifier.border(1.dp, c.line, shape) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        color = fg, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** `ol.steps`: numbered help lines from the server. */
@Composable
fun Steps(lines: List<String>) {
    if (lines.isEmpty()) return
    val c = Kelo.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            Row {
                Text(string(Res.string.step_number, i + 1), color = c.ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(20.dp))
                Text(line, color = c.ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** `<b><ShieldIcon /> …</b>`: a bold line with the shield in front. */
@Composable
fun ShieldTitle(text: String) {
    val c = Kelo.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(KeloIcons.Shield, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, fontWeight = FontWeight.Bold, color = c.ink, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun BoldLine(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = Kelo.colors.ink, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun SmallText(text: String) {
    Text(text, color = Kelo.colors.ink, style = MaterialTheme.typography.bodySmall)
}

/**
 * A service's tile: its own app icon from the server when there is one (ConnectorIcons.kt; the services' trademarks, shown only to
 * say which service a row is), else its initials on a colour made from its id.
 */
@Composable
fun ConnectorTile(x: Connector, size: Dp) {
    ServiceIcon(x.icon, x.iconSmall, x.iconColor, size) { InitialsTile(x, size) }
}

/** The initials tile: the fallback when a service has no icon or it cannot be loaded. */
@Composable
fun InitialsTile(x: Connector, size: Dp) {
    val c = Kelo.colors
    val hue = remember(x.id) { ConnectorList.hue(x.id) }
    val letters = remember(x.name) { ConnectorList.initials(x.name.ifBlank { x.id }) }
    val bg = ConnectorList.tileColor(hue, c.dark)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letters, color = chat.kelo.app.ui.theme.TileInk, fontWeight = FontWeight.Bold, maxLines = 1,
            fontSize = (size.value * if (letters.length > 1) 0.36f else 0.44f).sp,
        )
    }
}

/** The red "다시 연결 필요" badge. */
@Composable
fun ReconnectBadge(modifier: Modifier = Modifier) {
    val c = Kelo.colors
    Text(
        string(Res.string.connector_status_reconnect),
        modifier = modifier.clip(RoundedCornerShape(99.dp)).background(c.danger.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp),
        color = c.danger, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** The line under a connect form that says what went wrong; nothing when [error] is null. */
@Composable
fun ConnectErrorLine(error: ConnectError?) {
    val text = when (error) {
        null -> return
        is ConnectError.Said -> error.text
        ConnectError.NoBrowser -> string(Res.string.connector_browser_failed)
        ConnectError.FileUnreadable -> string(Res.string.key_file_failed)
    }
    ErrorText(text, small = true)
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Kelo.colors.ink)
}
