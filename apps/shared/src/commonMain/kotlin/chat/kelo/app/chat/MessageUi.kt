package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalBackHandler
import chat.kelo.app.ui.platform.LocalChromeHider
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.platform.LocalTextSharer
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import kotlinx.coroutines.delay

/** How much a pressed-and-held bubble lifts. */
private const val LIFT_SCALE = 1.03f
/** How long the menu shows the check after 복사 before it closes. */
private const val COPY_CLOSE_MS = 650L

/**
 * A chat bubble that answers a long press: it lifts a little (scale and shadow), the phone gives a long-press tick, and a menu with
 * [actions] opens under it. 복사 is done here (the icon turns into a check for a moment, then the menu closes); the other actions go
 * to [onAction]. [copyText] gives what 복사 puts on the clipboard (worked out only then). [fromEnd]: the bubble sits on the trailing side (the user's own).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionBubble(
    actions: List<MsgAction>,
    copyText: () -> String,
    shape: Shape,
    fromEnd: Boolean,
    onAction: (MsgAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Kelo.colors
    val reduce = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val clipboard = LocalClipboardManager.current
    // no way to share on this app (the desktop gives none): the action is not offered
    val shown = MessageActions.offered(actions, canShare = LocalTextSharer.current != null)
    var open by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (open && !reduce) LIFT_SCALE else 1f, if (reduce) snap() else Motion.bump(), label = "lift")
    val shadow by animateDpAsState(if (open) 10.dp else 0.dp, Motion.small(reduce), label = "liftShadow")
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_CLOSE_MS)
            open = false
            copied = false
        }
    }
    val menuLabel = string(Res.string.msg_actions_label)
    Box {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = lift
                    scaleY = lift
                    transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 0.5f)
                }
                .shadow(shadow, shape, clip = false)
                .then(
                    if (shown.isEmpty()) Modifier
                    else Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClickLabel = menuLabel,
                        onLongClick = {
                            haptics.longPress()
                            copied = false
                            open = true
                        },
                        onClick = {},
                    ),
                ),
        ) { content() }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false; copied = false },
            containerColor = c.card,
            shape = RoundedCornerShape(14.dp),
            offset = DpOffset(0.dp, 6.dp),
        ) {
            for (a in shown) {
                val (icon, label) = when (a) {
                    MsgAction.COPY -> (if (copied) KeloIcons.Check else KeloIcons.Copy) to
                        string(if (copied) Res.string.msg_action_copied else Res.string.msg_action_copy)
                    MsgAction.SHARE -> KeloIcons.Share to string(Res.string.msg_action_share)
                    MsgAction.SELECT -> KeloIcons.TextSelect to string(Res.string.msg_action_select)
                    MsgAction.RETRY -> KeloIcons.Retry to string(Res.string.msg_action_retry)
                    MsgAction.EDIT -> KeloIcons.Edit to string(Res.string.msg_action_edit)
                    MsgAction.REPORT -> KeloIcons.Flag to string(Res.string.msg_action_report)
                }
                MenuRow(icon, label, accent = a == MsgAction.COPY && copied) {
                    if (a == MsgAction.COPY) {
                        if (!copied) {
                            clipboard.setText(AnnotatedString(copyText()))
                            haptics.tick()
                            copied = true
                        }
                    } else {
                        open = false
                        onAction(a)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, accent: Boolean, onClick: () -> Unit) {
    val c = Kelo.colors
    DropdownMenuItem(
        text = { Text(label, color = c.ink, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (accent) c.success else c.muted, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/**
 * 텍스트 선택: the message on a page of its own, where any part of it can be selected (press and hold, then drag the handles) and
 * copied with the system's selection menu, or all of it with 복사. The header and tab bar step aside; back closes it.
 */
@Composable
fun SelectTextPage(text: String, onClose: () -> Unit) {
    val c = Kelo.colors
    LocalChromeHider.current("select-text", true)   // the phone hides its header and tab bar; the desktop has none
    LocalBackHandler.current(onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}   // nothing under it gets touches
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(KeloIcons.Close, contentDescription = string(Res.string.close), tint = c.ink) }
            Text(
                string(Res.string.msg_select_title), style = MaterialTheme.typography.titleMedium, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            CopyButton(text)
        }
        Text(
            string(Res.string.msg_select_hint), style = MaterialTheme.typography.bodySmall, color = c.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            JoinedText(
                AnnotatedString(text), style = MaterialTheme.typography.bodyLarge, color = c.ink,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
