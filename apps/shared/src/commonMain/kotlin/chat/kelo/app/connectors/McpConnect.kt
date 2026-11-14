package chat.kelo.app.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.text.string

/** Sign in on the service's own page (hosted MCP servers), in the browser. The service sends the user back to the server. */
@Composable
internal fun McpConnect(c: Connector, onChanged: () -> Unit, onNotify: ((String) -> Unit)?) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "mcp-${c.id}") { McpConnectViewModel(services.sessions, services.browser, c.id) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    var other by remember(c.id) { mutableStateOf(false) }
    // back from the browser: the button is ready again (the list reload shows whether it worked)
    LocalReloadOnReturn.current(c.id) { vm.returned() }

    if (confirming) {
        ConfirmDialog(
            string(Res.string.mcp_disconnect_confirm, c.name), string(Res.string.connector_disconnect),
            onYes = { vm.disconnect(c.item, onChanged) },
            onDismiss = { confirming = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (c.status == "connected") {
            ShieldTitle(string(Res.string.connector_connected_name, c.name))
            MutedText(string(Res.string.mcp_connected_note), small = true)
            if (onNotify != null) {
                val text = string(Res.string.chat_connect_notify, c.name)
                PrimaryButton(string(Res.string.notify_agent), onClick = { onNotify(text) })
            }
            LinkButton(string(Res.string.connector_disconnect), onClick = { confirming = true }, danger = true)
        } else {
            BoldLine(string(Res.string.mcp_title, c.name))
            MutedText(string(Res.string.mcp_help, c.name), small = true)
            // the service may accept only apps it approved, which shows only after the login: said before the sign-in opens
            if (c.note.isNotBlank()) MutedText(c.note, small = true)
            PrimaryButton(
                if (ui.busy) string(Res.string.connector_opening) else string(Res.string.mcp_sign_in, c.name),
                enabled = !ui.busy,
                onClick = vm::signIn,
            )
            ConnectErrorLine(ui.error)
            val fb = c.fallback
            if (fb != null && fb.connect == "key" && fb.title.isNotBlank()) {
                if (other) {
                    BoldLine(fb.title)
                    KeyConnect(c.copy(connect = "key", via = fb.via, item = fb.item, description = fb.blurb), onChanged, onNotify)
                } else {
                    LinkButton("${fb.title} ›", onClick = { other = true })
                }
            }
        }
    }
}
