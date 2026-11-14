package chat.kelo.app.ui.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated CSS animations, played at their keyframe times. */
class KeloMotionTest {
    private val eps = 1e-3f

    /** The animations of a mood's drawing, in order. */
    private fun anims(g: G, out: MutableList<Anim> = mutableListOf()): List<Anim> {
        g.anim?.let(out::add)
        g.items.forEach { if (it is G) anims(it, out) }
        return out
    }

    @Test fun idleBreathesBlinksAndSways() {
        val (breathe, leaf, blink) = anims(KeloArt.idle)
        assertTrue(KeloMotion.transform(breathe, KeloMotion.progress(breathe, 1_200)))   // 50% of 2.4 s
        assertEquals(0f, breathe.now[0], eps); assertEquals(-2f, breathe.now[1], eps)
        assertEquals(1.02f, breathe.now[2], eps); assertEquals(0.98f, breathe.now[3], eps)
        KeloMotion.transform(leaf, KeloMotion.progress(leaf, 0)); assertEquals(-4f, leaf.now[0], eps)
        KeloMotion.transform(leaf, KeloMotion.progress(leaf, 1_200)); assertEquals(6f, leaf.now[0], eps)
        KeloMotion.transform(blink, KeloMotion.progress(blink, 1_656)); assertEquals(0.1f, blink.now[1], eps)   // 46% of 3.6 s
        KeloMotion.transform(blink, KeloMotion.progress(blink, 1_000)); assertEquals(1f, blink.now[1], eps)
    }

    @Test fun posesTakeTurns() {
        val (front, back) = anims(KeloArt.working)
        assertEquals(1f, KeloMotion.opacity(front, KeloMotion.progress(front, 0)), eps)
        assertEquals(0f, KeloMotion.opacity(back, KeloMotion.progress(back, 0)), eps)
        assertEquals(0f, KeloMotion.opacity(front, KeloMotion.progress(front, 1_800)), eps)
        assertEquals(1f, KeloMotion.opacity(back, KeloMotion.progress(back, 1_800)), eps)
    }

    @Test fun alternateGoesThereAndBack() {
        val bob = anims(KeloArt.working).first { it.durationMs == 300 && it.ops.size == 1 }
        KeloMotion.transform(bob, KeloMotion.progress(bob, 0)); assertEquals(0f, bob.now[1], eps)
        KeloMotion.transform(bob, KeloMotion.progress(bob, 300)); assertEquals(1.5f, bob.now[1], eps)   // the second run starts at the end
        KeloMotion.transform(bob, KeloMotion.progress(bob, 600)); assertEquals(0f, bob.now[1], eps)
        KeloMotion.transform(bob, KeloMotion.progress(bob, 299)); assertEquals(1.5f, bob.now[1], 0.01f)
        KeloMotion.transform(bob, KeloMotion.progress(bob, 450)); assertEquals(0.75f, bob.now[1], eps)
    }

    @Test fun delaysHoldTheElementAtRest() {
        val dots = anims(KeloArt.thinking).filter { it.durationMs == 1_500 }
        assertEquals(listOf(0, 300, 600), dots.map { it.delayMs })
        val dot3 = dots[2]
        assertEquals(-1f, KeloMotion.progress(dot3, 599), 0f)
        assertFalse(KeloMotion.transform(dot3, -1f))
        assertEquals(1f, KeloMotion.opacity(dot3, -1f), 0f)
        assertEquals(0.25f, KeloMotion.opacity(dot3, KeloMotion.progress(dot3, 600)), eps)
        assertEquals(1f, KeloMotion.opacity(dot3, KeloMotion.progress(dot3, 600 + 750)), eps)   // its 50%
    }

    @Test fun shorterTransformListsArePaddedWithIdentity() {
        // sleep-bob: scale(1, 1) → scale(1.03, 0.97) translateY(1px)
        val bob = anims(KeloArt.sleeping).first()
        assertEquals(listOf(1, 0), bob.ops.toList())
        KeloMotion.transform(bob, KeloMotion.progress(bob, 0)); assertEquals(0f, bob.now[3], eps)
        KeloMotion.transform(bob, KeloMotion.progress(bob, 1_600)); assertEquals(1f, bob.now[3], eps)
    }

    @Test fun fillMixesInSrgb() {
        val glow = anims(KeloArt.browser).first { it.fKeys.isNotEmpty() }
        val c = KeloMotion.fill(glow, 0f)!!
        assertEquals(0xE1 / 255f, c.red, 0.01f)
        val d = KeloMotion.fill(glow, 1f)!!
        assertEquals(0xB3 / 255f, d.red, 0.01f)
    }

    @Test fun sparklesTurnAboutTheMiddle() {
        val sparkle = anims(KeloArt.done).last()
        assertEquals(60f, sparkle.ox, 0f); assertEquals(60f, sparkle.oy, 0f)
    }
}
