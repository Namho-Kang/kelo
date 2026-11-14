package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.vault.SecureCard
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/** A site the agent uses with its own browser. A login, if the site needs one, is kept in the vault and typed by the server. */
@Composable
internal fun BrowserConnect(c: Connector, onNotify: ((String) -> Unit)?) {
    val services = LocalAppServices.current
    val host = ConnectorRules.host(c.site)
    val vm = viewModel(key = "browser-${c.id}") { BrowserConnectViewModel(services.sessions, services.browser, c.site, c.name) }
    val ui by vm.state.collectAsStateWithLifecycle()
    LocalReloadOnReturn.current(host) { vm.load() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (c.site.isNotBlank()) {
            LinkButton(string(Res.string.browser_site_link, host.ifBlank { c.site }), onClick = vm::openSite)
        }
        val kept = ui.kept
        val asking = ui.asking
        when {
            kept is LoginKept.Some -> ShieldTitleSmall(string(Res.string.browser_saved, kept.usernameHint ?: string(Res.string.browser_saved_default)))
            asking != null -> SecureCard(asking, onNotify)
            kept == LoginKept.None -> SecondaryButton(string(Res.string.browser_ask), onClick = vm::askForLogin)
        }
        MutedText(string(Res.string.browser_note), small = true)
        ConnectErrorLine(ui.error)
    }
}

@Composable
private fun ShieldTitleSmall(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(KeloIcons.Shield, contentDescription = null, tint = Kelo.colors.ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        SmallText(text)
    }
}
