package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.pressShrink

/** From the screen's end edge to the "↓" button's: the card's 12dp margin plus 12dp, which centres it over 보내기. */
val JUMP_END = 24.dp

/** The "↓" on the composer's top border that takes the list back to the newest message; the dot: the assistant wrote more meanwhile. */
@Composable
fun JumpPill(unread: Boolean, onClick: () -> Unit) {
    val c = Kelo.colors
    val source = remember { MutableInteractionSource() }
    val label = string(if (unread) Res.string.chat_jump_latest_new else Res.string.chat_jump_latest)
    Box {
        Box(
            Modifier
                .pressShrink(source)
                .shadow(6.dp, CircleShape, clip = false)
                .size(ChatOverlayRules.JUMP_SIZE.dp)
                .clip(CircleShape)
                .background(c.card)
                .border(1.dp, c.line, CircleShape)
                .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(KeloIcons.ArrowDown, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp)) }
        if (unread) {
            Box(
                Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(c.card).padding(2.dp)
                    .clip(CircleShape).background(c.accent),
            )
        }
    }
}
