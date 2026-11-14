package chat.kelo.desktop.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.ui.platform.keloViewModel
import chat.kelo.app.shell.*
import org.jetbrains.compose.resources.StringResource
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import chat.kelo.app.core.AppVersion
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.AgentActivityRules
import chat.kelo.app.chat.AgentStatus
import chat.kelo.app.chat.BrowserTabsBrief
import chat.kelo.app.chat.TasksBrief
import chat.kelo.app.chat.WorkersBrief
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.ui.character.KeloCharacter
import chat.kelo.app.ui.character.KeloMood
import chat.kelo.app.ui.character.centerShift
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.work.WorkBadge
import chat.kelo.app.chat.ChatViewModel
import chat.kelo.desktop.chat.ChatSide
import chat.kelo.desktop.chat.LocalChatSide
import chat.kelo.desktop.chat.ChatPane
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.LiveRequest
import chat.kelo.desktop.core.MY_PAGE_OPERATOR
import chat.kelo.desktop.core.MY_PAGE_SERVER_UPDATE
import chat.kelo.desktop.core.MY_PAGE_RESOURCES
import chat.kelo.desktop.core.MY_PAGE_CLEANUP
import chat.kelo.desktop.core.MY_PAGE_MODEL
import chat.kelo.desktop.core.MY_PAGE_AGENT
import chat.kelo.desktop.core.MY_PAGE_WALLET
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.agent.ownTabId
import chat.kelo.app.shell.PairAgainScreen
import chat.kelo.app.shell.ServerTooOldScreen
import chat.kelo.desktop.pages.ConnectorsPane
import chat.kelo.desktop.pages.GoalsPane
import chat.kelo.app.keyguard.LockedBanner
import chat.kelo.app.my.MyPage
import chat.kelo.app.work.LiveOverlay
import chat.kelo.desktop.pages.MyPane
import chat.kelo.desktop.pages.WorkPane
import kotlinx.coroutines.delay

/** The signed-in app's pages, in the side rail's order; [id] is the tab's id ([chat.kelo.app.shell.Routes]). */
enum class Page(val id: String) {
    CHAT(Routes.CHAT),
    GOALS(Routes.GOALS),
    WORK(Routes.WORK),
    CONNECTORS(Routes.CONNECTORS),
    MY(Routes.MY),
}

/** How far the floating header reaches down (the pages leave this much room at their top). */
val HeaderHeight: Dp = 104.dp

/**
 * The signed-in window: a side rail with the five pages (채팅, 다짐, 작업, 커넥터, 마이; 작업 carries a badge when something waits),
 * the page, and over it the floating header: Kelo's character (whose drawing follows what the agent is doing) with the agent's
 * name and, while it works, what it is doing. Clicking the character opens 작업.
 */
@Composable
fun Shell(
    app: AppContainer, chat: ChatViewModel, page: Page, onPage: (Page) -> Unit, onSignedOut: () -> Unit, connectorToOpen: String?, onConnectorOpened: () -> Unit,
    /** What only the desktop chat keeps beside the view model (the human checks' places, address forms answered with a card) */
    side: ChatSide = ChatSide(),
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalChatSide provides side) { ShellBody(app, chat, page, onPage, onSignedOut, connectorToOpen, onConnectorOpened) }
}

@Composable
private fun ShellBody(app: AppContainer, chat: ChatViewModel, page: Page, onPage: (Page) -> Unit, onSignedOut: () -> Unit, connectorToOpen: String?, onConnectorOpened: () -> Unit) {
    val c = Kelo.colors
    val profile by app.profile.collectAsState()
    val p = profile ?: Profile()
    val shellVm = keloViewModel("shell") {
        ShellViewModel(app.sessions, app.activity, app.agentAvatar, location = app.phoneLocation)
    }
    androidx.compose.runtime.SideEffect { shellVm.bind(profile, app::setProfile) }
    val shell by shellVm.state.collectAsStateWithLifecycle()
    // the window coming to the front counts as something happening (the character wakes)
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { app.activity.touch(System.nanoTime() / 1_000_000) }
    val builtIn = builtInTabs(withWork = true) { {} }
    val layout = TabLayout.Default   // no editing yet (Nest work); a hidden 다짐 or 커넥터 stays reachable from My
    val tabs = rememberTabs(builtIn, layout)
    val hidden = remember(layout) { hiddenTabs(builtIn, layout) }
    // the agent's picture: the kept one at once, then in step with the profile's version (the status poll keeps it so after)
    LaunchedEffect(p.avatarVersion) {
        val avatar = app.agentAvatar
        avatar.warm()
        avatar.sync(p.avatarVersion)
    }
    // the app's own update check: at start, then once a day (checked every hour; Updater.checkIfDue)
    LaunchedEffect(app.updates) {
        while (true) {
            app.updates.checkIfDue()
            kotlinx.coroutines.delay(60 * 60 * 1000L)
        }
    }
    // the server's oldest supported desktop version (min_app_version.desktop): below it the required-update screen covers the window
    val minVersion = p.minAppVersion["desktop"]
    LaunchedEffect(minVersion) { app.updates.requireAtLeast(minVersion) }
    val updateRequired by app.updates.required.collectAsState()
    // 426 app_too_old from any call (its min_app is newer than this release): the same required-update screen
    val appTooOld by app.sessions.appTooOld.collectAsState()
    LaunchedEffect(appTooOld) { if (appTooOld) app.updates.requireNow() }
    // KE2E: the server's key changed (nothing more is sent until this device is connected again); the server is too old for
    // this app (no KE2E, or X-Kelo-Server below min_server: never plaintext, spec 5.18)
    val keyChanged by app.sessions.keyChanged.collectAsState()
    val serverOld by app.sessions.serverOld.collectAsState()
    val serverSeen by AppVersion.server.collectAsState()
    // a notification, a card's button or the tray asked for a place (the phone's MainShell does the same with OpenTarget)
    val openRequest by app.openRequests.collectAsState()
    LaunchedEffect(openRequest) {
        val target = openRequest ?: return@LaunchedEffect
        app.openRequests.value = null
        when (target) {
            OpenTarget.Chat -> { app.liveTab.value = null; onPage(Page.CHAT) }
            // Kelo is locked (Keyguard): only an owner phone unlocks it; this computer shows the locked banner (spec 2.4: no key here)
            OpenTarget.Unlock -> { app.liveTab.value = null; app.sessions.setLocked(true); onPage(Page.CHAT) }
            is OpenTarget.ChatAt -> { app.liveTab.value = null; app.chatFocus.value = target.messageId; onPage(Page.CHAT) }
            is OpenTarget.Entry -> { app.liveTab.value = null; app.chatEntryFocus.value = target.requestId; onPage(Page.CHAT) }
            OpenTarget.Wallet -> { app.liveTab.value = null; app.myPageRequests.value = MY_PAGE_WALLET; onPage(Page.MY) }
            OpenTarget.Keychain -> { app.liveTab.value = null; app.myPageRequests.value = MyPage.VAULT; onPage(Page.MY) }
            OpenTarget.Operator -> { app.liveTab.value = null; app.myPageRequests.value = MY_PAGE_OPERATOR; onPage(Page.MY) }
            OpenTarget.ServerUpdate -> { app.liveTab.value = null; app.myPageRequests.value = MY_PAGE_SERVER_UPDATE; onPage(Page.MY) }
            OpenTarget.Storage -> { app.liveTab.value = null; app.myPageRequests.value = MY_PAGE_RESOURCES; onPage(Page.MY) }
            OpenTarget.StorageCleanup -> { app.liveTab.value = null; app.myPageRequests.value = MY_PAGE_CLEANUP; onPage(Page.MY) }
            is OpenTarget.ModelSettings -> {   // a newer CLI notice: My → Model at that CLI's box ("model:<provider>")
                app.liveTab.value = null
                app.myPageRequests.value = if (target.provider.isBlank()) MY_PAGE_MODEL else "$MY_PAGE_MODEL:${target.provider}"
                onPage(Page.MY)
            }
            is OpenTarget.Tab -> app.liveTab.value = LiveRequest(target.id, target.control, target.picture)
            OpenTarget.OwnTab -> app.liveTab.value = LiveRequest(
                runCatching { ownTabId(app.sessions.requireApi()) }.getOrDefault("owner"),
            )
            is OpenTarget.Task, is OpenTarget.Schedule, OpenTarget.Approvals, OpenTarget.Activity, OpenTarget.Schedules, OpenTarget.Watchers -> {
                app.liveTab.value = null
                app.workRequests.value = target
                onPage(Page.WORK)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(c.bg)) {
            Rail(tabs, page, shell) { id -> Page.entries.firstOrNull { it.id == id }?.let(onPage) }
            VerticalDivider(color = c.line)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (page) {
                    Page.CHAT -> ChatPane(
                        chat, app.sessions, p, onProfile = app::setProfile,
                        onOpenConnector = { id -> app.openPage.value = "connector:$id" },
                        topInset = HeaderHeight,
                        onOpenTarget = { t -> app.openRequests.value = t },
                        onOpenGoals = { onPage(Page.GOALS) },
                    )
                    Page.GOALS -> GoalsPane(app, topInset = HeaderHeight)
                    Page.WORK -> WorkPane(app, topInset = HeaderHeight)
                    Page.CONNECTORS -> ConnectorsPane(app, topInset = HeaderHeight, openId = connectorToOpen, onOpened = onConnectorOpened)
                    Page.MY -> MyPane(app, hiddenTabs = hidden, openTab = { id -> Page.entries.firstOrNull { it.id == id }?.let(onPage) }, onSignedOut = onSignedOut)
                }
                // a long chat (or list) scrolls on under the floating agent: the page's colour fades in towards the window's top edge, so
                // older messages go out of sight there instead of showing through around the agent (backlog 39; the phone has its
                // status-bar scrim for the same reason)
                TopFade(Modifier.align(Alignment.TopCenter))
                // as on the phone, the agent opens its own page (My → the agent: its name, picture and what it is doing)
                FloatingHeader(p, shell, onAgent = { app.myPageRequests.value = MY_PAGE_AGENT; onPage(Page.MY) }, modifier = Modifier.align(Alignment.TopCenter))
                // a newer app version: a small pill once per version (My keeps the card and the download)
                UpdateBanner(app, onOpenMy = { onPage(Page.MY) }, modifier = Modifier.align(Alignment.BottomCenter))
                // Keyguard: the server holds no key ("Kelo is locked", 423 / agent status): this computer holds no copy, so the banner
                // says where to unlock and offers the recovery code (My → Keyguard)
                val locked by app.sessions.locked.collectAsState()
                if (locked) LockedBanner(
                    onRecover = { app.liveTab.value = null; app.myPageRequests.value = MyPage.KEYGUARD_CODE; onPage(Page.MY) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = HeaderHeight + 8.dp, start = 12.dp, end = 12.dp).widthIn(max = 560.dp),
                )
                // a browser tab live over the page ("solve it" on a human check, from the chat or 작업)
                val liveTab by app.liveTab.collectAsState()
                liveTab?.let { r -> LiveOverlay(r.id, takeControl = r.control, picture = r.picture, onClose = { app.liveTab.value = null }) }
            }
        }
        // the server needs a newer version (min_app_version.desktop): this covers everything until the app is updated
        if (updateRequired) UpdateRequiredScreen(app)
        else if (keyChanged) PairAgainScreen(onPairAgain = { app.sessions.pairAgain(); onSignedOut() })
        else if (serverOld || AppVersion.serverTooOld(serverSeen)) {
            ServerTooOldScreen(serverSeen, onRetry = { app.sessions.recheckServer() })
        }
    }
}

@Composable
private fun Rail(tabs: List<TabSpec>, page: Page, shell: ShellUiState, onPage: (String) -> Unit) {
    val c = Kelo.colors
    Column(
        Modifier.width(92.dp).fillMaxHeight().background(c.bg).padding(top = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val on = tab.id == page.id
            val label = tab.title.text()
            val tabBadge = if (tab.badge == TabBadgeKind.WORK) shell.badge else null
            val badgeWords = tabBadge?.let { waitingWords(shell.status) }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp))
                    .pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Tab) { onPage(tab.id) }
                    .semantics { contentDescription = listOfNotNull(label, badgeWords).joinToString(", ") }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    Box(Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) c.mine else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(tab.icon, contentDescription = null, tint = if (on) c.ink else c.muted, modifier = Modifier.size(22.dp))
                    }
                    // only while something waits for the owner (WorkBadge): how many, in a red pill
                    if (tabBadge != null) TabBadge(tabBadge, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
                }
                // labels wrap (two lines at most) rather than being cut: "Connecteurs", "Конекторы"
                Text(label, color = if (on) c.ink else c.muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


/** The page's colour at the window's top edge, fading out over the header's height (see the call in ShellBody). */
@Composable
private fun TopFade(modifier: Modifier = Modifier) {
    val bg = Kelo.colors.bg
    Box(
        modifier.fillMaxWidth().height(HeaderHeight * TOP_FADE_SHARE)
            .background(Brush.verticalGradient(0f to bg, 0.55f to bg.copy(alpha = 0.92f), 1f to bg.copy(alpha = 0f))),
    )
}

/** How much of the header's height the fade covers: the agent's picture and name tag sit on it, the first message starts below. */
private const val TOP_FADE_SHARE = 0.9f
