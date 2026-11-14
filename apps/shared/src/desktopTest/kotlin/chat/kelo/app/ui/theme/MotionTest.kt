package chat.kelo.app.ui.theme

import chat.kelo.app.ui.components.SkeletonTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun animationsOffOnlyAtZeroScale() {
        assertTrue(Motion.isOff(0f))
        assertFalse(Motion.isOff(0.5f))
        assertFalse(Motion.isOff(1f))
    }

    @Test fun theCurvesAreMaterial3() {
        // emphasized decelerate: most of the way there early (arrivals)
        assertEquals(0f, Motion.EmphasizedDecelerate.transform(0f), 0.001f)
        assertEquals(1f, Motion.EmphasizedDecelerate.transform(1f), 0.001f)
        assertTrue(Motion.EmphasizedDecelerate.transform(0.3f) > 0.75f)
        // emphasized accelerate: slow to leave, then gone (exits)
        assertTrue(Motion.EmphasizedAccelerate.transform(0.5f) < 0.3f)
        assertEquals(1f, Motion.EmphasizedAccelerate.transform(1f), 0.001f)
        // standard sits between the two
        val mid = Motion.Standard.transform(0.5f)
        assertTrue(mid > Motion.EmphasizedAccelerate.transform(0.5f) && mid < Motion.EmphasizedDecelerate.transform(0.5f))
    }

    @Test fun durationsFollowTheMaterialScale() {
        assertEquals(400, Motion.ENTER_MS)       // medium 4
        assertEquals(200, Motion.EXIT_MS)        // short 4
        assertEquals(300, Motion.STANDARD_MS)    // medium 2
        assertEquals(150, Motion.SMALL_MS)       // short 3
        assertEquals(100, Motion.PRESS_MS)       // short 2
        assertTrue(Motion.EXIT_MS < Motion.ENTER_MS)
        assertEquals(0.96f, Motion.PRESS_SHRINK, 0.0001f)
        assertEquals(1200, SkeletonTiming.SWEEP_MS)
    }

    @Test fun skeletonStaysAtLeastItsMinimum() {
        assertEquals(500L, SkeletonTiming.remaining(1000, 1000))
        assertEquals(400L, SkeletonTiming.remaining(1000, 1100))
        assertEquals(0L, SkeletonTiming.remaining(1000, 1600))
    }
}
