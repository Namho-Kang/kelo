package chat.kelo.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion

/**
 * A small floating pill saying the screen shows what was kept from last time because the server could not be reached. It floats
 * over the page (nothing under it moves when it comes or goes) and fades in and out. [onRetry], when given, makes it tappable.
 */
@Composable
fun StaleHint(visible: Boolean, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = fadeIn(Motion.small(reduce)), exit = fadeOut(Motion.small(reduce)),
    ) {
        val c = Kelo.colors
        val shape = RoundedCornerShape(50)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                Modifier
                    .widthIn(max = 420.dp)
                    .shadow(3.dp, shape, clip = false)
                    .clip(shape)
                    .background(c.card)
                    .border(1.dp, c.line, shape)
                    .then(if (onRetry != null) Modifier.clickable(role = Role.Button, onClick = onRetry) else Modifier)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.muted))
                Text(
                    string(Res.string.stale_hint), color = c.muted, fontSize = 13.sp, lineHeight = 17.sp,
                    textAlign = TextAlign.Center, maxLines = 3,
                )
            }
        }
    }
}
