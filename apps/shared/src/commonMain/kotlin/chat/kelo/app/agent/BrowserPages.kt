package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import org.jetbrains.compose.resources.StringResource


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import chat.kelo.app.ui.platform.decodeImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.chat.BrowserImage
import chat.kelo.app.core.ApiError
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.EmptyNote
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SkeletonRows
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TabRow(t: BrowserTab, onClick: () -> Unit) {
    val c = Kelo.colors
    val thumb = remember(t.thumbnail) { t.thumbnail?.let(BrowserImage::decode) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(c.bubble), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(KeloIcons.Globe, contentDescription = null, tint = c.muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.title.ifBlank { t.host.ifBlank { tabLabel(t) } }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.host.isNotBlank()) Text(t.host, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabStatus(t)
                Text(tabLabel(t), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Whose tab it is, in the app's words (a phone's tab carries the phone's own name). */
@Composable
private fun tabLabel(t: BrowserTab): String = when (t.kind) {
    "owner" -> string(Res.string.agent_tab_kind_owner)
    "schedule" -> string(Res.string.agent_tab_kind_schedule)
    "task" -> string(Res.string.agent_tab_kind_task)
    else -> t.label
}

@Composable
private fun TabStatus(t: BrowserTab) {
    val c = Kelo.colors
    val (word, color) = when (t.status) {
        "working" -> string(Res.string.agent_tab_working) to c.accent
        "failed" -> string(Res.string.agent_tab_failed) to c.danger
        else -> string(Res.string.agent_tab_idle) to c.muted
    }
    Text(
        word, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * One tab, full screen and live (see [LiveTabPage]): the picture follows the agent, and the user can take the tab over. Closing
 * the tab is one tap away while the user is only watching.
 */
@Composable
fun BrowserTabPage(vm: AgentViewModel, id: String, onBack: () -> Unit, takeControl: Boolean = false, picture: String? = null) {
    val state by vm.browser.collectAsStateWithLifecycle()
    // opened straight from a chat card: the tab list may not be loaded yet
    LaunchedEffect(id) { if (state.data == null) vm.loadBrowser() }
    val tab = state.data?.items?.firstOrNull { it.id == id }
    var confirmClose by remember { mutableStateOf(false) }
    TabView(vm, id, tab, onBack, onClose = { confirmClose = true }, takeControl = takeControl, picture = picture)
    if (confirmClose) {
        ConfirmDialog(string(Res.string.agent_tab_close_confirm), string(Res.string.agent_tab_close), onDismiss = { confirmClose = false }, onYes = {
            vm.closeTab(tab ?: BrowserTab(id), onBack)
        })
    }
}

/**
 * What a tab shows: the live view. When the server has no live view (or the tab has closed) the newest picture kept of it shows
 * instead: the one the server kept when it closed, else [picture] (the last one the chat card had).
 */
@Composable
private fun TabView(
    vm: AgentViewModel, id: String, tab: BrowserTab?, onBack: () -> Unit, onClose: () -> Unit, takeControl: Boolean = false, picture: String? = null,
) {
    LiveTabPage(id, tab, onBack = onBack, onCloseTab = onClose, takeControl = takeControl) {
        ScreenshotView(vm, id, working = false, reload = 0, picture = picture)
    }
}

@Composable
private fun ScreenshotView(vm: AgentViewModel, id: String, working: Boolean, reload: Int, picture: String? = null) {
    val c = Kelo.colors
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember { mutableStateOf<StringResource?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(id, reload, working) {
        while (true) {
            loading = image == null
            try {
                val bytes = vm.screenshot(id)
                image = withContext(Dispatchers.Default) { decodeImage(bytes) }
                problem = null
            } catch (e: ApiError) {
                val kept = picture?.let { withContext(Dispatchers.Default) { BrowserImage.decode(it) } }
                if (kept != null) { image = kept; problem = null }
                else problem = if (e.status == 404) Res.string.agent_tab_no_picture else Res.string.connect_failed
            }
            loading = false
            if (!working) break
            kotlinx.coroutines.delay(4_000)
        }
    }
    val img = image
    when {
        img != null -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Image(img, contentDescription = string(Res.string.agent_tab_picture), contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
        }
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.muted) }
        problem != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { problem?.let { MutedText(string(it)) } }
    }
}
