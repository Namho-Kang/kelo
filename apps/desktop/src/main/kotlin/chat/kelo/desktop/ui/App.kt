package chat.kelo.desktop.ui

import chat.kelo.app.ui.text.*
import chat.kelo.app.res.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import chat.kelo.app.core.Profile
import chat.kelo.app.start.ConnectFlow
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.Haptics
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.LocalPageFrame
import chat.kelo.app.ui.platform.PageFrame
import chat.kelo.app.ui.theme.KeloTheme
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.desktop.core.AppContainer
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.core.DesktopNote
import chat.kelo.desktop.i18n.DesktopLocale

/** The app's theme and language around [content]: the user's choices (My → this device), else the system's. */
@Composable
fun KeloFrame(app: AppContainer, content: @Composable () -> Unit) {
    val theme by app.settings.theme.collectAsState()
    val lang by app.settings.language.collectAsState()
    val reduce by app.settings.reduceMotion.collectAsState()
    val code = remember(lang) { app.settings.shownLanguage() }
    val locale = remember(code) { DesktopLocale.of(code) }
    // the text comes from Compose resources, which read the default locale: set it before the content is composed (no key() needed,
    // LocalTextLocale below makes every text composable read again)
    remember(locale) { DesktopLocale.apply(locale) }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    // a desktop window has no ViewModelStoreOwner: the frame is one (the signed-in session has its own, see KeloApp)
    val viewModels = remember { object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = androidx.lifecycle.ViewModelStore() } }
    DisposableEffect(viewModels) { onDispose { viewModels.viewModelStore.clear() } }
    // and no lifecycle either: the shared screens' lifecycle effects (reload on resume, state collected with the lifecycle) see a window that is resumed
    val lifecycle = remember {
        object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        }
    }
    CompositionLocalProvider(
        androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides viewModels,
        androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycle,
        LocalTextLocale provides locale, LocalReduceMotion provides reduce,
        LocalFormFactor provides FormFactor.Desktop, LocalHaptics provides Haptics.None,
        chat.kelo.app.ui.text.LocalWordJoiner provides chat.kelo.app.ui.text.WordJoin::join,
        androidx.compose.ui.platform.LocalClipboardManager provides chat.kelo.app.ui.text.JoinerFreeClipboard(androidx.compose.ui.platform.LocalClipboardManager.current),
        chat.kelo.app.LocalAppServices provides app,
        chat.kelo.app.ui.platform.LocalReloadOnReturn provides { key, load -> ReloadOnReturn(key, load) },
        chat.kelo.app.platform.LocalQrScanner provides DesktopLinkScanner,
        // a sub page sits below the floating header, centred and at most this wide
        LocalPageFrame provides PageFrame(topInset = HeaderHeight, maxWidth = SUB_PAGE_WIDTH.dp),
    ) {
        KeloTheme(dark = dark) {
            // Arabic reads right to left: the whole window mirrors
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (code == "ar") androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                content = { DesktopToasts(content) },
            )
        }
    }
}

/**
 * The window's content: the connect screen while signed out, else the app. The chat lives as long as the sign-in, so a reply keeps
 * coming while another page is open; when it finishes with the window hidden, the tray says so.
 */
@Composable
fun KeloApp(app: AppContainer, initialPage: Page = Page.CHAT, initialLink: String = "") {
    KeloFrame(app) {
        val api by app.sessions.api.collectAsState()
        if (api == null) {
            ConnectFlow(initialLink)
            return@KeloFrame
        }
        val chat = remember(api) { DesktopChat(app) }
        DisposableEffect(chat) { onDispose { chat.close() } }
        var page by remember { mutableStateOf(initialPage) }
        var connector by remember { mutableStateOf<String?>(null) }
        val open by app.openPage.collectAsState()
        LaunchedEffect(open) {
            val o = open ?: return@LaunchedEffect
            app.openPage.value = null
            when {
                o.startsWith("connector:") -> { connector = o.removePrefix("connector:"); page = Page.CONNECTORS }
                else -> Page.entries.firstOrNull { it.name.equals(o, ignoreCase = true) }?.let { page = it }
            }
        }
        LaunchedEffect(api) {
            runCatching { app.sessions.requireApi().get<Profile>("/api/profile") }.onSuccess(app::setProfile)
        }
        CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides chat) {   // the work screen's view model lives and dies with the sign-in
            Shell(app, chat.vm, page, onPage = { page = it }, onSignedOut = {}, connectorToOpen = connector, onConnectorOpened = { connector = null }, side = chat.side)
        }
    }
}
