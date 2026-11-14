package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.platform.formFactor
import chat.kelo.app.ui.theme.Kelo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A screenshot Kelo showed in the chat ([ChatImage]): its own size up to the bubble width; a tap shows it whole over the chat. */
@Composable
fun ChatPicture(img: ChatImage) {
    val c = Kelo.colors
    val sessions = LocalAppServices.current.sessions
    var bitmap by remember(img.id) { mutableStateOf<ImageBitmap?>(null) }
    var open by remember(img.id) { mutableStateOf(false) }
    LaunchedEffect(img.id) {
        bitmap = runCatching {
            val bytes = sessions.requireApi().download(img.url)
            withContext(Dispatchers.Default) { decodeImage(bytes) }
        }.getOrNull()
    }
    val ratio = if (img.width > 0 && img.height > 0) img.width.toFloat() / img.height else 16f / 10f
    val shape = RoundedCornerShape(14.dp)
    val described = string(Res.string.chat_screenshot)
    Column(Modifier.widthIn(max = formFactor(phone = 420.dp, desktop = 520.dp))) {   // a wider window shows a bigger picture
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).background(c.card).border(1.dp, c.line, shape)
                .clickable(role = Role.Image, onClickLabel = described) { open = true },
        ) {
            bitmap?.let { Image(it, contentDescription = img.caption.ifBlank { described }, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        if (img.caption.isNotBlank()) {
            Text(img.caption, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
    }
    val b = bitmap
    if (open && b != null) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { open = false }, contentAlignment = Alignment.Center) {
                Image(b, contentDescription = img.caption.ifBlank { described }, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
    }
}
