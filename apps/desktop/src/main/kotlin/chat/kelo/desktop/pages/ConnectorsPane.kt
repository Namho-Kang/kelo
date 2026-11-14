package chat.kelo.desktop.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import chat.kelo.app.connectors.ConnectorsScreen
import chat.kelo.desktop.core.AppContainer

/**
 * 커넥터 is the shared screen (chat.kelo.app.connectors.ConnectorsScreen); in a wide window the open connector sits beside the list.
 * A connector asked for from outside (a notification, a chat card) is opened by [openId]; [onOpened] says it was taken. The page
 * frame (below the floating header) comes from the window root, so [topInset] is not used any more.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ConnectorsPane(app: AppContainer, topInset: Dp, openId: String? = null, onOpened: () -> Unit = {}) {
    LaunchedEffect(openId) { if (openId != null) onOpened() }
    key(openId) { ConnectorsScreen(openId) }   // a new request starts the screen at that connector
}
