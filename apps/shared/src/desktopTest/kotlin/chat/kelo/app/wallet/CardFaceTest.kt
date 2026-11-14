package chat.kelo.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Card faces and the stepped add form. */
class CardFaceTest {
    @Test fun everyBrandHasItsOwnFace() {
        val faces = CardBrand.entries.map { CardFaceRules.palette(it) }
        assertEquals(faces.size, faces.toSet().size)
        faces.forEach { (a, b) -> assertTrue(a != b) }   // a gradient, not a flat colour
    }

    @Test fun thePreviewShowsWhatIsTypedAndHidesTheMiddle() {
        assertEquals("•••• •••• •••• ••••", CardFaceRules.preview(""))
        assertEquals("42•• •••• •••• ••••", CardFaceRules.preview("42"))
        assertEquals("4242 4242 42•• ••••", CardFaceRules.preview("4242424242"))
        // long enough: only the first six and the last four in full
        assertEquals("4242 42•• •••• 4242", CardFaceRules.preview("4242424242424242"))
        // American Express groups 4-6-5 over 15 digits
        assertEquals("3782 82•••• •0005", CardFaceRules.preview("378282246310005"))
    }

    private val now = YearMonth.of(2026, 11)

    @Test fun eachStepLetsGoOnlyWhenItIsComplete() {
        assertFalse(AddCardSteps.canLeave(AddStep.NUMBER, "4242", "", "", false, now))
        assertFalse(AddCardSteps.canLeave(AddStep.NUMBER, "4242424242424241", "", "", false, now))   // fails the check digit
        assertTrue(AddCardSteps.canLeave(AddStep.NUMBER, "4242424242424242", "", "", false, now))
        assertFalse(AddCardSteps.canLeave(AddStep.DATES, "4242424242424242", "1026", "123", false, now))   // expired
        assertFalse(AddCardSteps.canLeave(AddStep.DATES, "4242424242424242", "1228", "12", false, now))    // short CVC
        assertTrue(AddCardSteps.canLeave(AddStep.DATES, "4242424242424242", "1228", "123", false, now))
        assertFalse(AddCardSteps.canLeave(AddStep.DATES, "378282246310005", "1228", "123", false, now))   // Amex wants four
        assertTrue(AddCardSteps.canLeave(AddStep.OWNER, "", "", "", false, now))                          // name and postal are optional
        assertFalse(AddCardSteps.canLeave(AddStep.CONFIRM, "4242424242424242", "1228", "123", false, now)) // not ticked
        assertTrue(AddCardSteps.canLeave(AddStep.CONFIRM, "4242424242424242", "1228", "123", true, now))
    }

    @Test fun stepsInOrderAndWhereToGoBack() {
        assertEquals(AddStep.DATES, AddCardSteps.next(AddStep.NUMBER))
        assertEquals(AddStep.CONFIRM, AddCardSteps.next(AddStep.CONFIRM))
        assertNull(AddCardSteps.prev(AddStep.NUMBER))
        assertEquals(AddStep.OWNER, AddCardSteps.prev(AddStep.CONFIRM))
        assertEquals(2 to 4, AddCardSteps.position(AddStep.DATES))
        assertEquals(AddStep.NUMBER, AddCardSteps.firstBlocking("1", "1228", "123", now))
        assertEquals(AddStep.DATES, AddCardSteps.firstBlocking("4242424242424242", "", "123", now))
        assertNull(AddCardSteps.firstBlocking("4242424242424242", "1228", "123", now))
    }
}
