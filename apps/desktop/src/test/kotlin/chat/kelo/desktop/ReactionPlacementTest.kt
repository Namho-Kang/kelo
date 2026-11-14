package chat.kelo.desktop

import chat.kelo.app.chat.BubbleWithReaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.Reactions
import chat.kelo.app.chat.ReactionEndInset
import chat.kelo.app.chat.ReactionOverhang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Where the reaction chip sits on the user's bubble (BubbleWithReaction, as on Android): over the bottom edge near the end corner,
 * hanging [ReactionOverhang] below it inside room the layout keeps; centred under a short bubble; mirrored in right-to-left.
 * Measured in dp at density 1.
 */
class ReactionPlacementTest {
    @get:Rule val compose = createComposeRule()

    private fun place(bubbleWidth: Int, emoji: String = Reactions.READ, rtl: Boolean = false): Triple<Rect, Rect, Rect> {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Row(Modifier.width(400.dp)) {
                    BubbleWithReaction(emoji, Modifier.testTag("row")) {
                        Box(Modifier.size(bubbleWidth.dp, 44.dp).background(Color.Blue).testTag("bubble"))
                    }
                }
            }
        }
        compose.waitForIdle()
        fun bounds(n: SemanticsMatcher) = compose.onAllNodes(n, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        // the chip is the one node with a spoken label ("읽음" / "Read", then the emoji)
        return Triple(bounds(hasTestTag("row")), bounds(hasTestTag("bubble")), bounds(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)))
    }

    @Test fun nearTheEndCornerOfALongBubble() {
        val (row, bubble, chip) = place(260)
        assertEquals(bubble.right - ReactionEndInset.value, chip.right, 0.5f)                     // 10 in from the end edge (12 to what shows)
        assertTrue("well away from the start edge", chip.left > bubble.left + 100)
        assertEquals(bubble.bottom + ReactionOverhang.value, chip.bottom, 0.5f)                  // hangs below, over the bottom edge
        assertTrue("overlaps the bubble", chip.top < bubble.bottom)
        assertEquals(bubble.bottom + ReactionOverhang.value, row.bottom, 0.5f)                   // room for it, so the next row is clear
        assertEquals(bubble.width, row.width, 0.5f)
        assertTrue("small: at most 26 by 24 with its halo (${chip.width} by ${chip.height})", chip.width <= 27f && chip.height <= 25f)
    }

    @Test fun aZwjEmojiIsOneSmallPicture() {
        val runner = "🏃‍♂️"   // 🏃‍♂️
        val (_, bubble, chip) = place(260, runner)
        assertEquals(bubble.right - ReactionEndInset.value, chip.right, 0.5f)
        assertTrue("one picture, not a man and a sign side by side (${chip.width})", chip.width <= 30f)
    }

    @Test fun centredUnderAShortBubble() {
        val (_, bubble, chip) = place(44)
        assertEquals(bubble.center.x, chip.center.x, 0.75f)
        assertTrue("never past the start edge", chip.left >= bubble.left)
    }

    @Test fun mirroredInRightToLeft() {
        val (_, bubble, chip) = place(260, rtl = true)
        assertEquals(bubble.left + ReactionEndInset.value, chip.left, 0.5f)                       // the end is on the left
        assertEquals(bubble.bottom + ReactionOverhang.value, chip.bottom, 0.5f)
    }
}
