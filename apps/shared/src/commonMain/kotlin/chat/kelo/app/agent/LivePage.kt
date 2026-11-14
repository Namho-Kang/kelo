package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.CompositionLocalProvider
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.platform.LocalNetworkInfo
import chat.kelo.app.ui.platform.rememberPageViewModelStoreOwner
import chat.kelo.app.ui.platform.LocalSecureWindow
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.PrimaryButton
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/**
 * One browser tab, live, on the whole screen (FLAG_SECURE: a page may show a password). The picture follows the tab as the agent
 * works in it; pinch to zoom and drag to look around. "직접 조작" takes the tab over: the agent waits, taps and drags go to the
 * page (a drag scrolls it), and a text field, keys and back / forward / reload come up; "돌려주기" hands it back. Leaving the page
 * hands it back too.
 *
 * [fallback] is what shows when there is nothing live to show (a tab that has closed keeps its last picture there).
 */
@Composable
fun LiveTabPage(
    tabId: String,
    tab: BrowserTab?,
    onBack: () -> Unit,
    onCloseTab: (() -> Unit)?,
    takeControl: Boolean = false,
    fallback: @Composable () -> Unit,
) {
    // the page's view models (the live connection) live exactly as long as the page: they are cleared when it leaves
    val owner = rememberPageViewModelStoreOwner(tabId)
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        LiveTabContent(tabId, tab, onBack, onCloseTab, takeControl, fallback)
    }
}

@Composable
private fun LiveTabContent(
    tabId: String,
    tab: BrowserTab?,
    onBack: () -> Unit,
    onCloseTab: (() -> Unit)?,
    takeControl: Boolean,
    fallback: @Composable () -> Unit,
) {
    LocalSecureWindow.current()   // a person's screen: out of screenshots and the recent-apps preview
    val c = Kelo.colors
    val network = LocalNetworkInfo.current
    val pointer = LocalFormFactor.current == FormFactor.Desktop
    val api = LocalAppServices.current.sessions.api.collectAsStateWithLifecycle().value ?: return
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    // the connection lives in the view model (one per tab): it closes when the page is gone for good
    val client: LiveViewModel = viewModel(key = "live-$tabId") { liveViewModel(api, tabId, network) }
    // off screen (another app, the screen off): the connection closes; the tab stays held for a short while
    LifecycleStartEffect(client) {
        client.start()
        onStopOrDispose { client.stop() }
    }
    val frame by client.frame.collectAsStateWithLifecycle()
    val state by client.state.collectAsStateWithLifecycle()
    val activity = LocalAppServices.current.agentActivity
    // the tab is gone (closed, or the job behind it stopped): the lists that still show it as running load again now
    LaunchedEffect(state.mode, state.end) {
        if (state.mode == LiveMode.ENDED && state.end != LiveEnd.NO_BROWSER) activity.workChanged.update { it + 1 }
    }
    var notice by remember { mutableStateOf<StringResource?>(null) }
    LaunchedEffect(client) { client.notices.collect { notice = it } }
    LaunchedEffect(notice) { if (notice != null) { delay(3_500); notice = null } }

    val controlling = state.status.youControl
    // opened to solve a human check: take the tab over once, as soon as the first picture is there and nobody else holds it
    var autoTaken by remember(client) { mutableStateOf(false) }
    LaunchedEffect(client, takeControl, frame != null, state.status.someoneElse, controlling) {
        if (!LiveRules.autoTake(takeControl, autoTaken, frame != null, controlling, state.status.someoneElse, state.controlBusy)) return@LaunchedEffect
        autoTaken = true
        when (client.control(true)) {
            LiveClient.ControlResult.Held -> notice = Res.string.live_held_elsewhere
            LiveClient.ControlResult.Gone -> notice = Res.string.live_tab_closed
            is LiveClient.ControlResult.Failed -> notice = Res.string.live_input_failed
            LiveClient.ControlResult.Ok -> haptics.confirm()
        }
    }
    val header = frame?.header
    val title = header?.title?.ifBlank { null } ?: tab?.title?.ifBlank { null } ?: tab?.host?.ifBlank { null } ?: string(Res.string.agent_tab_browser)
    val host = header?.url?.let(::hostOf)?.ifBlank { null } ?: tab?.host.orEmpty()

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        LiveTop(title, host, onBack, onCloseTab.takeIf { !controlling && state.mode != LiveMode.ENDED })
        if (state.mode != LiveMode.ENDED) LiveStrip(state, tab)
        if (controlling) ControlBanner(busy = state.controlBusy) {
            scope.launch { client.control(false) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            when {
                state.mode == LiveMode.ENDED && state.end != LiveEnd.NO_BROWSER -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LiveNote(string(Res.string.live_tab_closed))
                    Box(Modifier.weight(1f)) { fallback() }
                }
                state.mode == LiveMode.ENDED -> fallback()
                else -> LiveCanvas(client, frame, controlling, onTapped = { haptics.tick() })
            }
            notice?.let { n ->
                Text(
                    string(n), color = c.bg, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(c.ink)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (pointer) {
            val key = if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) "⌘" else "Ctrl"
            Text(string(Res.string.desktop_chat_zoom_hint, key), style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        if (controlling) ControlBar(client)
        else if (state.mode != LiveMode.ENDED) {
            val other = state.status.someoneElse
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    string(if (other) Res.string.live_held_elsewhere else Res.string.live_take),
                    enabled = !other && !state.controlBusy && frame != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            when (client.control(true)) {
                                LiveClient.ControlResult.Held -> notice = Res.string.live_held_elsewhere
                                LiveClient.ControlResult.Gone -> notice = Res.string.live_tab_closed
                                is LiveClient.ControlResult.Failed -> notice = Res.string.live_input_failed
                                LiveClient.ControlResult.Ok -> haptics.confirm()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveTop(title: String, host: String, onBack: () -> Unit, onCloseTab: (() -> Unit)?) {
    val c = Kelo.colors
    val back = string(Res.string.back)
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(KeloIcons.ChevronLeft, contentDescription = back, tint = c.ink) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (host.isNotBlank()) Text(host, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onCloseTab != null) {
            IconButton(onClick = onCloseTab) { Icon(KeloIcons.Trash, contentDescription = string(Res.string.agent_tab_close), tint = c.muted) }
        }
    }
}

/** What the agent is doing, who is watching, who holds the tab. */
@Composable
private fun LiveStrip(state: LiveState, tab: BrowserTab?) {
    val c = Kelo.colors
    val s = state.status
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (word, color) = when {
            s.youControl -> string(Res.string.live_state_you) to c.accent
            s.someoneElse -> string(Res.string.live_state_other) to c.muted
            s.agent.waitingApproval -> string(Res.string.live_state_waiting) to c.accent
            s.agent.busy || tab?.status == "working" -> string(Res.string.agent_tab_working) to c.accent
            else -> string(Res.string.agent_tab_idle) to c.muted
        }
        Pill(word, color)
        val line = when (state.mode) {
            LiveMode.CONNECTING -> string(Res.string.live_connecting)
            LiveMode.WAITING -> string(Res.string.live_reconnecting)
            else -> s.label.ifBlank { s.agent.status.orEmpty() }.ifBlank { tab?.statusText.orEmpty() }
        }
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp))
        if (state.mode == LiveMode.POLLING) Pill(string(Res.string.live_slow_mode), c.muted)
        if (s.viewers > 0) {
            val desc = plural(Res.plurals.live_viewers, s.viewers, s.viewers)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = desc }) {
                Icon(KeloIcons.Eye, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(" ${s.viewers}", style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun LiveNote(text: String) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(KeloIcons.Alert, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
    }
}

/** While the user holds the tab: the agent is paused, and one tap gives it back. */
@Composable
private fun ControlBanner(busy: Boolean, onGiveBack: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(c.accent.copy(alpha = 0.14f)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(KeloIcons.Hand, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        Text(string(Res.string.live_paused_banner), style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
        Text(
            string(Res.string.live_give_back), color = c.bg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (busy) c.muted else c.ink)
                .clickable(enabled = !busy, role = Role.Button, onClick = onGiveBack).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The picture. Watching: pinch to zoom (1x to 5x), drag to move, double tap to zoom in or out. Controlling: a tap or a long press
 * goes to that point of the page and a drag scrolls it (sent at most every 50 ms); the zoom stays as it was and is accounted for.
 * With a pointer (the desktop) the zoom also has buttons and the wheel with Ctrl (⌘ on a Mac) held, and the plain wheel scrolls the
 * page while controlling. The zoom and pan are the view model's.
 */
@Composable
private fun LiveCanvas(client: LiveViewModel, frame: LiveFrame?, controlling: Boolean, onTapped: () -> Unit) {
    val c = Kelo.colors
    val pointer = LocalFormFactor.current == FormFactor.Desktop
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoom by client.zoom.collectAsStateWithLifecycle()
    val pan by client.pan.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(frame)
    val zoomNow by rememberUpdatedState(zoom)
    val panNow by rememberUpdatedState(pan)
    // drags add up here and leave as one scroll every 50 ms
    val pending = remember { floatArrayOf(0f, 0f, 0f, 0f) }   // dx, dy, at x, at y (view pixels)
    LaunchedEffect(controlling) {
        while (controlling) {
            delay(50)
            val f = current ?: continue
            if (pending[0] == 0f && pending[1] == 0f) continue
            val w = size.width.toFloat(); val h = size.height.toFloat()
            val d = LiveGeometry.scrollDelta(pending[0], pending[1], w, h, f.header.w, f.header.h, zoomNow)
            val at = LiveGeometry.toFrame(pending[2], pending[3], w, h, f.header.w, f.header.h, zoomNow, panNow.x, panNow.y)
            pending[0] = 0f; pending[1] = 0f
            if (d.x != 0 || d.y != 0) client.send(LiveMessages.scroll(d, at, f.header.w, f.header.h))
        }
    }
    val desc = string(Res.string.agent_tab_picture)
    val zoomKeyIsMeta = remember { System.getProperty("os.name").orEmpty().lowercase().contains("mac") }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(c.bubble).onSizeChanged { size = it }
            .then(
                if (controlling) Modifier
                    .pointerInput("control") {
                        detectTapGestures(
                            onTap = { p ->
                                val f = current ?: return@detectTapGestures
                                LiveGeometry.toFrame(p.x, p.y, size.width.toFloat(), size.height.toFloat(), f.header.w, f.header.h, zoomNow, panNow.x, panNow.y)
                                    ?.let { onTapped(); client.send(LiveMessages.tap(it, f.header.w, f.header.h)) }
                            },
                            onLongPress = { p ->
                                val f = current ?: return@detectTapGestures
                                LiveGeometry.toFrame(p.x, p.y, size.width.toFloat(), size.height.toFloat(), f.header.w, f.header.h, zoomNow, panNow.x, panNow.y)
                                    ?.let { onTapped(); client.send(LiveMessages.longPress(it, f.header.w, f.header.h)) }
                            },
                        )
                    }
                    .pointerInput("scroll") {
                        detectDragGestures { change, drag ->
                            change.consume()
                            pending[0] += drag.x; pending[1] += drag.y
                            pending[2] = change.position.x; pending[3] = change.position.y
                        }
                    }
                else Modifier
                    .pointerInput("zoom") {
                        detectTransformGestures { _, panBy, zoomBy, _ ->
                            client.transform(zoomBy, panBy, size.width.toFloat(), size.height.toFloat())
                        }
                    }
                    .pointerInput("double") {
                        detectTapGestures(onDoubleTap = { client.toggleZoom(size.width.toFloat(), size.height.toFloat()) })
                    },
            )
            // the pointer's wheel: with the zoom key it zooms; else it moves a zoomed picture, or scrolls the page while controlling
            .then(if (pointer) Modifier.pointerInput("wheel", controlling) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val ch = e.changes.firstOrNull() ?: continue
                        val mods = e.keyboardModifiers
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        if (if (zoomKeyIsMeta) mods.isMetaPressed else mods.isCtrlPressed) {
                            client.zoomTo(zoomNow * (if (ch.scrollDelta.y < 0) LiveViewModel.WHEEL_STEP else 1f / LiveViewModel.WHEEL_STEP), w, h)
                            ch.consume()
                            continue
                        }
                        val f = current ?: continue
                        if (!controlling) {
                            if (zoomNow > 1.01f) {
                                client.transform(1f, Offset(-ch.scrollDelta.x * 60f, -ch.scrollDelta.y * 60f), w, h)
                                ch.consume()
                            }
                            continue
                        }
                        // one wheel notch is about 100 CSS pixels of the page (sent in frame pixels); the page moves the way the wheel turns
                        val step = 100f * (if (f.header.cssW > 0) f.header.w.toFloat() / f.header.cssW else 1f) / zoomNow
                        val d = FramePoint((ch.scrollDelta.x * step).roundToInt(), (ch.scrollDelta.y * step).roundToInt())
                        val at = LiveGeometry.toFrame(ch.position.x, ch.position.y, w, h, f.header.w, f.header.h, zoomNow, panNow.x, panNow.y)
                        client.send(LiveMessages.scroll(d, at, f.header.w, f.header.h))
                        ch.consume()
                    }
                }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f == null) {
            CircularProgressIndicator(color = c.muted)
        } else {
            Image(
                f.image, contentDescription = desc, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
            )
        }
        if (pointer) ZoomButtons(zoom, Modifier.align(Alignment.BottomEnd),
            onOut = { client.zoomTo(zoom / LiveViewModel.BUTTON_STEP, size.width.toFloat(), size.height.toFloat()) },
            onIn = { client.zoomTo(zoom * LiveViewModel.BUTTON_STEP, size.width.toFloat(), size.height.toFloat()) },
            onReset = { client.resetZoom() })
    }
}

/** The pointer's zoom buttons in a corner of the picture: out, the level (a click fits the window), in; with the wheel's hint under. */
@Composable
private fun ZoomButtons(zoom: Float, modifier: Modifier, onOut: () -> Unit, onIn: () -> Unit, onReset: () -> Unit) {
    val c = Kelo.colors
    val zoomInWords = string(Res.string.desktop_chat_zoom_in)
    val zoomResetWords = string(Res.string.desktop_chat_zoom_reset)
    Row(
        modifier.padding(8.dp).clip(RoundedCornerShape(99.dp)).background(c.card.copy(alpha = 0.92f))
            .border(1.dp, c.line, RoundedCornerShape(99.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOut, enabled = zoom > 1.01f) { Text("−", color = c.ink, style = MaterialTheme.typography.titleLarge) }
        Text("${(zoom * 100).roundToInt()}%", color = c.ink, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(enabled = zoom > 1.01f, onClick = onReset).padding(horizontal = 4.dp)
                .semantics { contentDescription = zoomResetWords })
        IconButton(onClick = onIn, enabled = zoom < LiveGeometry.MAX_ZOOM - 0.01f) {
            Text("+", color = c.ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = zoomInWords })
        }
    }
}

/** Keys, back / forward / reload, and a line to type into the page (nothing typed is kept or logged). */
@Composable
private fun ControlBar(client: LiveViewModel) {
    val c = Kelo.colors
    var text by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }
    fun sendText() { if (text.isNotEmpty()) { client.type(text); text = "" } }
    Column(Modifier.fillMaxWidth().background(c.card).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NavKey(KeloIcons.ArrowLeft, Res.string.live_back) { client.send(LiveMessages.nav("back")) }
            NavKey(KeloIcons.ArrowRight, Res.string.live_forward) { client.send(LiveMessages.nav("forward")) }
            NavKey(KeloIcons.Retry, Res.string.live_reload) { client.send(LiveMessages.nav("reload")) }
            KEY_LABELS.forEach { (key, label, desc) -> TextKey(string(label), string(desc)) { client.send(LiveMessages.key(key)) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeloTextField(
                text, { text = it }, string(Res.string.live_type_hint), modifier = Modifier.weight(1f), password = hidden,
                maxLength = LiveMessages.MAX_TEXT,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false, imeAction = ImeAction.Send,
                    keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(onSend = { sendText() }),
            )
            IconButton(onClick = { hidden = !hidden }) {
                Icon(if (hidden) KeloIcons.Lock else KeloIcons.Eye, contentDescription = string(if (hidden) Res.string.live_type_show else Res.string.live_type_hide), tint = c.muted)
            }
            IconButton(onClick = { sendText() }, enabled = text.isNotEmpty()) {
                Icon(KeloIcons.Send, contentDescription = string(Res.string.live_type_send), tint = if (text.isNotEmpty()) c.accent else c.muted)
            }
        }
    }
}

private val KEY_LABELS = listOf(
    Triple("Enter", Res.string.live_key_enter, Res.string.live_key_enter_desc),
    Triple("Backspace", Res.string.live_key_backspace, Res.string.live_key_backspace_desc),
    Triple("Tab", Res.string.live_key_tab, Res.string.live_key_tab_desc),
    Triple("Escape", Res.string.live_key_escape, Res.string.live_key_escape_desc),
    Triple("ArrowLeft", Res.string.live_key_left, Res.string.live_key_left_desc),
    Triple("ArrowUp", Res.string.live_key_up, Res.string.live_key_up_desc),
    Triple("ArrowDown", Res.string.live_key_down, Res.string.live_key_down_desc),
    Triple("ArrowRight", Res.string.live_key_right, Res.string.live_key_right_desc),
)

@Composable
private fun NavKey(icon: ImageVector, desc: StringResource, onClick: () -> Unit) {
    val c = Kelo.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.bubble).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = string(desc), tint = c.ink, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun TextKey(label: String, desc: String, onClick: () -> Unit) {
    val c = Kelo.colors
    Box(
        Modifier.size(height = 40.dp, width = 52.dp).clip(RoundedCornerShape(10.dp)).background(c.bubble)
            .clickable(role = Role.Button, onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1) }
}

private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
