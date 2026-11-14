package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.components.rememberSkeletonShown
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.platform.rememberPageViewModelStoreOwner
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/**
 * The right way to connect this service, whichever it is. Without [connector] it looks the service up by [id]
 * (for a card in the chat). [onNotify], when given, offers "에이전트에게 알리기" once connected. The forms' view models live as long as
 * the panel is shown.
 */
@Composable
fun ConnectPanel(
    id: String, connector: Connector? = null, onChanged: () -> Unit = {}, onNotify: ((String) -> Unit)? = null,
    onPhoneCalendar: (() -> Unit)? = null, header: Boolean = false,
) {
    val owner = rememberPageViewModelStoreOwner("connect-panel:$id")
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        PanelContent(id, connector, onChanged, onNotify, onPhoneCalendar, header)
    }
}

@Composable
private fun PanelContent(
    id: String, connector: Connector?, onChanged: () -> Unit, onNotify: ((String) -> Unit)?, onPhoneCalendar: (() -> Unit)?, header: Boolean,
) {
    val sessions = LocalAppServices.current.sessions
    val lookup = viewModel(key = "lookup-$id") { ConnectPanelViewModel(sessions, id) }
    val looked by lookup.state.collectAsStateWithLifecycle()
    if (connector == null) LocalReloadOnReturn.current(id) { lookup.lookUp() }
    val c = connector ?: looked.looked
    val placeholder = rememberSkeletonShown(c == null)
    if (c == null || placeholder) {
        SkeletonLines(2)
        return
    }
    val changed: () -> Unit = { onChanged(); if (connector == null) lookup.lookUp() }
    if (header) {   // the chat's card: which service, with its icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            ConnectorTile(c, 32.dp)
            Text(c.name.ifBlank { c.id }, style = MaterialTheme.typography.titleMedium, color = Kelo.colors.ink)
        }
    }
    when (c.connect) {
        "device" -> DeviceConnect(c.via.ifBlank { c.id }, changed, onNotify)
        "key" -> KeyConnect(c, changed, onNotify)
        "browser" -> BrowserConnect(c, onNotify)
        "mcp" -> McpConnect(c, changed, onNotify)
        "mail" -> MailConnect(c, changed, onNotify)
        "caldav" -> CalDavConnect(c, changed, onNotify)
        "google" -> GoogleConnect(c, changed, onNotify, onPhoneCalendar)
        else -> MutedText(if (c.status == "unsupported") c.description else string(Res.string.connector_not_yet), small = true)
    }
}
