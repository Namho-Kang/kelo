package chat.kelo.app.chat

import chat.kelo.app.shell.NestHomeCards
import chat.kelo.app.shell.NestPlace
import chat.kelo.app.shell.nestEntries
import chat.kelo.app.chat.vault.SecretEntryCard
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.LocalTextSharer
import chat.kelo.app.ui.platform.MessageContextArea
import chat.kelo.app.ui.platform.headerHeight
import chat.kelo.app.ui.platform.rememberViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun Messages(
    state: ChatState, agentName: String, vm: ChatViewModel, hooks: ChatScreenHooks, onOpenConnector: (String) -> Unit,
    onAction: (ChatItem, String, MsgAction) -> Unit, modifier: Modifier, onOpenBrowser: (ChatItem.Browser) -> Unit = {},
    bottom: Dp = 0.dp,
    /** The wallet's card form (an address question answered with "Add a card") */
    onOpenCardForm: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    /** The "↓" button, drawn by the screen over the composer: this list tells it when to show and where it leads */
    jump: JumpButtonState = remember { JumpButtonState() },
) {
    val list = rememberLazyListState()
    // the open secure entries from the server's list join the rows under the message that asked (a reload, another phone)
    // tool chips of one kind in a row fold into one chip that grows in place (ChatReducer.foldTools)
    val rows = remember(state.items, state.secrets) { ChatReducer.foldTools(ChatReducer.withPending(state.items, state.secrets)) }.asReversed()
    // the messages that start a new stretch (first shown, a new day, or TimeLabels.GAP_MINUTES after the one before) get a time line
    val timed = remember(state.items) { TimeLabels.labelled(state.items, java.time.ZoneId.systemDefault()) }
    val timeLabeler = rememberTimeLabeler()
    val top = headerHeight()
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val services = LocalAppServices.current
    val reduce = LocalReduceMotion.current
    // an end-to-end card goes from this device only after the owner's check (the app's CardSenders: decrypt, send to the approval's
    // slot, wipe); a PIN goes the same way
    val senders = hooks.cardSenders()
    fun cardSender(approvalId: String, cardId: String) = senders.card(approvalId, cardId)
    fun pinSender(slotId: String, pinId: String) = senders.pin(slotId, pinId)
    LaunchedEffect(state.pinNeeds) {
        state.pinNeeds.filter { it.send == CardSend.Idle && vm.takePinOnAllow(it.slotId) }.forEach { n -> vm.releasePin(n.slotId, pinSender(n.slotId, n.pinId)) }
    }
    val sessions = services.sessions
    val stepCalls = remember(sessions) { { PaymentStepCalls(sessions.requireApi()) } }
    fun stepActions(step: PaymentStep) = PaymentStepActions(
        answer = { answer, code -> haptics.tick(); vm.answerPaymentStep(step.stepId, answer, code) },
        openLive = { services.openRequests.value = chat.kelo.app.notify.OpenTarget.Tab(step.tab.ifEmpty { "owner" }, control = true) },
        padImage = { runCatching { stepCalls().pad(step.stepId) }.getOrNull() },
        padTap = { x, y -> haptics.tick(); runCatching { stepCalls().tap(step.stepId, x, y) }.getOrNull() },
        padClear = { runCatching { stepCalls().clear(step.stepId) }.getOrNull() },
    )
    LaunchedEffect(hooks.reselect) { hooks.reselect.collect { list.animateScrollToItem(0) } }   // newest first: the list is drawn from the bottom up

    // follow the newest row, unless the user scrolled up to read older ones
    val last = state.items.lastOrNull()
    // the reply being written right now: the newest row, from the assistant, made in this session (not history)
    val streamingKey = (last as? ChatItem.Msg)?.takeIf { !it.mine && it.id == null }?.key
    LaunchedEffect(state.items.size, state.phase?.kind, last?.key) {
        // requested, not run here: a bubble that comes and goes within one frame (a message the server held back) would otherwise
        // scroll a list whose composition is still pending
        if (ChatScroll.follow(list.firstVisibleItemIndex, last is ChatItem.Msg && last.mine)) list.requestScrollToItem(0)
    }
    // questions from a background job or a scheduled run, live at the very bottom (newest lowest); a new one pulls the list down
    // like a new row does, or lights the "↓" pill's dot when the reader is up the list
    val asks = remember(state.asks, state.items) { ChatAsks.visible(state.asks, state.items) }
    val openAsks = asks.filter { it.open }.map { it.id }
    var askUnread by remember { mutableStateOf(false) }
    var askIdsBefore by remember { mutableStateOf(openAsks.toSet()) }
    LaunchedEffect(openAsks) {
        val arrived = openAsks.any { it !in askIdsBefore }
        askIdsBefore = openAsks.toSet()
        if (arrived) {
            if (ChatScroll.follow(list.firstVisibleItemIndex, false)) list.animateScrollToItem(0) else askUnread = true
        }
    }
    // a follow-up notification asked for one message: scroll to it once it is in the list (a fresh reload brings it)
    val focusFlow = services.chatFocus
    val focus by focusFlow.collectAsState()
    var focusReloaded by remember { mutableStateOf(false) }
    LaunchedEffect(focus, rows.size) {
        val id = focus ?: return@LaunchedEffect
        if (!focusReloaded) vm.loadFollowups()
        val i = rows.indexOfFirst { it is ChatItem.Msg && it.id == id }
        if (i >= 0) {
            list.animateScrollToItem(i + if (state.phase != null) 1 else 0)
            focusFlow.value = null
            focusReloaded = false
        } else if (!focusReloaded) {
            focusReloaded = true
            vm.reload()
        } else if (state.loaded) {
            focusFlow.value = null   // not among the newest messages: the chat simply opens at the bottom
            focusReloaded = false
        }
    }
    // a row in 답이 필요해요 asked for a secure entry's card: scroll to it and mark it for a moment
    val entryFlow = services.chatEntryFocus
    val entryFocus by entryFlow.collectAsState()
    var entryAsked by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entryFocus, rows.size) {
        val id = entryFocus ?: return@LaunchedEffect
        val i = rows.indexOfFirst { it is ChatItem.Secret && it.request.id == id }
        val before = asks.size + state.pinNeeds.takeLast(2).size + PaymentStepRules.visible(state.paymentSteps).size + if (state.phase != null) 1 else 0
        when {
            i >= 0 -> {
                list.animateScrollToItem(i + before)
                entryFlow.value = null
                entryAsked = false
                marked = id
            }
            !entryAsked -> { entryAsked = true; vm.refreshSecrets() }
            else -> { delay(ENTRY_WAIT_MS); entryFlow.value = null; entryAsked = false }   // gone meanwhile: the chat stays where it is
        }
    }
    // the mark goes after a moment (its own effect: a list change must not cancel it)
    LaunchedEffect(marked) {
        if (marked == null) return@LaunchedEffect
        delay(ENTRY_MARK_MS)
        marked = null
    }
    // a reply an offer card started elsewhere (the Work page): follow it here, once
    val follow by services.chatFollowTurn.collectAsStateWithLifecycle()
    LaunchedEffect(follow) { follow?.let { id -> vm.followTurn(id); services.chatFollowTurn.value = null } }

    // older messages when the top is reached
    LaunchedEffect(list) {
        snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to list.layoutInfo.totalItemsCount }
            .map { (lastShown, total) -> total > 0 && lastShown >= total - 3 }
            .distinctUntilChanged()
            .collect { nearTop -> if (nearTop) vm.loadOlder() }
    }
    // the "↓" pill: shown away from the newest row, with a dot when the assistant wrote more meanwhile
    val slop = with(LocalDensity.current) { 24.dp.roundToPx() }
    val atBottom by remember { derivedStateOf { ChatScroll.atBottom(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, slop) } }
    val signature = ChatScroll.replySignature(state.items)
    var seenSignature by remember { mutableLongStateOf(signature) }
    var unread by remember { mutableStateOf(false) }
    LaunchedEffect(signature, atBottom, askUnread) {
        unread = ChatScroll.unread(unread, atBottom, signature != seenSignature || askUnread)
        seenSignature = signature
        askUnread = false
    }
    val showJump = !atBottom && state.items.isNotEmpty()
    SideEffect {
        jump.shown = showJump
        jump.unread = unread
        jump.onClick = {
            haptics.tick()
            scope.launch { if (reduce) list.scrollToItem(0) else list.animateScrollToItem(0) }
        }
    }

    val homeCards = nestEntries(NestPlace.HOME_CARD)
    if (state.loaded && state.items.isEmpty() && state.phase == null && asks.isEmpty()) {
        // made (and GET /api/offers asked) only while the empty chat shows them: the server counts every listed offer as seen
        val offersVm = rememberViewModel { chat.kelo.app.offers.OffersViewModel(services.sessions) }
        Box(modifier.fillMaxWidth().padding(top = top).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(string(Res.string.chat_hint, agentName), color = Kelo.colors.muted, style = MaterialTheme.typography.bodyLarge)
                chat.kelo.app.offers.OffersList(offersVm, onStarted = { id -> vm.followTurn(id) })
                NestHomeCards(homeCards)
            }
        }
        return
    }
    Box(modifier.fillMaxWidth()) {
        // like every other page: the messages scroll on under the floating agent and its name tag (only the shell's short status-bar
        // fade above them); the top padding starts the first message below the tag. At the bottom the list runs on under the
        // composer's scrim, and the "↓" sits on the composer (ChatOverlayRules)
        DismissKeyboardOnDrag(list)
        LazyColumn(
            Modifier.fillMaxSize().dismissKeyboardOnTouch(),
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 10.dp, bottom = maxOf(bottom, 10.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            items(asks.asReversed(), key = { "ask:" + it.id }, contentType = { "ask" }) { ask ->
                Box(Modifier.animateItem(fadeInSpec = if (reduce) null else tween(Motion.SMALL_MS), placementSpec = if (reduce) null else Motion.rise(),
                    fadeOutSpec = if (reduce) null else tween(Motion.SMALL_MS))) {
                    ChatAskCard(ask, onSolve = {
                        haptics.tick()
                        services.openRequests.value = chat.kelo.app.notify.OpenTarget.Tab(ask.approval.tab, control = true)
                    }) { ok, rememberSite, rememberSchedule ->
                        val card = ask.approval.event.card
                        if (ok && card != null && card.needsCard) {
                            haptics.tick()
                            vm.answerAsk(ask.id, true, sendCard = cardSender(ask.id, card.card.id))
                        } else {
                            if (ok) haptics.confirm() else haptics.reject()
                            vm.answerAsk(ask.id, ok, rememberSite = rememberSite, rememberSchedule = rememberSchedule)
                        }
                    }
                }
            }
            // payment steps that need the user, and PINs waiting for this phone: time-critical, at the very bottom
            items(state.pinNeeds.takeLast(2).asReversed(), key = { "pin:" + it.slotId }, contentType = { "pin" }) { need ->
                val send = { haptics.tick(); vm.releasePin(need.slotId, pinSender(need.slotId, need.pinId)) }
                val card = hooks.pinCard
                if (card != null) card(need, send) else PinNeedCard(need, send)
            }
            items(PaymentStepRules.visible(state.paymentSteps).asReversed(), key = { "step:" + it.stepId }, contentType = { "step" }) { step ->
                PaymentStepCard(step, stepActions(step), approvalNote = hooks.approvalNote)
            }
            state.phase?.let { p -> item(key = "status") { StatusLine(p) } }
            items(rows, key = { it.key }, contentType = { it::class }) { item ->
                // rows made during this session rise into place; rows from history just appear
                val fresh = remember(item.key) { vm.takeFresh(item.key) }
                Box(
                    Modifier
                        .animateItem(fadeInSpec = null, placementSpec = if (reduce) null else Motion.rise(), fadeOutSpec = if (reduce) null else tween(Motion.SMALL_MS))
                        .riseIn(fresh && !reduce, fromEnd = item is ChatItem.Msg && item.mine),
                ) {
                    // the long-press menu: what this row offers right now (다시 시도 only on the newest reply or an error)
                    val actions = remember(item, state.items, state.busy) { MessageActions.actions(state.items, item.key, state.busy) }
                    when (item) {
                        is ChatItem.Msg -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (item.key in timed) TimeLine(timeLabeler.label(item.at))
                            if (item.mine) {
                                // the files sent with it (pictures as thumbnails, files as chips); the app's own sentence is not shown
                                if (item.files.isNotEmpty()) UserFiles(item.files, Modifier.fillMaxWidth().padding(start = 48.dp))
                                val lineWord = string(Res.string.chat_attachment_line, "\u0000")
                                val shown = remember(item.text, item.files, item.autoText, lineWord) {
                                    MessageFileRules.shownText(item.text, item.files, item.autoText) { lineWord.replace("\u0000", it) }
                                }
                                if (shown.isNotEmpty() || item.files.isEmpty()) BubbleMenu(actions, item.text, { a -> onAction(item, item.text, a) }) {
                                    UserBubble(shown, item.reaction, actions) { a -> onAction(item, item.text, a) }
                                }
                            }
                            else if (item.briefing != null) {
                                val uriHandler = LocalUriHandler.current
                                BriefingCardView(
                                    item.briefing!!,
                                    onTap = { t ->
                                        haptics.tick()
                                        when (t) {
                                            is BriefingTap.ChatAt -> services.chatFocus.value = t.messageId
                                            BriefingTap.Waiting -> services.openRequests.value = chat.kelo.app.notify.OpenTarget.Activity
                                            is BriefingTap.Task -> services.openRequests.value = chat.kelo.app.notify.OpenTarget.Task(t.id)
                                            BriefingTap.Goals -> onOpenGoals()
                                            is BriefingTap.Event -> Unit   // the card hands it to onOpenEvent
                                            is BriefingTap.Mail -> scope.launch { vm.setInput(loadString(Res.string.briefing_mail_ask, t.subject)) }
                                            is BriefingTap.Suggest -> vm.setInput(t.text)
                                        }
                                    },
                                    onHours = { h, accept ->
                                        scope.launch {
                                            runCatching {
                                                services.sessions.requireApi().post<kotlinx.serialization.json.JsonElement>(
                                                    if (accept) chat.kelo.app.my.ContactHoursRules.acceptPath(h.id) else chat.kelo.app.my.ContactHoursRules.keepPath(h.id))
                                            }
                                        }
                                    },
                                    onLink = { url -> runCatching { uriHandler.openUri(url) } },
                                    onOpenEvent = hooks.openCalendarAt?.let { open -> { t -> haptics.tick(); open(t.atMs) } },
                                )
                            } else {
                                if (item.text.isNotEmpty() || item.images.isEmpty()) {
                                    BubbleMenu(actions, item.text, { a -> onAction(item, item.text, a) }) {
                                        AssistantBubbles(item.text, streaming = state.busy && item.key == streamingKey, actions) { text, a -> onAction(item, text, a) }
                                    }
                                }
                                item.images.forEach { img -> ChatPicture(img) }
                                item.id?.let { id -> state.followups[id] }?.let { f -> chat.kelo.app.followup.MessageFollowups(f, state, vm) }
                                item.comparison?.let { ComparisonCardView(it, onChoose = { text -> haptics.tick(); vm.send(text) }) }
                                item.replies?.takeIf { !state.busy && QuickReplyRules.isOpen(state.items, item.key) }?.let { r ->
                                    QuickReplies(r, onSend = { text, answers -> haptics.tick(); vm.send(text, answers = answers) })
                                }
                            }
                        }
                        is ChatItem.Tool -> ToolChip(item)
                        is ChatItem.Approval -> ApprovalCard(item, onAnswer = { id, ok, rememberSite ->
                            if (ok && item.pin != null) vm.pinAllowed(id)   // its pin_needed row then asks for the owner's check at once
                            val card = item.card
                            if (ok && card != null && card.needsCard) {
                                haptics.tick()
                                vm.approveWithCard(id, cardSender(id, card.card.id))
                            } else {
                                if (ok) haptics.confirm() else haptics.reject()
                                vm.approve(id, ok, rememberSite = rememberSite)
                            }
                        }, onKeepForSchedule = { id -> haptics.confirm(); vm.approve(id, true, rememberSchedule = true) })
                        is ChatItem.Browser -> BrowserCard(item, onOpenLive = { onOpenBrowser(item) })
                        is ChatItem.CardNeeded -> CardNeededCard(item, onSend = {
                            haptics.tick()
                            vm.sendCard(item.approvalId, cardSender(item.approvalId, item.card.id))
                        })
                        is ChatItem.PaymentAuto -> PaymentAutoRow(item)
                        is ChatItem.ShippingForm -> ShippingFormCard(item) { outcome ->
                            vm.shippingAnswered(item.ask.formId, outcome)
                            // "Add a card" as the way to pay: the wallet's own card form, behind its lock
                            if (outcome is ShippingOutcome.Sent && outcome.payment == chat.kelo.app.wallet.ShippingAsk.PAY_CARD) onOpenCardForm()
                        }
                        is ChatItem.Secret -> {
                            val on = marked == item.request.id
                            val ring by androidx.compose.animation.animateColorAsState(
                                if (on) Kelo.colors.accent else Kelo.colors.accent.copy(alpha = 0f), Motion.small(reduce), label = "entry-mark")
                            SecretEntryCard(
                                item.request,
                                onSaved = { answer, result -> services.agentActivity.entryClosed(item.request.kind); vm.secretSaved(item.request, answer, result) },
                                onCancel = { haptics.tick(); services.agentActivity.entryClosed(item.request.kind); vm.secretCancelled(item.request) },
                                onGone = { vm.secretGone(item.request) },
                                modifier = Modifier.border(2.dp, ring, RoundedCornerShape(16.dp)),
                            )
                        }
                        is ChatItem.Vault -> VaultCardRow(item, vm)
                        is ChatItem.Connect -> ConnectCard(item, vm)
                        is ChatItem.KeySaved -> KeySavedCard(item)
                        is ChatItem.LocationAsk -> LocationAskCard(item, vm)
                        is ChatItem.Notice -> NoticeChip(noticeText(item.notice))
                        is ChatItem.Error -> {
                            val text = errorText(item.error)
                            BubbleMenu(actions, text, { a -> onAction(item, text, a) }) { ErrorBubble(text, actions) { a -> onAction(item, text, a) } }
                        }
                    }
                }
            }
            // Nest home cards (none without a NestSurfaceView): above the oldest message, in the empty/briefing area
            if (homeCards.isNotEmpty()) item(key = "nest-home", contentType = "nest-home") { NestHomeCards(homeCards) }
        }
    }
}

/** A row made in this session rises a little and grows from about 92% as it appears (a soft spring). */
@Composable
private fun Modifier.riseIn(active: Boolean, fromEnd: Boolean): Modifier {
    if (!active) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, Motion.rise()) }
    return graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        val s = 0.92f + 0.08f * v
        scaleX = s
        scaleY = s
        translationY = (1f - v) * 18.dp.toPx()
        transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
    }
}

/** How long a secure entry's card stays marked after a row in 답이 필요해요 led to it, and how long a missing one is waited for. */
private const val ENTRY_MARK_MS = 1_800L
private const val ENTRY_WAIT_MS = 3_000L

/** A message's right-click menu and text selection where the pointer has them (the desktop); on the phone the bubble is drawn as it is. */
@Composable
private fun BubbleMenu(actions: List<MsgAction>, text: String, onAction: (MsgAction) -> Unit, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val offered = MessageActions.offered(actions, canShare = LocalTextSharer.current != null)
    val items = offered.map { a ->
        val label = when (a) {
            MsgAction.COPY -> string(Res.string.msg_action_copy)
            MsgAction.SHARE -> string(Res.string.msg_action_share)
            MsgAction.SELECT -> string(Res.string.msg_action_select)
            MsgAction.RETRY -> string(Res.string.msg_action_retry)
            MsgAction.EDIT -> string(Res.string.msg_action_edit)
            MsgAction.REPORT -> string(Res.string.msg_action_report)
        }
        label to { if (a == MsgAction.COPY) clipboard.setText(AnnotatedString(text)) else onAction(a) }
    }
    MessageContextArea(items, content)
}
