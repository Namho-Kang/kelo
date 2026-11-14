package chat.kelo.app.my

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One block of the My home list: a heading and the rows under it. The built-in sections come first; a Nest `my_section` entry may
 * be appended later ([MyScreenHooks.extraSections]). Nothing for Nest is drawn yet.
 */
class MySection(val id: String, val title: String, val rows: chat.kelo.app.ui.components.GroupScope.() -> Unit)

/** The My page names a notification or another screen can ask for ([chat.kelo.app.AppServices.myPageRequests]). */
object MyPage {
    /** Opens the wallet page */
    const val WALLET = "wallet"
    /** The Keychain (behind its own lock) */
    const val VAULT = "vault"
    /** ... at its add-card form (a card number the chat held back) */
    const val WALLET_ADD = "wallet:add"
    /** 운영자 (a beta request notification) */
    const val OPERATOR = "operator"
    /** 서버 업데이트 (a server update notification; owner only) */
    const val SERVER_UPDATE = "server-update"
    /** Security -> the phone key (Keyguard); [KEYGUARD_CODE] opens it at the recovery code */
    const val KEYGUARD = "keyguard"
    const val KEYGUARD_CODE = "keyguard:code"
    /** Kelo server resources (a storage warning was tapped) */
    const val RESOURCES = "resources"
    /** ... its cleanup screen (the warning's action) */
    const val CLEANUP = "cleanup"
    /** The agent's own page (its picture and name) */
    const val AGENT = "agent"
    /** The notification settings (an app's own row opens it with `go`) */
    const val NOTIFY = "notify"
    /** The model settings (the first-run card's button) */
    const val MODEL = "model"

    /** The model settings opened at one CLI's box ("model:<provider>"); a blank provider is just the page. */
    fun model(provider: String): String = if (provider.isBlank()) MODEL else "$MODEL:$provider"
}

/**
 * Everything the shared My screen takes from its app, in one object (no platform `if` in the screen): the tab bar's reselect and
 * insets, the app's chrome, and the sub pages that are still per app until their phase.
 */
class MyScreenHooks(
    /** Tab re-select: a sub page steps back, the home page scrolls to the top. */
    val reselect: Flow<Unit> = emptyFlow(),
    /** How much of the bottom the app's own bar covers (the phone's tab bar). */
    val bottomInset: @Composable () -> Dp = { 0.dp },
    /** Hides the app's top bar and tab bar while a sub page is open, in the same frame as the page starts sliding. */
    val hideChrome: (key: String, hidden: Boolean) -> Unit = { _, _ -> },
    /** 연결: the connectors tab. */
    val openConnectors: () -> Unit = {},
    /** Keyguard: the key of passwords, keys and cards on the owner's phones (the Android Keystore and biometric check, so the page lives in the Android app); null = this app has none (its row is not shown). */
    val keyguardPage: (@Composable (onBack: () -> Unit, startWithCode: Boolean) -> Unit)? = null,
    /** The built-in tabs the owner's tab layout hides (다짐, 커넥터): My lists them so they stay reachable. */
    val hiddenTabs: List<chat.kelo.app.shell.TabSpec> = emptyList(),
    /** Opens a tab by its id (a row of [hiddenTabs]). */
    val openTab: (String) -> Unit = {},
    /** Sections after the built-in ones (Nest `my_section` entries). */
    val extraSections: List<MySection> = emptyList(),
    /** Rows at the end of the app group (the desktop's own settings); each calls `go(name)` to open one of [extraPages]. */
    val appRows: (chat.kelo.app.ui.components.GroupScope.(go: (String) -> Unit) -> Unit)? = null,
    /** Sub pages this app adds, by name (the name a row passes to `go`, or a request in [chat.kelo.app.AppServices.myPageRequests]). */
    val extraPages: Map<String, @Composable (onBack: () -> Unit) -> Unit> = emptyMap(),
    /** The home page was shown (again): an app that does not keep the profile fresh itself reloads it here. */
    val onHomeShown: () -> Unit = {},
    /** A card above the first section (the desktop's own update offer). */
    val topCard: @Composable () -> Unit = {},
)
