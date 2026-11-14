package chat.kelo.app.my

import chat.kelo.app.shell.text
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.Languages
import chat.kelo.app.core.Profile
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.platform.LocalToaster
import chat.kelo.app.res.*
import chat.kelo.app.ui.PushHost
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.StaleHint
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalChromeHider
import chat.kelo.app.ui.platform.LocalPageFrame
import chat.kelo.app.ui.platform.headerHeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.widthIn
import chat.kelo.app.ui.platform.keloViewModel
import chat.kelo.app.ui.platform.rememberVaultGate
import chat.kelo.app.ui.text.*
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.update.UpdateCard
import chat.kelo.app.update.checkValue
import chat.kelo.app.wallet.WalletPage
import chat.kelo.app.wallet.WalletPlaces
import chat.kelo.app.wallet.WalletView
import kotlinx.coroutines.launch

/** The My page's sub pages. */
private object MyView {
    const val HOME = "home"
    const val AGENT = "agent"
    const val PERMISSIONS = "permissions"
    const val RECORDS = "records"
    const val NOTIFY = MyPage.NOTIFY
    const val PROFILE = "profile"
    const val MODEL = "model"
    const val USAGE = "usage"
    const val DEVICES = "devices"
    const val VAULT = "vault"
    const val RESOURCES = "resources"
    const val CLEANUP = "cleanup"
    const val DOCUMENTS = "documents"
    const val FILES = "files"
    const val PRIVACY = "privacy"
    const val LANGUAGE = "language"
    const val WALLET = "wallet"
    const val OPERATOR = "operator"
    const val SERVER_UPDATE = "server_update"
    const val KEYGUARD = "keyguard"

    /** Sub pages a request may open by this name (the ones that need nothing but the name). */
    val NAMED = setOf(PROFILE, NOTIFY, USAGE, DEVICES, DOCUMENTS, FILES, PRIVACY, LANGUAGE, PERMISSIONS, RECORDS)
}

/**
 * The 마이 tab: one grouped settings list (account and devices, the agent, the model and its usage, connections, security, data,
 * the app, and for the server's owner its operator settings). It shows only what belongs to the person; the assistant's skills
 * and notes are not listed here. Sub pages cover the whole screen (the app's bars step aside); the back arrow or the phone's back
 * gesture steps out of them, and the list comes back scrolled where it was left. The vault opens only after the phone's own check
 * (fingerprint, face or screen lock). [onOpenTab] switches to another tab (연결 opens 커넥터; 권한's waiting questions open 작업).
 */
@Composable
fun MyScreen(profile: Profile, onProfile: (Profile) -> Unit, onSignedOut: () -> Unit, hooks: MyScreenHooks) {
    val services = LocalAppServices.current
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    var vaultNoLock by rememberSaveable { mutableStateOf(false) }
    val view = stack.lastOrNull() ?: MyView.HOME
    // the header and tabs start stepping aside in the same frame as the page starts sliding
    fun push(v: String) { stack = MyStack.push(stack, v); hooks.hideChrome(CHROME_KEY, MyStack.isSub(stack)) }
    val back: () -> Unit = { stack = MyStack.pop(stack); hooks.hideChrome(CHROME_KEY, MyStack.isSub(stack)) }
    val openVault = rememberVaultGate { noLock -> vaultNoLock = noLock; push(MyView.VAULT) }
    fun go(v: String) = if (v == MyView.VAULT) openVault() else push(v)
    LocalChromeHider.current(CHROME_KEY, MyStack.isSub(stack))   // also after the activity is recreated with a sub page open
    // the CLI a notification asked 마이 → 모델 to show (its provider), until the page is left
    var modelCli by rememberSaveable { mutableStateOf<String?>(null) }
    var keyguardCode by rememberSaveable { mutableStateOf(false) }   // the page opens at the recovery code (from the locked banner)
    LaunchedEffect(view) { if (view != MyView.MODEL) modelCli = null }
    // a notification asked for one of the pages
    val pageRequest by services.myPageRequests.collectAsStateWithLifecycle()
    LaunchedEffect(pageRequest) {
        val p = pageRequest ?: return@LaunchedEffect
        services.myPageRequests.value = null
        if (p == MyPage.WALLET && stack.lastOrNull() != MyView.WALLET) push(MyView.WALLET)
        if (p == MyPage.WALLET_ADD) {   // the card form, behind the wallet's own lock
            services.walletPlaces.go(WalletView.Add)
            if (stack.lastOrNull() != MyView.WALLET) push(MyView.WALLET)
        }
        if (p == MyPage.AGENT && stack.lastOrNull() != MyView.AGENT) push(MyView.AGENT)
        if (p == MyPage.VAULT && stack.lastOrNull() != MyView.VAULT) openVault()   // through the Keychain's own lock
        if (p == MyPage.MODEL || p.startsWith(MyPage.MODEL + ":")) {
            modelCli = p.substringAfter(':', "").ifEmpty { null }   // a newer CLI notice: the page shows that CLI's box
            if (stack.lastOrNull() != MyView.MODEL) push(MyView.MODEL)
        }
        if (p == MyPage.OPERATOR && stack.lastOrNull() != MyView.OPERATOR) push(MyView.OPERATOR)
        if (p == MyPage.SERVER_UPDATE && stack.lastOrNull() != MyView.SERVER_UPDATE) push(MyView.SERVER_UPDATE)
        if ((p == MyPage.KEYGUARD || p == MyPage.KEYGUARD_CODE) && hooks.keyguardPage != null && stack.lastOrNull() != MyView.KEYGUARD) {
            keyguardCode = p == MyPage.KEYGUARD_CODE
            push(MyView.KEYGUARD)
        }
        // a page by its name (a sub page without a request of its own, or one this app adds)
        if (p in MyView.NAMED || p in hooks.extraPages) { if (stack.lastOrNull() != p) push(p) }
        // a storage warning: its tap opens the resources page, its 정리하기 action the cleanup screen (over the resources page)
        if (p == MyPage.RESOURCES && stack.lastOrNull() != MyView.RESOURCES) push(MyView.RESOURCES)
        if (p == MyPage.CLEANUP && stack.lastOrNull() != MyView.CLEANUP) {
            if (stack.lastOrNull() != MyView.RESOURCES) push(MyView.RESOURCES)
            push(MyView.CLEANUP)
        }
    }
    // the home page's scroll lives here, not in the home page, so it survives a visit to a sub page (and a rotation)
    val homeScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    LaunchedEffect(hooks.reselect) {
        hooks.reselect.collect { if (MyStack.isSub(stack)) back() else homeScroll.animateScrollTo(0) }
    }
    val sessions = services.sessions
    val server = keloViewModel("my-server-update") { ServerUpdateViewModel(sessions) }
    val vm = keloViewModel("my-overview") { MyOverviewViewModel(sessions, server) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val o = ui.overview
    val serverUi by server.state.collectAsStateWithLifecycle()

    // fresh numbers every time the home page is shown (see MyOverviewViewModel.refresh)
    LaunchedEffect(view) { if (view == MyView.HOME) { vm.refresh(); hooks.onHomeShown() } }

    PushHost(
        state = stack,
        depth = { it.size },
        back = if (MyStack.isSub(stack)) MyStack.pop(stack) else null,
        onBack = back,
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        when (shown.lastOrNull() ?: MyView.HOME) {
            MyView.PROFILE -> ProfilePage(profile, onProfile, back)
            MyView.AGENT -> AgentPage(profile, onProfile, back)
            MyView.PERMISSIONS -> PermissionsPage(back, onOpenWork = { services.openRequests.value = OpenTarget.Activity })
            MyView.RECORDS -> RecordsPage(back)
            MyView.NOTIFY -> NotifyPage(back, onDevices = { push(MyView.DEVICES) })
            MyView.MODEL -> ModelPage(profile, onProfile, back, cliProvider = modelCli)
            MyView.USAGE -> UsagePage(o.usage, profile.model, onRefresh = { vm.refreshUsage(true) }, onBack = back)
            MyView.DEVICES -> DevicesPage(back, onSignedOut)
            MyView.WALLET -> WalletPage(back, onSeen = vm::walletSeen)
            MyView.VAULT -> VaultPage(back, onGoModel = { push(MyView.MODEL) }, noLock = vaultNoLock)
            MyView.RESOURCES -> ResourcesPage(back, onCleanup = { push(MyView.CLEANUP) })
            MyView.CLEANUP -> CleanupPage(back)
            MyView.DOCUMENTS -> DocumentsPage(back)
            MyView.FILES -> KeloFilesPage(back)
            MyView.PRIVACY -> PrivacyPage(back)
            MyView.OPERATOR -> OperatorPage(back)
            MyView.KEYGUARD -> hooks.keyguardPage?.invoke(back, keyguardCode)
            MyView.SERVER_UPDATE -> ServerUpdatePage(server, back)
            MyView.LANGUAGE -> LanguagePage(
                services.language.current ?: Languages.SYSTEM, services.language.systemShownCode,
                onChange = { code -> services.language.pick(code) },   // the whole app in the chosen language
                onBack = back,
            )
            else -> {
                val extra = hooks.extraPages[shown.lastOrNull()]
                if (extra != null) extra(back) else Box(Modifier.fillMaxSize()) {
                    Home(profile, o, serverUi.status, homeScroll, hooks, ::go, onSignedOut, vm)
                    StaleHint(ui.offline, Modifier.align(Alignment.BottomCenter).padding(bottom = hooks.bottomInset() + 12.dp))
                }
            }
        }
    }
}

private const val CHROME_KEY = "my-sub"

/** The My page's stack of sub pages (empty is the home page). */
internal object MyStack {
    fun push(stack: List<String>, v: String): List<String> = stack + v
    fun pop(stack: List<String>): List<String> = stack.dropLast(1)
    fun isSub(stack: List<String>): Boolean = stack.isNotEmpty()
}

@Composable
private fun Home(
    profile: Profile, o: MyOverview, server: ServerUpdateStatus?, scroll: ScrollState, hooks: MyScreenHooks,
    go: (String) -> Unit, onSignedOut: () -> Unit, vm: MyOverviewViewModel,
) {
    val services = LocalAppServices.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val c = Kelo.colors
    val cleared = string(Res.string.my_clear_chat_done)
    val updates = services.appUpdates
    val update = updates?.state?.collectAsStateWithLifecycle()?.value
    val sections = builtInSections(profile, o, server, update, go, hooks, onClearChat = { confirmClear = true }) + hooks.extraSections + nestSections()

    val frame = LocalPageFrame.current   // a window: below the app's header, centred, at most this wide (the phone: whole screen)
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.then(if (frame.maxWidth.isSpecified) Modifier.widthIn(max = frame.maxWidth) else Modifier).fillMaxSize().verticalScroll(scroll)
            .padding(start = 8.dp, end = 8.dp, top = headerHeight() + frame.topInset + 4.dp, bottom = hooks.bottomInset() + 16.dp),
    ) {
        hooks.topCard()
        // a newer build of this app is out: its download (or Play's), check and install start here
        if (updates != null && update?.offer != null) {
            UpdateCard(
                update, onGet = updates::get, onInstall = updates::install,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
            )
        }
        // the owner's server: a newer release, an update running, or one that failed or was undone in the last day
        ServerUpdateRules.card(server, System.currentTimeMillis() / 1000.0)?.let { card ->
            ServerUpdateCard(card, onClick = { go(MyView.SERVER_UPDATE) }, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
        }
        sections.forEach { s ->
            SectionLabel(s.title)
            MenuGroup(s.rows)
        }

        Box(Modifier.height(16.dp))
        MenuGroup { row { MenuRow(KeloIcons.ArrowRight, string(Res.string.my_logout), { confirmLogout = true }, danger = true) } }
        AppVersionLine(
            appVersion = services.appVersion, serverVersion = o.version, sourceCommit = services.sourceCommit, sourceDirty = services.sourceDirty,
            openUrl = { url -> services.browser.open(url) },
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
    }
    }

    if (confirmLogout) {
        ConfirmDialog(
            text = string(Res.string.my_logout_confirm), yes = string(Res.string.my_logout),
            onYes = { scope.launch { services.sessions.signOutAndForget(); onSignedOut() } },   // this device leaves the server's list too
            onDismiss = { confirmLogout = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            text = string(Res.string.my_clear_chat_confirm), yes = string(Res.string.delete),
            onDismiss = { confirmClear = false },
            onYes = {
                scope.launch {
                    val error = vm.clearChat { services.notifications?.cancelAll() }   // the chat and what came of it (ClearHistory)
                    toaster.show(error ?: cleared)
                }
            },
        )
    }
}

/** The built-in sections of the home list, in order; the operator's only for an owner sign-in. */
/** The Nest `my_section` entries under a header with the package's name, each drawn by the app's NestSurfaceView (none without one). */
@Composable
private fun nestSections(): List<MySection> {
    val view = chat.kelo.app.shell.LocalNestSurfaceView.current ?: return emptyList()
    return chat.kelo.app.shell.nestEntries(chat.kelo.app.shell.NestPlace.MY_SECTION).map { e ->
        MySection("nest/${e.key}", e.packageName.ifBlank { e.title }) { row { view.Show(e, Modifier) } }
    }
}

@Composable
private fun builtInSections(
    profile: Profile, o: MyOverview, server: ServerUpdateStatus?, update: chat.kelo.app.update.UpdateState?,
    go: (String) -> Unit, hooks: MyScreenHooks, onClearChat: () -> Unit,
): List<MySection> {
    val services = LocalAppServices.current
    val c = Kelo.colors
    val connected = o.creds.filter { it.hasSecret && (it.kind == VaultEntry.KIND_API || it.kind == VaultEntry.KIND_EMAIL) }
    val notifications = services.notifications
    val locked by services.sessions.locked.collectAsStateWithLifecycle()
    val updates = services.appUpdates
    val list = ArrayList<MySection>()

    list += MySection("account", string(Res.string.my_section_account)) {
        row { MenuRow(KeloIcons.User, string(Res.string.profile_title), { go(MyView.PROFILE) }, value = profile.userName.ifEmpty { string(Res.string.my_name_missing) }) }
        row { MenuRow(KeloIcons.Phone, string(Res.string.devices_title), { go(MyView.DEVICES) }, value = o.devices?.let { string(Res.string.count_devices, it) }) }
        row {
            val sizes = o.sizes?.let { p -> profileText(p, long = false)?.let { string(it) } ?: p }
            // a storage warning replaces the size profile, in red when something stops soon
            val storage = StorageRules.fromKey(o.storage)
            val warning = when (storage) {
                StorageState.HOST_LOW -> string(Res.string.storage_row_host)
                StorageState.CRITICAL -> string(Res.string.storage_row_critical)
                StorageState.WARN -> string(Res.string.storage_row_warn, o.storagePercent ?: 80)
                StorageState.OK -> null
            }
            MenuRow(
                KeloIcons.Chip, string(Res.string.resources_title), { go(MyView.RESOURCES) }, value = warning ?: sizes,
                valueColor = when { warning == null -> null; StorageRules.urgent(storage) -> c.danger; else -> c.warn },
            )
        }
        if (ServerUpdateRules.shown(server)) row {
            // the version the server runs; in the accent colour while a newer one waits
            MenuRow(
                KeloIcons.Cloud, string(Res.string.server_update_title), { go(MyView.SERVER_UPDATE) },
                value = server?.current?.takeIf { it.isNotBlank() },
                valueColor = if (ServerUpdateRules.canUpdate(server)) c.accent else null,
            )
        }
    }
    list += MySection("agent", string(Res.string.my_section_agent)) {
        row { MenuRow(KeloIcons.Spark, string(Res.string.my_agent_title), { go(MyView.AGENT) }, value = profile.agentName) }
    }
    list += MySection("model", string(Res.string.my_section_model)) {
        row { MenuRow(KeloIcons.Chat, string(Res.string.model_title), { go(MyView.MODEL) }, value = profile.model.ifEmpty { null }?.let { chat.kelo.app.chat.ModelNames.display(it, null) } ?: string(if (profile.modelReady == false) Res.string.model_not_chosen else Res.string.model_sheet_server_default)) }
        row { MenuRow(KeloIcons.Pulse, string(Res.string.usage_title), { go(MyView.USAGE) }, value = usageSummary(o.usage, profile.model)) }
    }
    // a tab the owner's layout hides stays one tap away: 커넥터 has the row below already, the others get one of their own
    val hiddenTabRows = hooks.hiddenTabs.filter { it.id != chat.kelo.app.shell.Routes.CONNECTORS }.map { it to it.title.text() }
    list += MySection("connections", string(Res.string.my_section_connections)) {
        row {
            MenuRow(
                KeloIcons.Plug, string(Res.string.my_services), hooks.openConnectors,
                value = plural(Res.plurals.my_services_count, connected.size, connected.size),
            )
        }
        hiddenTabRows.forEach { (tab, label) -> row { MenuRow(tab.icon, label, { hooks.openTab(tab.id) }) } }
    }
    list += MySection("security", string(Res.string.my_section_security)) {
        row {
            val bad = o.creds.any { it.isBad }
            MenuRow(
                KeloIcons.Key, string(Res.string.vault_title), { go(MyView.VAULT) },
                value = when {
                    bad -> string(Res.string.my_vault_check)
                    o.locked -> string(Res.string.my_vault_locked)
                    o.creds.isNotEmpty() -> string(Res.string.count_items, o.creds.size)
                    else -> null
                },
                valueColor = if (bad) c.danger else null,
            )
        }
        row {
            MenuRow(
                KeloIcons.Card, string(Res.string.wallet_title), { go(MyView.WALLET) },
                value = o.cards?.takeIf { it > 0 }?.let { plural(Res.plurals.wallet_card_count, it, it) },
                badge = o.walletUnseen?.takeIf { it > 0 },
            )
        }
        row { MenuRow(KeloIcons.Shield, string(Res.string.agent_tab_permissions), { go(MyView.PERMISSIONS) }, value = string(Res.string.my_perm_value)) }
        row { MenuRow(KeloIcons.Lock, string(Res.string.my_privacy_title), { go(MyView.PRIVACY) }) }
        // the key of passwords, keys and cards on the owner's phones (Keyguard; owner only, the page says so otherwise)
        if (hooks.keyguardPage != null) row {
            MenuRow(KeloIcons.Shield, string(Res.string.keyguard_title), { go(MyView.KEYGUARD) },
                value = if (locked) string(Res.string.keyguard_locked_title) else null, valueColor = if (locked) c.danger else null)
        }
    }
    list += MySection("data", string(Res.string.my_section_data)) {
        row { MenuRow(KeloIcons.File, string(Res.string.docs_title), { go(MyView.DOCUMENTS) }, value = o.documents?.let { string(Res.string.count_items, it) }) }
        row { MenuRow(KeloIcons.File, string(Res.string.files_title), { go(MyView.FILES) }) }
        row { MenuRow(KeloIcons.Book, string(Res.string.my_records_title), { go(MyView.RECORDS) }, value = string(Res.string.my_records_value)) }
        row { MenuRow(KeloIcons.Trash, string(Res.string.my_clear_chat), onClearChat, danger = true) }
    }
    list += MySection("app", string(Res.string.my_section_app)) {
        if (notifications != null) row {
            // notifications: the phone's current state; the page has the rest (and the agent writing first)
            val on = notifications.isOn()
            // signed in with the access key: the server keeps push tokens only for paired phones (403), so say so here
            val base = services.sessions.api.value?.baseUrl
            val needsDevice = base != null && remember(base, o) { notifications.pushNeedsDevice(base) }
            MenuRow(
                KeloIcons.Bell, string(Res.string.notify_my_row), { go(MyView.NOTIFY) },
                value = string(
                    when {
                        on && needsDevice -> Res.string.notify_state_needs_device
                        on -> Res.string.notify_state_on
                        else -> Res.string.notify_state_off
                    },
                ),
                valueColor = if (on && !needsDevice) null else c.danger,
            )
        }
        row { MenuRow(KeloIcons.Globe, string(Res.string.my_language), { go(MyView.LANGUAGE) }, value = Languages.override(services.language.current)?.let(Languages::name) ?: string(Res.string.language_system)) }
        hooks.appRows?.invoke(this, go)
        if (updates != null && update?.enabled == true) {
            // the running version, and a check right now (the Play build asks Google Play)
            row {
                MenuRow(
                    KeloIcons.ArrowDown, string(Res.string.update_check), { updates.check(force = true) },
                    value = listOfNotNull(string(Res.string.my_version, update.running), checkValue(update)).joinToString(" · "),
                    valueColor = if (update.offer != null) c.accent else null,
                )
            }
        }
    }
    if (o.operator == true) {
        list += MySection("operator", string(Res.string.my_section_operator)) {
            row { MenuRow(KeloIcons.Cloud, string(Res.string.operator_page_title), { go(MyView.OPERATOR) }) }
        }
    }
    return list
}
