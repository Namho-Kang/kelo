package chat.kelo.desktop.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.ui.platform.LocalHeaderHeight
import chat.kelo.app.work.WorkScreen
import chat.kelo.app.work.WorkScreenHooks
import chat.kelo.desktop.core.AppContainer

/**
 * 작업: the shared work screen ([WorkScreen]) in the desktop's window: no tab bar to hide, plain page switching and no card on this
 * computer (the interface's defaults), the sign-in dialog for a follow-up button, a browser tab opening in the shell's live view
 * (app.openRequests) and the place a notification, the tray or another page asked for ([AppContainer.workRequests]).
 */
@Composable
fun WorkPane(app: AppContainer, topInset: Dp) {
    val request by app.workRequests.collectAsState()
    val hooks = androidx.compose.runtime.remember(app) { DesktopWorkHooks(app) }
    CompositionLocalProvider(LocalHeaderHeight provides topInset) {
        WorkScreen(
            start = request, onStartUsed = { app.workRequests.value = null },
            onOpenLive = { id -> app.openRequests.value = OpenTarget.Tab(id) },
            hooks = hooks,
        )
    }
}

/** What the desktop gives the work screen beyond the defaults. */
private class DesktopWorkHooks(private val app: AppContainer) : WorkScreenHooks {
    @Composable
    override fun SignInSheet(name: String, onNotify: (String) -> Unit, onClose: () -> Unit) = chat.kelo.app.followup.SignInSheet(name, onNotify, onClose)
}
