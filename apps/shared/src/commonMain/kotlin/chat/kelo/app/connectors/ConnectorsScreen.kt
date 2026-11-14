package chat.kelo.app.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.unit.isSpecified
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.platform.LocalPageFrame
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.kelo.app.LocalAppServices
import chat.kelo.app.platform.Section
import chat.kelo.app.my.VaultEntry
import chat.kelo.app.res.*
import chat.kelo.app.ui.PushHost
import chat.kelo.app.ui.components.BackTitleBar
import chat.kelo.app.ui.components.Card
import chat.kelo.app.ui.components.ChoiceChip
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.Segmented
import chat.kelo.app.ui.components.SkeletonBar
import chat.kelo.app.ui.components.SkeletonLines
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.components.StaleHint
import chat.kelo.app.ui.components.TokenText
import chat.kelo.app.ui.components.rememberSkeletonShown
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalChromeHider
import chat.kelo.app.ui.platform.LocalReloadOnReturn
import chat.kelo.app.ui.platform.TabPageFrame
import chat.kelo.app.ui.platform.headerHeight
import chat.kelo.app.ui.platform.keloViewModel
import chat.kelo.app.ui.platform.rememberPageViewModelStoreOwner
import chat.kelo.app.ui.text.plural
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo

/**
 * The 커넥터 tab: the services the agent can use, searchable and filterable by group; the connected ones pinned on top, the rest by
 * group, with a badge for each; tapping one opens its detail over the whole screen (the app's bars step aside) with the connect panel; the back
 * arrow or the phone's (predictive) back gesture returns to the list where it was. [openId] opens that connector straight away
 * (the chat's connect card). The list and the open panel are refreshed whenever the app comes back to the foreground, so a
 * sign-in finished in the browser shows up on return.
 */
@Composable
fun ConnectorsScreen(openId: String? = null, frame: TabPageFrame = TabPageFrame.None) {
    val sessions = LocalAppServices.current.sessions
    val vm = keloViewModel("connectors") { ConnectorsViewModel(sessions, openId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LocalReloadOnReturn.current(Unit) { vm.reload() }   // on the phone: every return to the app; on the desktop: every return to the window
    // kept here, so coming back from a detail finds the list where it was
    val listState = rememberLazyListState()
    val open: (String?) -> Unit = { id -> vm.open(id); frame.hideChrome(CHROME_KEY, id != null) }
    // tapping 커넥터 again: back to the list (from a detail), or to the top of the list
    LaunchedEffect(frame) {
        frame.reselect.collect { if (vm.state.value.openId != null) open(null) else listState.animateScrollToItem(0) }
    }
    LocalChromeHider.current(CHROME_KEY, state.openId != null)

    // a wide window (the desktop): the connector opens beside the list; otherwise (the phone, a narrow window) it covers the list
    val pageFrame = LocalPageFrame.current
    val phoneData = LocalAppServices.current.phoneData
    // Google Calendar's "휴대폰 캘린더로 연결하기": back to the list, down to 이 기기에서, and the calendar row's sheet opens
    val onPhoneCalendar: (() -> Unit)? = phoneData?.let { { vm.showPhoneCalendar(); frame.hideChrome(CHROME_KEY, false) } }
    val detail: @Composable (String) -> Unit = { id ->
        ConnectorDetail(id, ConnectorRules.find(state.list, id), state.creds, loading = !state.loaded, onBack = { open(null) }, onChanged = vm::reload,
            onPhoneCalendar = onPhoneCalendar)
    }
    val list: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.then(if (pageFrame.maxWidth.isSpecified) Modifier.widthIn(max = pageFrame.maxWidth) else Modifier).fillMaxSize()) {
                ConnectorList(state, vm, listState, headerHeight() + pageFrame.topInset, frame.bottomInset, state.openId, onOpen = { open(it) })
                // above the tab bar, where it covers no title or search field
                StaleHint(state.offline, Modifier.align(Alignment.BottomCenter).padding(bottom = frame.bottomInset + 12.dp), onRetry = vm::reload)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = LocalFormFactor.current == FormFactor.Desktop && maxWidth > WIDE_WINDOW
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { list() }
                state.openId?.let { id ->
                    VerticalDivider(color = Kelo.colors.line)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = pageFrame.topInset)) { detail(id) }
                }
            }
        } else {
            PushHost(
                state = state.openId.orEmpty(),
                depth = { if (it.isEmpty()) 0 else 1 },
                back = if (state.openId != null) "" else null,
                onBack = { open(null) },
                modifier = Modifier.fillMaxSize(),
            ) { id -> if (id.isEmpty()) list() else Box(Modifier.fillMaxSize().padding(top = pageFrame.topInset)) { detail(id) } }
        }
    }
}

/** From this width on a window shows the open connector beside the list. */
private val WIDE_WINDOW = 880.dp

private const val CHROME_KEY = "connector-detail"

private val GroupShape = RoundedCornerShape(12.dp)
private val FirstShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val LastShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)

@Composable
private fun ConnectorList(
    state: ConnectorsState, vm: ConnectorsViewModel, listState: LazyListState, top: Dp, bottom: Dp, selectedId: String?, onOpen: (String) -> Unit,
) {
    val c = Kelo.colors
    // filtered and grouped only when the list, the search or the filter changes, not on every recomposition
    val shown = remember(state.list, state.query) { state.shown }
    // opening view: connected and popular only; a search covers everything; the full list (with group chips) on request
    val home = remember(state.list, state.creds) { ConnectorList.home(state.list, state.creds) }
    val full = state.showAll || home == null
    val searching = state.query.isNotBlank()
    val sections = remember(shown, state.creds, state.filter, full, searching, home) {
        when {
            searching -> ConnectorList.sections(shown, state.creds)
            !full -> home.orEmpty()
            else -> ConnectorList.sections(shown, state.creds, state.filter)
        }
    }
    val chips = remember(state.list) { ConnectorList.filters(state.list) }
    val hidden = remember(state.list, state.creds) { ConnectorList.hiddenCount(state.list, state.creds) }
    val skeleton = rememberSkeletonShown(!state.loaded)
    val phoneData = LocalAppServices.current.phoneData
    LaunchedEffect(state.phoneCalendar, sections, skeleton) {
        // the "device" item comes after "top" and, per section, its head, its rows and its gap
        if (state.phoneCalendar && !skeleton) listState.animateScrollToItem(1 + sections.sumOf { it.items.size + 2 })
    }
    LazyColumn(
        Modifier.fillMaxSize().background(c.bg).imePadding(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = bottom + 24.dp),
    ) {
        item(key = "top", contentType = "top") {
            KeloTextField(
                state.query, vm::setQuery, string(Res.string.connectors_search),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.height(10.dp))
            if (full && !searching && home != null) {
                LinkButton(string(Res.string.connectors_back_to_popular), onClick = { vm.setShowAll(false) })
            }
            if (full && !searching && chips.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(string(Res.string.connectors_filter_all), selected = state.filter == null, onClick = { vm.setFilter(null) })
                    chips.forEach { ch ->
                        ChoiceChip(ch.label?.let { string(it) } ?: ch.title, selected = state.filter == ch.key,
                            onClick = { vm.setFilter(if (state.filter == ch.key) null else ch.key) })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        if (skeleton) {
            item(key = "loading", contentType = "loading") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBar(width = 90.dp, height = 12.dp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    SkeletonRows(4)
                    SkeletonBar(width = 70.dp, height = 12.dp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    SkeletonRows(3)
                }
            }
            return@LazyColumn
        }
        sections.forEach { section ->
            val category = section.key
            val items = section.items
            item(key = "head:$category", contentType = "head") {
                Text(
                    section.label?.let { string(it) } ?: section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when (category) { "reconnect" -> c.danger; "connected", "popular" -> c.accent; else -> c.muted },
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp),
                )
            }
            // one lazy item per connector (not one big item per group), so only the rows on screen are composed
            itemsIndexed(items, key = { _, x -> "row:${x.id}" }, contentType = { _, _ -> "row" }) { i, x ->
                val shape = when {
                    items.size == 1 -> GroupShape
                    i == 0 -> FirstShape
                    i == items.lastIndex -> LastShape
                    else -> RectangleShape
                }
                Column(Modifier.fillMaxWidth().background(c.card, shape)) {
                    if (i == 0) Spacer(Modifier.height(2.dp))
                    if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 16.dp))
                    ConnectorRow(x, shape, ConnectorList.reconnect(x, state.creds) != null, x.id == selectedId, onOpen)
                    if (i == items.lastIndex) Spacer(Modifier.height(2.dp))
                }
            }
            item(key = "gap:$category", contentType = "gap") { Spacer(Modifier.height(14.dp)) }
        }
        if (!searching && state.filter == null) {
            // what this phone itself can share (calendar, contacts, messages, dialer, notifications, call log, Health Connect): not
            // services to sign in to, one switch each; also found in 마이 → 권한
            if (phoneData != null) item(key = "device", contentType = "device") {
                phoneData.Section(openCalendar = state.phoneCalendar, onCalendarOpened = vm::phoneCalendarShown)
                Spacer(Modifier.height(14.dp))
            }
        }
        if (!full && !searching && hidden > 0) {
            item(key = "all", contentType = "all") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { vm.setShowAll(true) }.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(plural(Res.plurals.connectors_show_all, hidden, hidden), color = c.muted,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.loaded && shown.isEmpty()) {
            item(key = "empty") {
                if (state.failed) {
                    MutedText(string(Res.string.connect_failed))
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(string(Res.string.retry), onClick = vm::reload)
                } else {
                    MutedText(string(Res.string.connectors_empty))
                }
            }
        }
    }
}

@Composable
private fun ConnectorRow(x: Connector, shape: Shape, broken: Boolean, selected: Boolean, onOpen: (String) -> Unit) {
    val c = Kelo.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(Kelo.colors.accent.copy(alpha = 0.08f)) else Modifier)   // beside the list, the open one is marked
            .clickable(interactionSource = source, indication = ripple()) { onOpen(x.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectorTile(x, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(x.name, color = c.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (x.description.isNotBlank()) Text(
                x.description, color = c.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            broken -> ReconnectBadge()
            ConnectorRules.pendingReview(x) -> ReviewPill()
            else -> StatusBadge(ConnectorRules.badge(x.status))
        }
        if (x.limited) LimitedChip()
    }
}

/**
 * One connector, full screen: a large tile with its name and state, what Kelo can do with it (the server's own words), how it is
 * connected, who is connected and with which permissions when that is known, a warning when it needs connecting again, then the
 * connect panel (connect, connect again, disconnect with a question first). [x] is null while the list is still loading.
 */
@Composable
private fun ConnectorDetail(
    id: String, x: Connector?, creds: List<VaultEntry>, loading: Boolean, onBack: () -> Unit, onChanged: () -> Unit,
    onPhoneCalendar: (() -> Unit)?,
) {
    // the detail's view models live as long as the detail is open
    val owner = rememberPageViewModelStoreOwner("connector-detail:$id")
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { ConnectorDetailContent(x, creds, loading, onBack, onChanged, onPhoneCalendar) }
}

@Composable
private fun ConnectorDetailContent(
    x: Connector?, creds: List<VaultEntry>, loading: Boolean, onBack: () -> Unit, onChanged: () -> Unit, onPhoneCalendar: (() -> Unit)?,
) {
    val c = Kelo.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        BackTitleBar(x?.name.orEmpty(), onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (x == null) {
                if (loading || rememberSkeletonShown(loading)) {
                    SkeletonBar(width = 64.dp, height = 64.dp)
                    SkeletonBar(fraction = 0.5f, height = 26.dp)
                    Card { SkeletonLines(4) }
                } else {
                    MutedText(string(Res.string.connector_gone))
                }
                return@Column
            }
            val broken = ConnectorList.reconnect(x, creds)
            val account = ConnectorList.account(x, creds)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectorTile(x, 64.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(TokenText.fit(x.name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        broken != null -> ReconnectBadge()
                        ConnectorRules.pendingReview(x) -> ReviewPill()
                        else -> StatusBadge(ConnectorRules.badge(x.status))
                    }
                }
            }
            if (broken != null) {
                val cred = creds.firstOrNull { it.name == x.item }
                Card {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(KeloIcons.Alert, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(string(Res.string.connector_reconnect_title), fontWeight = FontWeight.SemiBold, color = c.ink, style = MaterialTheme.typography.bodyLarge)
                            Text(string(broken.text), color = c.ink, style = MaterialTheme.typography.bodySmall)
                            val detail = cred?.lastResultDetail.orEmpty()
                            if (broken == Reconnect.REFUSED && detail.isNotBlank()) MutedText(string(Res.string.connector_reconnect_detail, detail), small = true)
                        }
                    }
                }
            }
            if (x.description.isNotBlank()) {
                DetailSection(string(Res.string.connector_can_do)) {
                    Text(x.description, color = c.ink, style = MaterialTheme.typography.bodyLarge)
                }
            }
            val method = if (x.status == "connected") ConnectorList.methodOf(x) else ConnectorList.method(x.connect)
            DetailSection(string(Res.string.connector_facts)) {
                FactRow(string(Res.string.connector_method), method?.let { string(it.label) } ?: string(Res.string.connector_method_none))
                if (account.isNotBlank()) FactRow(string(Res.string.connector_account), account)
                else if (x.status == "connected") FactRow(string(Res.string.connector_account), string(Res.string.connector_account_unknown))
                if (x.site.isNotBlank()) FactRow(string(Res.string.connector_site), ConnectorRules.host(x.site).ifBlank { x.site })
                if (x.connect == "device" && x.status == "connected") DeviceScopes(x.via.ifBlank { x.id }, x.account)
            }
            if (x.status == "connected") PermissionsSection(x.id)
            if (x.connect != null) {
                // needing a new connection: the panel offers connecting again (the stored one is replaced), and it can still go
                Card { ConnectPanel(x.id, connector = if (broken != null) x.copy(status = "available") else x, onChanged = onChanged, onPhoneCalendar = onPhoneCalendar) }
                if (broken != null && x.item.isNotBlank()) BrokenDisconnect(x, onChanged)
            }
            if (x.status == "coming_soon") MutedText(string(Res.string.connector_coming_soon_note), small = true)
            if (x.status == "unsupported") MutedText(string(Res.string.connector_unsupported_note), small = true)
        }
    }
}

/** 연결 끊기 for a connection that needs doing again (the panel then shows only how to connect). */
@Composable
private fun BrokenDisconnect(x: Connector, onChanged: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val vm = viewModel(key = "connector-detail") { ConnectorDetailViewModel(sessions) }
    var asking by remember(x.id) { mutableStateOf(false) }
    LinkButton(string(Res.string.connector_disconnect), onClick = { asking = true }, danger = true)
    if (asking) {
        ConfirmDialog(
            string(Res.string.mcp_disconnect_confirm, x.name), string(Res.string.connector_disconnect),
            onYes = { vm.disconnect(x.item, onChanged) },
            onDismiss = { asking = false },
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Kelo.colors.muted, modifier = Modifier.padding(start = 2.dp))
        Card { content() }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    val c = Kelo.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.35f))
        Text(TokenText.fit(value, 24), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

/**
 * The permission a connected code-connected service was given (GET /api/oauth/providers: its `granted_scope` among its `scopes`).
 * Nothing when that choice is not known; [account] refetches after a new connection.
 */
@Composable
private fun DeviceScopes(provider: String, account: String) {
    val sessions = LocalAppServices.current.sessions
    val vm = viewModel(key = "connector-detail") { ConnectorDetailViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(provider, account) { vm.loadGranted(provider) }
    val s = ui.granted ?: return
    val c = Kelo.colors
    Text(string(Res.string.connector_permissions), color = c.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(KeloIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(TokenText.fit(s.label.ifBlank { s.id }), color = c.ink, style = MaterialTheme.typography.bodyMedium)
            if (s.detail.isNotBlank()) Text(s.detail, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
