package chat.kelo.app.wallet

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CardScanTextTest {
    @Test fun readsANumberWrittenTogetherSpacedOrDashed() {
        assertEquals("4242424242424242", CardScanText.number("4242424242424242"))
        assertEquals("4242424242424242", CardScanText.number("4242 4242 4242 4242"))
        assertEquals("4242424242424242", CardScanText.number("4242-4242-4242-4242"))
        assertEquals("5555555555554444", CardScanText.number("CARD NO. 5555 5555 5555 4444 "))
    }

    @Test fun readsTheGroupingsOfOtherBrands() {
        assertEquals("378282246310005", CardScanText.number("3782 822463 10005"))      // Amex 4-6-5
        assertEquals("30569309025904", CardScanText.number("3056 930902 5904"))        // Diners 4-6-4
        assertEquals("3530111333300000", CardScanText.number("3530 1113 3330 0000"))   // JCB
        assertEquals("6011111111111117", CardScanText.number("6011 1111 1111 1117"))   // Discover
        assertEquals("4222222222222", CardScanText.number("4222222222222"))            // Visa, 13 digits
    }

    @Test fun fixesLetterLookalikesOnlyBetweenDigits() {
        assertEquals("4242424242424242", CardScanText.number("4242 4242 4Z42 4242"))
        assertEquals("4012888888881881", CardScanText.number("4O12 8888 8888 1881"))
        assertEquals("4012888888881881", CardScanText.number("4012 8888 8888 l88l"))
        // a word stays a word
        assertNull(CardScanText.number("BOSS BANK"))
    }

    @Test fun rejectsNumbersThatFailTheCheckDigit() {
        assertNull(CardScanText.number("4242 4242 4242 4241"))
        assertFalse(CardScanText.plausible("1234567812345678"))
    }

    @Test fun rejectsDigitRunsThatAreNotCardNumbers() {
        assertNull(CardScanText.number("TEL 1588-1688"))                         // too short
        assertNull(CardScanText.number("424242424242"))                         // 12 digits
        assertNull(CardScanText.number("42424242424242424242"))                 // 20 digits: not cut down to fit
        assertNull(CardScanText.number("4242 4242 4242 4242 4242"))             // a longer run with a card inside
        assertNull(CardScanText.number("0000 0000 0000 0000"))                  // passes Luhn, but not a card
        assertNull(CardScanText.number("1111111111111111"))
        assertNull(CardScanText.number("0424242424242424"))                     // leading zero
        assertNull(CardScanText.number("1 2 3 4 5 6 7 8 1 2 3 4 5 6 7 8"))       // scattered digits
        assertNull(CardScanText.number("04/28 12/30"))                          // dates
        assertFalse(CardScanText.plausible("37828224631000"))                   // Amex must have 15
        assertFalse(CardScanText.plausible("55555555555544449"))                // Mastercard must have 16
    }

    @Test fun readsExpiryFormats() {
        assertEquals(listOf("0428"), CardScanText.expiries("VALID THRU 04/28"))
        assertEquals(listOf("0428"), CardScanText.expiries("04-28"))
        assertEquals(listOf("0428"), CardScanText.expiries("04 / 2028"))
        assertEquals(listOf("1230"), CardScanText.expiries("EXP 12.30"))
        assertEquals(listOf("0127", "0132"), CardScanText.expiries("FROM 01/27 THRU 01/32"))
    }

    @Test fun ignoresWhatIsNotAnExpiry() {
        assertTrue(CardScanText.expiries("13/28").isEmpty())          // no month 13
        assertTrue(CardScanText.expiries("00/28").isEmpty())
        assertTrue(CardScanText.expiries("01/02/2028").isEmpty())    // a full date
        assertTrue(CardScanText.expiries("4242-1242-4242").isEmpty()) // pieces of a number
        assertTrue(CardScanText.expiries("4242 1242").isEmpty())
    }

    @Test fun picksTheLaterDate() {
        assertEquals("0132", CardScanText.latest(listOf("0127", "0132")))
        assertEquals("1229", CardScanText.latest(listOf("1229", "0129")))
        assertNull(CardScanText.latest(emptyList()))
    }

    @Test fun joinsGroupsSplitOverLines() {
        val r = CardScanText.read(listOf("KELO BANK", "4242 4242", "4242 4242", "VALID THRU 04/28", "HONG GILDONG"))
        assertEquals("4242424242424242", r.number)
        assertEquals(listOf("0428"), r.expiries)
    }

    @Test fun aReadNeverPrintsTheNumber() {
        assertFalse(CardScanResult("4242424242424242", "0428").toString().contains("4242"))
    }
}

class CardScanAgreementTest {
    private val now = YearMonth.of(2026, 11)
    private fun frame(number: String?, vararg dates: String) = CardScanRead(number, dates.toList())

    @Test fun needsSeveralFramesToAgree() {
        val a = CardScanAgreement { now }
        assertNull(a.offer(frame("4242424242424242", "0428")))
        assertNull(a.offer(frame("4242424242424242", "0428")))
        val r = a.offer(frame("4242424242424242", "0428"))!!
        assertEquals("4242424242424242", r.number)
        assertEquals("0428", r.expiry)
    }

    @Test fun aMisreadFrameDoesNotWin() {
        val a = CardScanAgreement { now }
        a.offer(frame("4242424242424242"))
        a.offer(frame("4012888888881881"))
        a.offer(frame("4242424242424242"))
        assertNull(a.offer(frame("4012888888881881", "0428")))
        val r = a.offer(frame("4242424242424242", "0428"))!!
        assertEquals("4242424242424242", r.number)
    }

    @Test fun waitsAWhileForTheDateThenEndsWithoutIt() {
        val a = CardScanAgreement { now }
        repeat(CardScanAgreement.NUMBER_FRAMES - 1) { assertNull(a.offer(frame("4242424242424242"))) }
        var result: CardScanResult? = null
        var frames = 0
        while (result == null && frames < 50) { result = a.offer(frame("4242424242424242")); frames++ }
        assertEquals("4242424242424242", result!!.number)
        assertNull(result.expiry)
        assertEquals(CardScanAgreement.EXPIRY_GRACE + 1, frames)
    }

    @Test fun skipsAPastValidFromDate() {
        val a = CardScanAgreement { now }
        var r: CardScanResult? = null
        repeat(3) { r = a.offer(frame("4242424242424242", "0124", "0129")) }
        assertEquals("0129", r!!.expiry)
    }

    @Test fun clearForgetsEverything() {
        val a = CardScanAgreement { now }
        a.offer(frame("4242424242424242", "0428"))
        a.offer(frame("4242424242424242", "0428"))
        a.clear()
        assertNull(a.offer(frame("4242424242424242", "0428")))
    }
}

class CardScanFillTest {
    @Test fun googlePayResultFillsNumberAndExpiry() {
        val r = CardScanFill.fromGooglePay("4242 4242 4242 4242", 4, 2028)!!
        assertEquals("4242424242424242", r.number)
        assertEquals("0428", r.expiry)
    }

    @Test fun googlePayResultWithoutAGoodNumberIsNothing() {
        assertNull(CardScanFill.fromGooglePay(null, 4, 2028))
        assertNull(CardScanFill.fromGooglePay("4242424242424241", 4, 2028))
        assertNull(CardScanFill.fromGooglePay("", null, null))
    }

    @Test fun googlePayResultWithoutAUsableDateFillsOnlyTheNumber() {
        assertNull(CardScanFill.fromGooglePay("4242424242424242", null, null)!!.expiry)
        assertNull(CardScanFill.fromGooglePay("4242424242424242", 13, 2028)!!.expiry)
        assertNull(CardScanFill.fromGooglePay("4242424242424242", 4, 28)!!.expiry)
    }

    @Test fun aScanFillsTheFormButNeverTheCvcOrTheStep() {
        val before = AddCardDraft(number = "41", cvc = "123", holder = "HONG", nickname = "생활비")
        val after = CardScanFill.apply(before, CardScanFill.fromGooglePay("378282246310005", 7, 2031)!!)
        assertEquals("378282246310005", after.number)
        assertEquals("0731", after.expiry)
        assertEquals("123", after.cvc)
        assertEquals("HONG", after.holder)
        assertEquals("생활비", after.nickname)
        assertEquals(AddStep.NUMBER, after.step)
    }

    @Test fun aScanWithoutADateKeepsATypedOne() {
        val after = CardScanFill.apply(AddCardDraft(expiry = "0529"), CardScanResult("4242424242424242", null))
        assertEquals("0529", after.expiry)
    }
}

class CardScanChoiceTest {
    private fun probe(camera: Boolean = true, play: Boolean = true, pay: Boolean = true, text: Boolean = true) = ScanProbe(camera, play, pay, text)

    @Test fun googlePayFirst() = assertEquals(ScanPath.GOOGLE_PAY, CardScanChoice.path(probe()))
    @Test fun cameraScanWhenGooglePayIsNotThere() = assertEquals(ScanPath.ON_DEVICE, CardScanChoice.path(probe(pay = false)))
    @Test fun noButtonWithoutPlayServices() = assertEquals(ScanPath.NONE, CardScanChoice.path(probe(play = false)))
    @Test fun noButtonWithoutACamera() = assertEquals(ScanPath.NONE, CardScanChoice.path(probe(camera = false)))
    @Test fun noButtonWithNeither() = assertEquals(ScanPath.NONE, CardScanChoice.path(probe(pay = false, text = false)))
    @Test fun googlePayFailingLaterFallsBack() {
        assertEquals(ScanPath.ON_DEVICE, CardScanChoice.afterGooglePayFailed(probe()))
        assertEquals(ScanPath.NONE, CardScanChoice.afterGooglePayFailed(probe(text = false)))
    }
}

class ScanPathCacheTest {
    @Test fun checksOnlyOnce() = runTest {
        var calls = 0
        val cache = ScanPathCache { calls++; ScanProbe(true, true, true, true) }
        assertNull(cache.path)
        val all = List(5) { async { cache.get() } }.awaitAll()
        assertTrue(all.all { it == ScanPath.GOOGLE_PAY })
        assertEquals(ScanPath.GOOGLE_PAY, cache.get())
        assertEquals(1, calls)
        assertEquals(ScanPath.GOOGLE_PAY, cache.path)
    }

    @Test fun aFailingCheckMeansNoButton() = runTest {
        val cache = ScanPathCache { throw IllegalStateException("no Play services") }
        assertEquals(ScanPath.NONE, cache.get())
        assertEquals(ScanPath.NONE, cache.googlePayFailed())
    }

    @Test fun googlePayFailingSwitchesToTheCameraForGood() = runTest {
        var calls = 0
        val cache = ScanPathCache { calls++; ScanProbe(true, true, true, true) }
        assertEquals(ScanPath.GOOGLE_PAY, cache.get())
        assertEquals(ScanPath.ON_DEVICE, cache.googlePayFailed())
        assertEquals(ScanPath.ON_DEVICE, cache.get())
        assertEquals(1, calls)
    }

    @Test fun googlePayFailingWithoutTheCameraScanHidesTheButton() = runTest {
        val cache = ScanPathCache { ScanProbe(true, true, true, false) }
        cache.get()
        assertEquals(ScanPath.NONE, cache.googlePayFailed())
    }
}
