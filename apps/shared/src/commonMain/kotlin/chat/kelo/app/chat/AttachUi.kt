package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import chat.kelo.app.upload.UploadSizeText

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
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import kotlinx.coroutines.delay

// ---- attachments

/**
 * "+": the list of things to add to a message (사진, 카메라, 파일 on the phone; a Nest entry may be added to the list later). Each row
 * is one of [entries]; picking one closes the sheet first (the caller's [AddEntry.onPick] opens whatever it needs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(entries: List<AddEntry>, onClose: () -> Unit) {
    val c = Kelo.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = chat.kelo.app.ui.components.rememberKeloSheetState(),
        containerColor = c.card,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(
                string(Res.string.attach_sheet_title), style = MaterialTheme.typography.titleMedium, color = c.ink,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            for (e in entries) SheetRow(e.icon, e.label, e.hint) { e.onPick() }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, hint: String, onClick: () -> Unit) {
    val c = Kelo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(c.bubble), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

/** The picked photos and files above the composer: a picture or a file icon, the name, how far the upload is (or why it failed), and ×. */
@Composable
fun AttachmentTray(items: List<Attachment>, onRemove: (String) -> Unit) {
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (a in items) AttachmentChip(a, onRemove = { onRemove(a.id) })
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    val c = Kelo.colors
    val failed = a.state == AttachState.FAILED
    Column(
        Modifier
            .widthIn(min = 150.dp, max = 230.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.card)
            .border(1.dp, if (failed) c.danger.copy(alpha = 0.6f) else c.line, RoundedCornerShape(14.dp)),
    ) {
        Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.bubble), contentAlignment = Alignment.Center) {
                val thumb = a.thumbnail
                if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                else Icon(if (a.image) KeloIcons.Image else KeloIcons.File, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 8.dp).width(130.dp)) {
                Text(a.name, style = MaterialTheme.typography.bodySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (a.state) {
                    AttachState.UPLOADING -> a.progress?.let { string(Res.string.attach_progress, (it * 100).toInt()) }
                        ?: string(Res.string.attach_preparing)
                    AttachState.DONE -> string(Res.string.attach_done)
                    AttachState.FAILED -> attachErrorText(a.error)
                }
                Text(
                    status, style = MaterialTheme.typography.labelSmall, color = if (failed) c.danger else c.muted,
                    maxLines = if (failed) 3 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val removeLabel = string(if (a.state == AttachState.UPLOADING) Res.string.attach_cancel else Res.string.attach_remove, a.name)
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(KeloIcons.Close, contentDescription = removeLabel, tint = c.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (a.state == AttachState.UPLOADING) {
            val p = a.progress
            if (p == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line, strokeCap = StrokeCap.Butt)
            } else {
                val shown by animateFloatAsState(p, Motion.small(LocalReduceMotion.current), label = "upload")
                LinearProgressIndicator(
                    progress = { shown }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line,
                    strokeCap = StrokeCap.Butt, drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun attachErrorText(e: AttachError?): String = when (e) {
    is AttachError.Server -> e.text
    is AttachError.TooLarge -> string(Res.string.upload_too_large_limit, UploadSizeText.of(e.limit))
    AttachError.Network -> string(Res.string.attach_error_network)
    AttachError.Unreadable, null -> string(Res.string.attach_error_unreadable)
}
