package chat.kelo.app.chat

import chat.kelo.app.ui.platform.FormFactor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardDismissRulesTest {
    @Test fun `a plain tap closes the keyboard`() = assertTrue(KeyboardDismissRules.tapCloses(false, 2f, 3f, 8f))
    @Test fun `a tap a button or link took does not`() = assertFalse(KeyboardDismissRules.tapCloses(true, 0f, 0f, 8f))
    @Test fun `a movement past the touch slop is not a tap`() = assertFalse(KeyboardDismissRules.tapCloses(false, 0f, 20f, 8f))
    @Test fun `only phones`() {
        assertTrue(KeyboardDismissRules.applies(FormFactor.Phone))
        assertFalse(KeyboardDismissRules.applies(FormFactor.Desktop))
    }
}
