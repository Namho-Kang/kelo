package chat.kelo.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetDismissTest {
    @Test fun tapOutsideOrBackClosesAtOnce() = assertTrue(SheetDismiss.allow(dragged = 0f, pull = 300f, still = 5f))
    @Test fun aSmallFlickSpringsBack() = assertFalse(SheetDismiss.allow(dragged = 40f, pull = 300f, still = 5f))
    @Test fun aRealPullCloses() = assertTrue(SheetDismiss.allow(dragged = 320f, pull = 300f, still = 5f))
}
