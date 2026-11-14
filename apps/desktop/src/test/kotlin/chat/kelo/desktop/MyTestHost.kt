package chat.kelo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.pages.MyPane

/** The My page names a test can open first (the names the shared My screen takes from [AppContainer.myPageRequests]). */
object MyView {
    const val PROFILE = "profile"
    const val AGENT = "agent"
    const val RECORDS = "records"
    const val NOTIFY = "notify"
    const val MODEL = "model"
    const val USAGE = "usage"
    const val DEVICES = "devices"
    const val RESOURCES = "resources"
    const val DOCUMENTS = "documents"
    const val PRIVACY = "privacy"
    const val LANGUAGE = "language"
    const val SERVER_UPDATE = "server-update"
    const val DESKTOP = "desktop"
}

/** The My page; a value put in [start] opens that sub page (as a notification would) and is then taken. */
@Composable
fun MyPaneAt(app: AppContainer, start: MutableState<String?>) {
    MyPane(app, onSignedOut = {})
    LaunchedEffect(start.value) {
        val v = start.value ?: return@LaunchedEffect
        app.myPageRequests.value = v
        start.value = null
    }
}
