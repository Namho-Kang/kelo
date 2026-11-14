package chat.kelo.app.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.Motion

/** How long a card takes to change size (the secure and connect cards folding into one line). */
val FoldSpec = tween<IntSize>(Motion.STANDARD_MS, easing = Motion.Standard)

/** A card's folded result: a small icon and one line ("허용함 · gmail.send"). */
@Composable
fun ResultLine(icon: ImageVector, text: String, muted: Boolean = false) {
    val c = Kelo.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = if (muted) c.muted else c.ink, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (muted) c.muted else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
