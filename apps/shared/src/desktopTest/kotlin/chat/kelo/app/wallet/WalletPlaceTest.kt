package chat.kelo.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletPlaceTest {
    private val min = 60_000L
    private val typed = AddCardDraft(step = AddStep.DATES, number = "4242424242424242", expiry = "04", cvc = "1")

    @Test fun addDraftSurvivesTheLockAndComesBack() {
        val p = WalletPlace()
        p.go(WalletView.Add, 0)
        p.saveAdd(typed, 1_000)
        // locked, away for three minutes, unlocked
        assertEquals(WalletView.Add, p.view(4 * min))
        assertEquals(typed, p.add(4 * min))
        assertTrue(p.hasTyping(4 * min))
    }

    @Test fun placeRunsOutTenMinutesAfterTheLastEdit() {
        val p = WalletPlace()
        p.go(WalletView.Add, 0)
        p.saveAdd(typed, 2 * min)
        assertEquals(typed, p.add(11 * min))          // nine minutes after the last edit: still there
        assertNull(p.add(12 * min))                   // ten: gone
        assertEquals(WalletView.List, p.view(12 * min))
        assertFalse(p.hasTyping(12 * min))
        assertEquals(10 * min, WalletPlace.LIMIT_MS)
    }

    @Test fun touchesKeepThePlaceAlive() {
        val p = WalletPlace()
        p.go(WalletView.Add, 0)
        p.saveAdd(typed, 0)
        p.touch(9 * min)
        assertEquals(typed, p.add(18 * min))
        assertNull(p.add(19 * min))
    }

    @Test fun clockGoingBackwardsForgets() {
        val p = WalletPlace()
        p.go(WalletView.Add, 5 * min)
        p.saveAdd(typed, 5 * min)
        assertNull(p.add(1 * min))
    }

    @Test fun backToTheListCancelsTheFlow() {
        val p = WalletPlace()
        p.go(WalletView.Add, 0)
        p.saveAdd(typed, 0)
        p.go(WalletView.List, 1_000)
        assertNull(p.add(1_000))
        assertEquals(WalletView.List, p.view(1_000))
        // a late save from the page that is going away does not bring it back
        p.saveAdd(typed, 2_000)
        p.go(WalletView.Add, 3_000)
        assertNull(p.add(3_000))
    }

    @Test fun clearForgetsEverything() {
        val p = WalletPlace()
        p.go(WalletView.Add, 0)
        p.saveAdd(typed, 0)
        p.clear()
        assertNull(p.add(0))
        assertEquals(WalletView.List, p.view(0))
        assertNull(p.expiresAt())
    }

    @Test fun emptyDraftIsNotKept() {
        val p = WalletPlace()
        p.go(WalletView.Add, 0)
        p.saveAdd(typed, 0)
        p.saveAdd(AddCardDraft(), 1)
        assertNull(p.add(2))
        assertEquals(WalletView.Add, p.view(2))       // still on the page, nothing typed
        assertFalse(p.hasTyping(2))
    }

    @Test fun otherPagesAreRememberedWithoutTyping() {
        val p = WalletPlace()
        p.go(WalletView.Card("c1"), 0)
        assertEquals(WalletView.Card("c1"), p.view(min))
        assertFalse(p.hasTyping(min))
        p.go(WalletView.Add, min)
        p.saveAdd(typed, min)
        p.go(WalletView.Limits, 2 * min)              // leaving 등록 for another page drops what was typed there
        assertNull(p.add(2 * min))
    }

    @Test fun reenterDraftBelongsToItsCard() {
        val p = WalletPlace()
        val v = WalletView.Reenter("c1", autoPay = true)
        p.go(v, 0)
        p.saveReenter(ReenterDraft("c1", true, "4242", "12"), 0)
        assertEquals("4242", p.reenter("c1", true, min)?.number)
        assertNull(p.reenter("c1", false, min))
        assertNull(p.reenter("c2", true, min))
        p.saveReenter(ReenterDraft("c2", true, "5555", ""), min)   // not the page shown: ignored
        assertEquals("4242", p.reenter("c1", true, min)?.number)
        p.go(WalletView.Card("c1"), 2 * min)
        assertNull(p.reenter("c1", true, 2 * min))
        p.go(v, 2 * min)
        assertNull(p.reenter("c1", true, 2 * min))
    }

    @Test fun draftsNeverPrintWhatWasTyped() {
        val s = typed.toString() + ReenterDraft("c1", false, "4242424242424242", "123")
        assertFalse(s.contains("4242"))
        assertFalse(s.contains("123"))
    }

    // the lock

    @Test fun firstShowAsksByItself() {
        val f = UnlockFlow()
        assertEquals(LockPhase.LOCKED, f.phase)
        assertTrue(f.resumed())
        assertEquals(LockPhase.PROMPTING, f.phase)
        assertFalse(f.resumed())                      // a second resume while the check is up does not ask twice
        f.opened()
        assertTrue(f.isOpen)
    }

    @Test fun comingBackFromTheHomeScreenAsksAgainOnce() {
        val f = UnlockFlow()
        f.resumed(); f.opened()
        f.backgrounded()
        assertEquals(LockPhase.LOCKED, f.phase)
        assertTrue(f.resumed())
        assertFalse(f.resumed())
    }

    @Test fun backingOutLeavesTheButtonAndNeverLoops() {
        val f = UnlockFlow()
        f.resumed()
        f.notOpened()
        assertEquals(LockPhase.NEEDS_TAP, f.phase)
        assertFalse(f.resumed())                      // the check's own window going away resumes the page: no new prompt
        assertFalse(f.resumed())
        assertTrue(f.tap())                           // the button asks again
        assertEquals(LockPhase.PROMPTING, f.phase)
        f.notOpened()
        assertEquals(LockPhase.NEEDS_TAP, f.phase)
    }

    @Test fun leavingAfterBackingOutAsksAgainOnReturn() {
        val f = UnlockFlow()
        f.resumed(); f.notOpened()
        f.backgrounded()
        assertTrue(f.resumed())
    }

    @Test fun checkTakenAwayByLeavingTheAppAsksAgainOnReturn() {
        val f = UnlockFlow()
        f.resumed()
        f.backgrounded()                              // went home with the check up
        assertEquals(LockPhase.PROMPTING, f.phase)
        f.notOpened()                                 // the system cancelled it
        assertEquals(LockPhase.LOCKED, f.phase)
        assertTrue(f.resumed())
    }

    @Test fun checkTakenAwayWithoutAWordAsksAgainOnReturn() {
        val f = UnlockFlow()
        f.resumed()
        f.backgrounded()
        assertTrue(f.resumed())                       // no cancel came: ask again, once
        assertFalse(f.resumed())
        f.notOpened()                                 // the old check's late cancel, or backing out of the new one
        assertEquals(LockPhase.NEEDS_TAP, f.phase)
    }

    @Test fun oldPhonesScreenLockPageIsNotMistakenForLeaving() {
        // Android 9 and older: the PIN page is its own activity, so the app stops while it is up
        val f = UnlockFlow(credentialLeavesApp = true)
        f.resumed()
        f.backgrounded()
        assertFalse(f.resumed())
        f.notOpened()                                 // backed out of the PIN page
        assertEquals(LockPhase.NEEDS_TAP, f.phase)
        assertFalse(f.resumed())
        f.tap(); f.backgrounded(); f.opened()
        assertTrue(f.isOpen)
    }

    @Test fun idleTimeoutLocksAndAsks() {
        val f = UnlockFlow()
        f.resumed(); f.opened()
        f.timedOut()
        assertEquals(LockPhase.LOCKED, f.phase)
        assertTrue(f.resumed())
    }

    @Test fun noScreenLockIsCheckedAgainAfterSettings() {
        val f = UnlockFlow()
        f.resumed(); f.noLock()
        assertEquals(LockPhase.NO_LOCK, f.phase)
        assertFalse(f.resumed())
        f.backgrounded()                              // off to the phone's settings and back
        assertTrue(f.resumed())
    }

    @Test fun tapDoesNothingWhenOpen() {
        val f = UnlockFlow()
        f.resumed(); f.opened()
        assertFalse(f.tap())
        f.notOpened()                                 // a stray answer does not close an open page
        assertTrue(f.isOpen)
    }

    @Test fun wallLockTimeoutIsUnchanged() {
        val l = WalletLock()
        l.unlock(0)
        assertTrue(l.isOpen(WalletLock.TIMEOUT_MS - 1))
        assertFalse(l.isOpen(WalletLock.TIMEOUT_MS))
    }

    @Test fun addressDraftSurvivesTheLockOnlyOnItsOwnPage() {
        val p = WalletPlace()
        val d = ShippingEditDraft(null, "집", AddressDraft("KR", mapOf("name" to "홍길동")), makeDefault = true, touched = true)
        p.go(WalletView.ShippingEdit(null), 0)
        p.saveShipping(d, 1_000)
        assertEquals(d, p.shipping(null, 3 * min))
        assertTrue(p.hasTyping(3 * min))
        assertNull(p.shipping("ship_1", 3 * min))            // another address's page
        // an untouched page keeps nothing
        val q = WalletPlace()
        q.go(WalletView.ShippingEdit("ship_1"), 0)
        q.saveShipping(d.copy(profileId = "ship_1", touched = false), 0)
        assertNull(q.shipping("ship_1", 0))
        assertFalse(q.hasTyping(0))
        // saved: forgotten; leaving for another page forgets it too
        p.saveShipping(null, 4 * min)
        assertNull(p.shipping(null, 4 * min))
        p.saveShipping(d, 4 * min)
        p.go(WalletView.Limits, 5 * min)
        assertNull(p.shipping(null, 5 * min))
        assertFalse(d.toString().contains("홍길동"))
    }
}
