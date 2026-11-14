package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "↓" button sits on the composer's top border, clear of the field's text, instead of a band being cut out of the list. */
class ChatOverlayRulesTest {
    @Test fun theButtonStraddlesTheComposerTopBorder() {
        val size = ChatOverlayRules.JUMP_SIZE
        val lift = ChatOverlayRules.jumpLift(cardTop = 120f)
        assertEquals(120f - size / 2f, lift)                                              // its lower edge half its size into the card
        assertTrue(lift < 120f && lift + size > 120f)                                     // across the border: nothing of it floats free
    }

    @Test fun withAttachmentsTheButtonSitsOnTopOfThem() {
        val lift = ChatOverlayRules.jumpLift(cardTop = 120f, trayTop = 190f)
        assertEquals(190f, lift)                                                          // never over an attachment chip
        assertEquals(ChatOverlayRules.jumpLift(120f), ChatOverlayRules.jumpLift(120f, trayTop = 0f))
    }

    @Test fun theFieldKeepsItsFirstLineClearOfTheButton() {
        val size = ChatOverlayRules.JUMP_SIZE
        // Android/desktop: an OutlinedTextField has 16dp above its first line; iOS: 12pt
        for (inset in listOf(16f, 12f, 0f, 40f)) {
            val extra = ChatOverlayRules.fieldTopExtra(inset)
            assertTrue(extra >= 0f)
            assertTrue(inset + extra - size / 2f >= ChatOverlayRules.FIELD_GAP)            // the part inside the card ends above the text
        }
        assertEquals(6f, ChatOverlayRules.fieldTopExtra(16f))
        assertEquals(0f, ChatOverlayRules.fieldTopExtra(40f))                             // enough room already: nothing added
    }

    @Test fun scrolledToTheEndTheLastRowSitsJustAboveTheComposer() {
        // Android / desktop: the list keeps exactly the composer's height free; the composer has 6 of room above its card
        val fade = ChatOverlayRules.scrimFade(composerTop = 6f)
        assertEquals(ChatOverlayRules.LIST_GAP, fade + 6f)
        // iOS: its list keeps 6 after the last row (8 between rows, the end marker pulled up 2), so the scrim has no room of its own
        assertEquals(ChatOverlayRules.LIST_GAP, ChatOverlayRules.scrimFade(composerTop = 6f, listEnd = 6f) + 6f + 6f)
        assertTrue(ChatOverlayRules.LIST_GAP in 10f..14f)                                // about the space between two bubbles (8), a bit more
        assertEquals(0f, ChatOverlayRules.scrimFade(composerTop = 20f))                  // never negative
    }
}
