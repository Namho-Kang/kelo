package chat.kelo.app.chat

import chat.kelo.app.shell.rememberNestChatCards
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.Profile
import chat.kelo.app.ui.components.StaleHint
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.LocalTextSharer
import chat.kelo.app.ui.platform.headerHeight
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * The chat screen's body, shared by the apps: the message list, the composer riding on the keyboard over a fade, the "↓" pill, the
 * model chooser, the report sheet, the "+" list, and the sheets for a message held back (a key, the AI consent). What only an app can
 * do comes in through [hooks]; the app that makes [vm] also owns what hangs on the screen's lifecycle (reload on resume, saving the
 * draft, the news check, shared files, permissions).
 *
 * While the keyboard is up the app's own chrome may step aside ([ChatScreenHooks.keyboardChrome]) and the composer rides on the
 * keyboard (the window insets move it, frame by frame); when the keyboard goes away the field lets go of its focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreenContent(
    vm: ChatViewModel, profile: Profile, onProfile: (Profile) -> Unit, hooks: ChatScreenHooks,
    onOpenConnector: (String) -> Unit = {}, onOpenBrowser: (ChatItem.Browser) -> Unit = {}, onOpenCardForm: () -> Unit = {},
    /** The goals tab (a goal in the morning briefing) */
    onOpenGoals: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sessions = LocalAppServices.current.sessions
    val state by vm.state.collectAsState()
    val sharer = LocalTextSharer.current
    val haptics = LocalHaptics.current
    val desktop = LocalFormFactor.current == FormFactor.Desktop
    var sheet by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    // the current provider's model list: the chip's readable name and the chooser's first section. The last good one (kept on the
    // device) shows at once, so the chip never changes its wording when the fresh list arrives (see ModelCatalog)
    val modelList by androidx.compose.runtime.produceState(ModelCatalog.keptList(sessions, profile.provider), profile.provider, sessions) {
        val kept = ModelCatalog.keptList(sessions, profile.provider)
        value = kept
        if (profile.provider.isNotEmpty()) {
            val fresh = try {
                ModelCatalog.fetch(sessions, profile.provider)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            value = ModelCatalog.shown(fresh, kept)
        }
    }

    LaunchedEffect(vm) {
        // the buttons under messages come from the open follow-ups; a fill-in card the agent asked for (a key, a login, a card) that
        // is still open shows again after the app was closed: asked here, not only when the status poll counts one
        if (!vm.state.value.loaded) vm.reload() else { vm.loadFollowups(); vm.refreshSecrets() }
        vm.loadProviders()
        vm.refreshAsks(force = true)   // a background job's question waiting since before the chat was shown
    }
    LaunchedEffect(vm, haptics) {
        vm.events.collect { e ->
            when (e) {
                ChatEvent.SENT -> haptics.tick()
                ChatEvent.REPLY_DONE -> haptics.confirm()
                ChatEvent.ERROR -> haptics.reject()
                ChatEvent.PAYMENT_AUTO -> haptics.confirm()
            }
        }
    }

    // "+": the app's list of things to add (a photo, the camera, a file ...); each goes into the tray and uploads at once
    var attachSheet by remember { mutableStateOf(false) }
    val lineFormat = string(Res.string.chat_attachment_line)
    val askFormat = string(Res.string.chat_upload_prefill)
    val locale = textLocale()
    val words = AttachmentWords(
        line = { name -> TextFormat.format(locale, lineFormat, arrayOf(name)) },
        ask = { names -> TextFormat.format(locale, askFormat, arrayOf(names)) },
        separator = string(Res.string.chat_attachment_separator),
    )

    // the keyboard: the app's chrome steps aside while it is up for the composer, and the field lets go of its focus when it goes.
    // Only while the composer has focus: a keyboard left over from the screen before (pairing) never keeps the chrome away.
    // common stand-in for isImeVisible: read through derivedStateOf so only the flip recomposes, not every frame of the animation
    val imeDensity = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(imeDensity) { derivedStateOf { imeInsets.getBottom(imeDensity) > 0 } }
    var composerFocused by remember { mutableStateOf(false) }
    hooks.keyboardChrome(imeVisible, composerFocused)
    val focus = LocalFocusManager.current
    LaunchedEffect(imeVisible) { if (!imeVisible) focus.clearFocus() }

    // 수정해서 다시 보내기: the text goes into the composer, which takes the focus (and the keyboard comes up)
    val composerFocus = remember { FocusRequester() }
    ComposerAutoFocus(composerFocus)
    val keyboard = LocalSoftwareKeyboardController.current
    var editTick by remember { mutableStateOf(0) }
    LaunchedEffect(editTick) {
        if (editTick > 0) {
            runCatching { composerFocus.requestFocus() }
            keyboard?.show()
        }
    }
    // 텍스트 선택: the message on a page of its own
    var selecting by remember { mutableStateOf<String?>(null) }
    // 신고하기: the reply being reported (its row key), and what was left in the sheet per reply when it closed without sending
    var reporting by remember { mutableStateOf<String?>(null) }
    val reportDrafts = remember { mutableStateMapOf<String, ReportDraft>() }
    val snackbar = remember { SnackbarHostState() }
    val reportScope = rememberCoroutineScope()
    val reportThanks = string(Res.string.report_thanks)
    val onMessageAction: (ChatItem, String, MsgAction) -> Unit = { item, text, action ->
        when (action) {
            MsgAction.SHARE -> sharer?.share(text)
            MsgAction.SELECT -> { focus.clearFocus(); selecting = text }
            MsgAction.RETRY -> vm.retry(item.key)
            MsgAction.EDIT -> { vm.setInput(text); editTick++ }
            MsgAction.REPORT -> { focus.clearFocus(); reporting = item.key }
            MsgAction.COPY -> {}
        }
    }

    // The page takes the whole window and the app's bar floats over its bottom: keep the composer above whichever is taller, the
    // bar or the keyboard. Done with window insets in layout, so the keyboard's animation moves it smoothly without recomposing the chat.
    val bar = hooks.bottomInset
    // the composer floats over the messages: they run on under it and fade out in a scrim (ComposerScrim) instead of being cut off,
    // and the list keeps room at its end for the composer's measured height so the last row can always scroll above the pill
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }
    var trayHeight by remember { mutableStateOf(0.dp) }
    // the "↓" button: the list says when it shows; it is drawn after the composer, on the card's top border (ChatOverlayRules)
    val jump = remember { JumpButtonState() }
    // a window is wider than a phone: the chat keeps a readable column in the middle
    val column = if (desktop) Modifier.widthIn(max = 860.dp) else Modifier
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxSize().then(column)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets(bottom = bar)))) {
                Messages(state, profile.agentName, vm, hooks, onOpenConnector, onMessageAction, Modifier.fillMaxSize(), onOpenBrowser, bottom = composerHeight,
                    onOpenCardForm = onOpenCardForm, onOpenGoals = onOpenGoals, jump = jump)
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }   // the pill (and tray) and the fade above it
                        .composerScrim(Kelo.colors.bg),
                ) {
                    Box(Modifier.onSizeChanged { trayHeight = with(density) { it.height.toDp() } }) {
                        AttachmentTray(state.attachments, onRemove = vm::removeAttachment)
                    }
                    Composer(
                        input = state.input, busy = state.busy,
                        canSend = state.busy || (!state.uploading && (state.input.isNotBlank() || state.ready.isNotEmpty())),
                        focusRequester = composerFocus, onFocus = { composerFocused = it },
                        onInput = vm::setInput, onSend = { vm.send(words = words) }, onStop = vm::stop,
                        onAttach = { attachSheet = true },
                        chipText = modelChipText(profile, state.providers, modelList), onChip = { focus.clearFocus(); chooser = true },
                        fieldModifier = hooks.fieldModifier, mic = hooks.mic,
                        onPickTop = { SlashCommands.matches(state.input).firstOrNull()?.let { vm.setInput(SlashCommands.picked(it)); true } ?: false },
                    )
                }
                // "/" typed: the commands above the composer card (a tap puts the command in the field, its space closes the list)
                val slash = SlashCommands.matches(state.input)
                // not before the composer was measured: on the first frame its height is 0 and the gap would be negative (a crash at
                // start when a draft that starts with "/" was kept)
                if (slash.isNotEmpty() && composerHeight > 0.dp) {
                    SlashMenu(
                        slash, onPick = { vm.setInput(SlashCommands.picked(it)) },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = (composerHeight - ComposerScrimFade + 4.dp).coerceAtLeast(0.dp)),
                    )
                }
                // on the composer card's top border at the end side, centred over 보내기; on top of the attachments while they wait
                val card = composerHeight - ComposerScrimFade - trayHeight - COMPOSER_TOP
                val lift = ChatOverlayRules.jumpLift(card.value, if (trayHeight > 0.dp) (composerHeight - ComposerScrimFade).value else 0f).dp
                AnimatedVisibility(
                    visible = jump.shown,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = JUMP_END, bottom = lift),
                    enter = fadeIn(Motion.small(LocalReduceMotion.current)) + scaleIn(Motion.small(LocalReduceMotion.current), initialScale = 0.8f),
                    exit = fadeOut(Motion.small(LocalReduceMotion.current)) + scaleOut(Motion.small(LocalReduceMotion.current), targetScale = 0.8f),
                ) {
                    JumpPill(jump.unread, onClick = { jump.onClick() })
                }
            }
            // the history shown is the one kept from last time and the server cannot be reached right now
            StaleHint(state.offline && !state.busy, Modifier.align(Alignment.TopCenter).padding(top = headerHeight()), onRetry = vm::reload)
            val reduce = LocalReduceMotion.current
            AnimatedVisibility(
                visible = selecting != null,
                enter = fadeIn(Motion.small(reduce)) + slideInVertically(Motion.enter(reduce)) { it / 8 },
                exit = fadeOut(Motion.small(reduce)) + slideOutVertically(Motion.exit(reduce)) { it / 8 },
            ) {
                // the text stays while the page slides away
                var shown by remember { mutableStateOf("") }
                selecting?.let { shown = it }
                SelectTextPage(shown, onClose = { selecting = null })
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime.union(WindowInsets(bottom = bar))).padding(bottom = 72.dp))
        }
    }
    reporting?.let { key ->
        val reply = ReportRules.replyText(state.items, key)
        if (reply == null) {
            reporting = null
        } else {
            ReportSheet(
                reply = reply, asked = ReportRules.askedBefore(state.items, key), draft = reportDrafts[key] ?: ReportDraft(),
                onDraft = { reportDrafts[key] = it },
                onSent = {
                    reportDrafts.remove(key)
                    reporting = null
                    reportScope.launch { snackbar.showSnackbar(reportThanks) }
                },
                onClose = { reporting = null },
            )
        }
    }
    if (chooser) ModelChooser(
        profile, state.providers, modelList, onProfile = onProfile,
        onEffort = { vm.pickEffort(it, onProfile) },
        onMore = { chooser = false; sheet = true },
        onClose = { chooser = false },
    )
    if (sheet) ModelSheet(profile, onSaved = onProfile, onClose = { sheet = false })
    val nestCards = rememberNestChatCards()
    if (attachSheet) AttachSheet(
        (hooks.addEntries + nestCards.rows()).map { e -> AddEntry(e.id, e.icon, e.label, e.hint) { attachSheet = false; e.onPick() } },
        onClose = { attachSheet = false },
    )
    nestCards.Sheet()
    // a message the server held back for a key or password (SecretGuard): save it, send it as is, or edit it
    state.secretAsk?.let { ask ->
        SecretSheet(
            ask,
            onSaved = { value, reference -> vm.secretSaved(value, reference); haptics.confirm() },
            onCard = { hooks.secretToCard(); onOpenCardForm() },
            onSendAsIs = vm::secretSendAsIs,
            onEdit = vm::secretEdit,
        )
    }
    // the one question before chats first go to an AI service (AiConsent): Agree sends the message, Not now keeps it here
    state.consentAsk?.let { ask ->
        val consentBusy by vm.consentBusy.collectAsState()
        val consentError by vm.consentError.collectAsState()
        ConsentSheet(ask, consentBusy, consentError, onAgree = vm::consentAgree, onNotNow = vm::consentNotNow)
    }
}

/** A window with a physical keyboard has the composer ready when the chat opens; the phone never raises its keyboard on its own. */
@Composable
internal fun ComposerAutoFocus(requester: FocusRequester) {
    val desktop = LocalFormFactor.current == FormFactor.Desktop
    LaunchedEffect(desktop) {
        if (desktop) runCatching { requester.requestFocus() }
    }
}
