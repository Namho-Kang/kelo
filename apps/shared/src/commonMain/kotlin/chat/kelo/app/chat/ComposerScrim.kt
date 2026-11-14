package chat.kelo.app.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * How far above the composer's pill the fade begins: the room left for the gap between the last message and the composer
 * (ChatOverlayRules.LIST_GAP, 12) after the composer's own 6 of top room.
 */
val ComposerScrimFade = ChatOverlayRules.scrimFade(composerTop = 6f).dp

/**
 * The scrim the composer floats on: messages that scroll under it fade out into the screen's background ([bg]) instead of being
 * cut off by an opaque band. Transparent at its top ([ComposerScrimFade] above the pill), [bg] from a little below the pill's top
 * edge down to the bottom, where the tab bar (or the keyboard) begins. The element keeps [ComposerScrimFade] of room at its top.
 */
fun Modifier.composerScrim(bg: Color): Modifier = drawBehind {
    val solidFrom = (ComposerScrimFade.toPx() + 16.dp.toPx()).coerceAtMost(size.height)
    drawRect(
        Brush.verticalGradient(
            0f to bg.copy(alpha = 0f), (solidFrom / size.height).coerceIn(0f, 1f) to bg, 1f to bg,
            startY = 0f, endY = size.height,
        ),
        topLeft = Offset.Zero, size = size,
    )
}.padding(top = ComposerScrimFade)
