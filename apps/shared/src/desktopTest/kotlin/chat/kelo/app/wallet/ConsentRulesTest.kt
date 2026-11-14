package chat.kelo.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentRulesTest {
    @Test fun saveNeedsTheBoxTicked() {
        assertFalse(ConsentRules.canSave(detailsReady = true, acknowledged = false))
        assertFalse(ConsentRules.canSave(detailsReady = false, acknowledged = true))
        assertTrue(ConsentRules.canSave(detailsReady = true, acknowledged = true))
    }

    @Test fun recordsKeepVersionAndTime() {
        val r = ConsentRules.record(WalletConsent.CARD_NOTICE_VERSION, 1762500000000)
        assertEquals("v1@1762500000000", r)
        assertEquals(1 to 1762500000000L, ConsentRules.parse(r))
        assertNull(ConsentRules.parse("junk"))
        assertNull(ConsentRules.parse(null))
        assertEquals("card_notice:card_1", ConsentRules.cardKey("card_1"))
        assertEquals("autopay_notice:card_1", ConsentRules.autoPayKey("card_1"))
    }
}
