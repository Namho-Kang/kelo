package chat.kelo.desktop.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.AddEntry
import chat.kelo.app.chat.ChatScreenContent
import chat.kelo.app.chat.ChatScreenHooks
import chat.kelo.app.chat.ChatViewModel
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Sessions
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHeaderHeight
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.delay
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * The chat page: the shared chat screen ([ChatScreenContent]) with the desktop's slots ([ChatScreenHooks]) inside a window that takes
 * files dropped anywhere on it. What stays here is only what the desktop alone does: the file list for "+", Ctrl/Cmd+V of a picture,
 * the cards that are still per-app, the drop of files, the first-start card of a server with no model, and the 4 s news check.
 *
 * [topInset] is the floating header's height: the list scrolls under it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatPane(
    model: ChatViewModel,
    sessions: Sessions,
    profile: Profile,
    onProfile: (Profile) -> Unit,
    onOpenConnector: (String) -> Unit,
    topInset: Dp,
    modifier: Modifier = Modifier,
    /** A card asked for a place: a browser card's 보기 opens the tab its pictures came from, a card number goes to the wallet (app.openRequests) */
    onOpenTarget: (OpenTarget) -> Unit = {},
    /** The morning briefing's goal item: the Goals page */
    onOpenGoals: () -> Unit = {},
) {
    val c = Kelo.colors
    /** The model settings, open on a provider ("" = on the one in use), or closed (null): the first-start card's button */
    var setup by remember { mutableStateOf<String?>(null) }
    var dropping by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {   // another device may talk to the same agent meanwhile (as on the phone, every 4 s while shown)
            delay(4_000)
            model.checkForNews()
        }
    }

    val side = LocalChatSide.current
    val fileLabel = string(Res.string.desktop_attach_files)
    val fileHint = string(Res.string.attach_file_hint)
    val hooks = remember(model, side, fileLabel, fileHint, onOpenConnector) {
        ChatScreenHooks(
            addEntries = listOf(AddEntry("file", KeloIcons.File, fileLabel, fileHint) { pickFiles().forEach(model::attachFile) }),
            // Ctrl+V / Cmd+V with a picture on the clipboard: it is attached; text still pastes as text
            fieldModifier = Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed) && !e.isShiftPressed) {
                    val pictures = ClipboardImages.fromClipboard()
                    if (pictures != null) { pictures.forEach(model::attachFile); return@onPreviewKeyEvent true }
                }
                false
            },
            openCalendarAt = null,   // no calendar app on a computer: an event chip stays a label
            // the approval itself happens on the phone (its card or bank app, a passkey, a push), never on this computer
            approvalNote = { MutedText(string(Res.string.desktop_pay_step_on_phone), small = true) },
            pinCard = { need, _ -> PinNeedCard(need) },   // only the phone that saved a PIN can release it
            secretToCard = { model.secretEdit() },   // cards are added on the phone: the message goes back to the composer
        )
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { dropping = true }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val t = event.awtTransferable
                if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty().filter { it.isFile }
                files.forEach(model::attachFile)
                return files.isNotEmpty()
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(c.bg).dragAndDropTarget(
            shouldStartDragAndDrop = { e -> e.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
            target = dropTarget,
        ),
    ) {
        CompositionLocalProvider(LocalHeaderHeight provides topInset) {
            ChatScreenContent(
                model, profile, onProfile, hooks,
                onOpenConnector = onOpenConnector,
                onOpenBrowser = { item -> onOpenTarget(OpenTarget.Tab.ofCard(item)) },
                onOpenCardForm = { onOpenTarget(OpenTarget.Wallet) },
                onOpenGoals = onOpenGoals,
            )
        }
        // a new server has no model yet: the chat leads to choosing one (as on the phone)
        if (profile.modelReady == false) {
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = topInset + 8.dp, start = 16.dp, end = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(string(Res.string.model_first_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(string(Res.string.model_first_body), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                // a new server has no ready model to list: the button goes straight to the model settings (key, address, sign-in)
                chat.kelo.app.ui.components.PrimaryButton(string(Res.string.model_first_button), onClick = { setup = "" })
            }
        }
        if (dropping) {
            Box(
                Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(20.dp)).background(c.accent.copy(alpha = 0.10f))
                    .border(2.dp, c.accent, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
                    Icon(KeloIcons.File, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
                    Text(string(Res.string.desktop_drop_here, profile.agentName.ifBlank { "Kelo" }), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(string(Res.string.desktop_attach_files), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
        setup?.let { start ->
            // the model settings are the shared page (the same as 마이 → 모델), over the chat; a new server is set up entirely here
            Box(Modifier.fillMaxSize().background(c.bg)) {
                chat.kelo.app.my.ModelPage(profile, onProfile, onBack = { setup = null; model.loadProviders() }, cliProvider = start.ifEmpty { null })
            }
        }
    }
}

/** The system's own file picker (several files at once). */
fun pickFiles(): List<File> {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "", java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList().orEmpty()
}
