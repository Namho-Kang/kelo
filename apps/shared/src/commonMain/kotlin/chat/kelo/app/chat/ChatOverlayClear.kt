package chat.kelo.app.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The "↓" button is part of the composer, not of the list (at the top the chat is like every other page: the messages scroll on
 * under the floating agent). It straddles the composer card's top border at the end side (its lower half on the card's empty
 * top-end strip, which the text field keeps clear: [fieldTopExtra]), so the list runs on down to the composer with no band cut out.
 * Plain numbers (dp or points), shared with the desktop app and ported to iOS (KeloChat/Logic/ChatOverlay.swift).
 */
object ChatOverlayRules {
    /**
     * Scrolled to the end, the room between the last row and the composer's top (its card, or the attachments waiting above it): a
     * little more than between two bubbles (8). The "↓" sits on the composer's edge and shows only away from the end, so it needs
     * none.
     */
    const val LIST_GAP = 12f
    /** The "↓" button's diameter. */
    const val JUMP_SIZE = 36f
    /** The least room between the button's lower edge and the text field's first line. */
    const val FIELD_GAP = 4f

    /**
     * How far above the bottom the "↓" button's lower edge sits. [cardTop]: the composer card's top edge above the bottom. [trayTop]:
     * the top of the attachment tray above the card, 0 without one. The button straddles the card's top border; while attachments wait
     * above the card it sits on top of them instead (its lower half would cover a chip).
     */
    fun jumpLift(cardTop: Float, trayTop: Float = 0f, size: Float = JUMP_SIZE): Float =
        if (trayTop > cardTop) trayTop else (cardTop - size / 2f).coerceAtLeast(0f)

    /**
     * The extra room the composer's text field needs at its top so the button's lower half (inside the card) stays [FIELD_GAP] clear
     * of the first line, given the field's own top inset [fieldTop] (the space above its first line's box).
     */
    fun fieldTopExtra(fieldTop: Float, size: Float = JUMP_SIZE): Float = (size / 2f + FIELD_GAP - fieldTop).coerceAtLeast(0f)

    /**
     * The composer's transparent room above its content (the top of its scrim), so the last row ends [LIST_GAP] above the composer:
     * what is left after the composer's own top room [composerTop] and the room the list itself keeps after its last row [listEnd].
     * The list keeps the composer's whole measured height free at its end, so this holds as the field grows, with the attachments
     * open and with the keyboard up.
     */
    fun scrimFade(composerTop: Float, listEnd: Float = 0f): Float = (LIST_GAP - composerTop - listEnd).coerceAtLeast(0f)
}

/**
 * The "↓" button's state. The message list knows when it shows (away from the newest row), whether something new came meanwhile and
 * how to go back down; the screen draws it over the composer, after it, so the button sits on top of the card.
 */
@Stable
class JumpButtonState {
    var shown by mutableStateOf(false)
    var unread by mutableStateOf(false)
    var onClick: () -> Unit = {}
}
