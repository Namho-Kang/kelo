package chat.kelo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.kelo.app.core.Profile
import chat.kelo.app.my.MyScreen
import chat.kelo.app.my.MyScreenHooks
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.text.*
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.Autostart
import chat.kelo.desktop.core.ReleaseInfo
import chat.kelo.desktop.ui.SwitchRow
import chat.kelo.desktop.ui.UpdateCard
import chat.kelo.desktop.ui.updateRowValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

/** The name of the desktop's own My sub page (this computer's settings); it is one of [MyScreenHooks.extraPages]. */
const val MY_DESKTOP_PAGE = "desktop"

/**
 * 마이 is the shared My screen (chat.kelo.app.my.MyScreen, the same pages and view models as the phone). What this app adds to it:
 * "This device" (start at sign-in, the tray, motion, appearance, desktop notifications) with this app's own update
 * offer. [onSignedOut] runs after the sign-in is removed.
 */
@Composable
fun MyPane(app: AppContainer, hiddenTabs: List<chat.kelo.app.shell.TabSpec> = emptyList(), openTab: (String) -> Unit = {}, onSignedOut: () -> Unit) {
    val profile by app.profile.collectAsState()
    val openTabNow by androidx.compose.runtime.rememberUpdatedState(openTab)
    val hooks = remember(app, hiddenTabs) { desktopMyHooks(app, hiddenTabs) { openTabNow(it) } }
    MyScreen(profile ?: Profile(), onProfile = app::setProfile, onSignedOut = { app.sessions.signOut(); onSignedOut() }, hooks = hooks)
}

private fun desktopMyHooks(app: AppContainer, hiddenTabs: List<chat.kelo.app.shell.TabSpec>, openTab: (String) -> Unit) = MyScreenHooks(
    keyguardPage = { onBack, startWithCode -> chat.kelo.app.keyguard.KeyguardPage(onBack, startWithCode = startWithCode) },
    hiddenTabs = hiddenTabs,
    openTab = openTab,
    openConnectors = { app.openPage.value = "CONNECTORS" },
    topCard = { UpdateCard(app) },
    // the phone refreshes its profile elsewhere; here My does it each time the home page shows
    onHomeShown = { app.scope.launch { runCatching { app.sessions.requireApi().get<Profile>("/api/profile") }.onSuccess(app::setProfile) } },
    appRows = { go ->
        row {
            val on by app.settings.notifications.collectAsState()
            MenuRow(KeloIcons.Bell, string(Res.string.notify_my_row), { go(chat.kelo.app.my.MyPage.NOTIFY) },
                value = string(if (on) Res.string.notify_state_on else Res.string.notify_state_off),
                valueColor = if (on) null else chat.kelo.app.ui.theme.Kelo.colors.danger)
        }
        row {
            val theme by app.settings.theme.collectAsState()
            MenuRow(KeloIcons.Phone, string(Res.string.desktop_section), { go(MY_DESKTOP_PAGE) },
                value = string(Res.string.desktop_theme) + " · " + string(themeLabel(theme)))
        }
        if (app.updates.enabled) row {
            val scope = rememberCoroutineScope()
            val update by app.updates.state.collectAsState()
            MenuRow(KeloIcons.Retry, string(Res.string.update_check), { scope.launch { app.updates.check() } }, value = updateRowValue(update))
        }
    },
    extraPages = mapOf(MY_DESKTOP_PAGE to { onBack -> DesktopPage(app, onBack) }),
)

private fun themeLabel(v: String): StringResource = when (v) {
    "light" -> Res.string.desktop_theme_light
    "dark" -> Res.string.desktop_theme_dark
    else -> Res.string.desktop_theme_system
}

/** This device: desktop notifications, start at sign-in, the tray, reduced motion and the appearance (desktop only; a phone has none of these). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopPage(app: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    SubPage(string(Res.string.desktop_section), onBack) {
        val notify by app.settings.notifications.collectAsState()
        SwitchRow(string(Res.string.desktop_notifications), notify, app.settings::setNotifications, subtitle = string(Res.string.desktop_notifications_hint))
        val mode = Autostart.mode
        var autostart by remember { mutableStateOf(runCatching { Autostart.isOn() }.getOrDefault(false)) }
        SwitchRow(
            string(Res.string.desktop_start_at_login), autostart, enabled = mode == Autostart.Mode.SWITCH,
            subtitle = when (mode) {
                Autostart.Mode.SWITCH -> string(Res.string.desktop_start_at_login_hint)
                Autostart.Mode.SYSTEM -> string(Res.string.desktop_start_at_login_store)
                Autostart.Mode.UNAVAILABLE -> string(Res.string.desktop_start_at_login_unavailable)
            },
            onChange = { on -> scope.launch { autostart = withContext(Dispatchers.IO) { Autostart.set(on) } } },
        )
        val tray by app.settings.keepInTray.collectAsState()
        SwitchRow(string(Res.string.desktop_keep_in_tray), tray, app.settings::setKeepInTray)
        val motion by app.settings.reduceMotion.collectAsState()
        SwitchRow(string(Res.string.desktop_reduce_motion), motion, app.settings::setReduceMotion)
        SectionLabel(string(Res.string.desktop_theme))
        val theme by app.settings.theme.collectAsState()
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system", "light", "dark").forEach { v -> ChoiceChip(string(themeLabel(v)), selected = theme == v, onClick = { app.settings.setTheme(v) }) }
        }
        MutedText(string(Res.string.desktop_version, ReleaseInfo.VERSION), small = true)
    }
}
