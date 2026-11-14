package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import chat.kelo.app.ui.platform.decodeImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a user's message with files is drawn (pure, so it is tested). */
object MessageFileRules {
    /**
     * The bubble's text: nothing when it is the app's own sentence ([autoText]) and the files show; else the typed text without the
     * lines the app added for each file ([line] of its path or name), since the files show as pictures and chips.
     */
    fun shownText(text: String, files: List<MessageFile>, autoText: Boolean, line: (String) -> String): String {
        if (files.isEmpty()) return text
        if (autoText) return ""
        val added = files.flatMap { listOf(line(it.path), line(it.name)) }.toSet()
        return text.lines().filterNot { it.trim() in added }.joinToString("\n").trim()
    }

    /**
     * A long file name for a one-line chip, cut in the middle so its ending (".pdf") stays: at most [max] columns, where a Hangul, CJK
     * or full-width character counts as 2 (the same rule as the iPhone and the desktop, so a Korean name is not cut twice).
     * Short names stay whole.
     */
    fun chipName(name: String, max: Int = 26): String {
        if (columns(name) <= max) return name
        val tailLen = minOf(name.length - name.lastIndexOf('.').coerceAtLeast(name.length - 10), 10).coerceAtLeast(8)
        var tail = name.takeLast(tailLen)
        while (columns(tail) > max / 2) tail = tail.drop(1)
        val budget = max - 1 - columns(tail)
        val head = StringBuilder()
        var used = 0
        for (ch in name) {
            val w = width(ch)
            if (used + w > budget) break
            head.append(ch); used += w
        }
        return head.toString() + "…" + tail
    }

    /** Display columns of [s]: 2 for Hangul, CJK, kana and full-width forms, else 1. */
    fun columns(s: String): Int = s.sumOf { width(it) }

    private fun width(c: Char): Int = when (c.code) {
        in 0x1100..0x115F, in 0x2E80..0xA4CF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF, in 0xFE30..0xFE4F, in 0xFF00..0xFF60, in 0xFFE0..0xFFE6 -> 2
        else -> 1
    }

    /** Rows of the picture grid: one big picture alone, else two a row. */
    fun rows(pictures: Int): List<Int> = when {
        pictures <= 0 -> emptyList()
        pictures == 1 -> listOf(1)
        else -> List(pictures / 2) { 2 } + if (pictures % 2 == 1) listOf(1) else emptyList()
    }
}

/**
 * The files the user sent with a message, at the end side above (or instead of) the text: pictures and videos as a small grid of
 * thumbnails (GET thumb with the signed-in API; a tap shows the picture whole), other files as chips with their names.
 */
@Composable
fun UserFiles(files: List<MessageFile>, modifier: Modifier = Modifier) {
    val pictures = files.filter { it.visual }
    val others = files.filterNot { it.visual }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var at = 0
        for (n in MessageFileRules.rows(pictures.size)) {
            val row = pictures.subList(at, at + n)
            at += n
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (f in row) Thumb(f, if (n == 1 && pictures.size == 1) 200.dp else 104.dp)
            }
        }
        for (f in others) FileChip(f)
    }
}

@Composable
private fun Thumb(f: MessageFile, side: Dp) {
    val c = Kelo.colors
    val sessions = LocalAppServices.current.sessions
    var bitmap by remember(f.path, f.thumb) { mutableStateOf<ImageBitmap?>(null) }
    var open by remember(f.path) { mutableStateOf(false) }
    LaunchedEffect(f.path, f.thumb) {
        val url = f.thumb ?: return@LaunchedEffect   // the server makes it once the message is stored (user_message brings it)
        bitmap = load(sessions, url)
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(side).clip(shape).background(c.card).border(1.dp, c.line, shape)
            .clickable(role = Role.Image, onClickLabel = f.name) { if (f.kind == "image") open = true },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) Image(b, contentDescription = f.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(KeloIcons.Image, contentDescription = f.name, tint = c.muted)
    }
    if (open) FullPicture(f, onClose = { open = false })
}

/** The picture whole over the chat (the original file, read with the signed-in API). */
@Composable
private fun FullPicture(f: MessageFile, onClose: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    var bitmap by remember(f.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(f.url) { if (f.url.isNotEmpty()) bitmap = load(sessions, f.url) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
            // the phone's picture fills the width; in a desktop window it is fitted inside the window with a margin
            val fit = formFactor(phone = Modifier.fillMaxWidth(), desktop = Modifier.fillMaxSize().padding(24.dp))
            bitmap?.let { Image(it, contentDescription = f.name, modifier = fit, contentScale = ContentScale.Fit) }
        }
    }
}

@Composable
private fun FileChip(f: MessageFile) {
    val c = Kelo.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.widthIn(max = 260.dp).clip(shape).background(c.mine).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KeloIcons.File, contentDescription = string(Res.string.chat_attached_file), tint = c.ink, modifier = Modifier.size(18.dp))
        // one line (a file name is one value): cut at the end at 320 dp
        Text(MessageFileRules.chipName(f.name), color = c.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = f.name })
    }
}

private suspend fun load(sessions: chat.kelo.app.core.Sessions, url: String): ImageBitmap? = runCatching {
    val bytes = sessions.requireApi().download(url)
    withContext(Dispatchers.Default) { decodeImage(bytes) }
}.getOrNull()
