package chat.kelo.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CardRulesTest {
    @Test fun digitsDropSpacesAndDashesAndStopAt19() {
        assertEquals("4242424242424242", CardNumber.digits("4242 4242-4242 4242"))
        assertEquals(19, CardNumber.digits("1".repeat(30)).length)
        assertEquals("", CardNumber.digits("abc"))
    }

    @Test fun luhnMatchesTheServer() {
        assertTrue(CardNumber.luhn("4242424242424242"))
        assertTrue(CardNumber.luhn("5555555555554444"))
        assertTrue(CardNumber.luhn("378282246310005"))
        assertFalse(CardNumber.luhn("4242424242424241"))
        assertFalse(CardNumber.luhn(""))
        assertFalse(CardNumber.luhn("4242a"))
        assertTrue(CardNumber.valid("4242 4242 4242 4242"))
        assertFalse(CardNumber.valid("42424242424"))   // 11 digits
    }

    @Test fun brandDetectionFollowsWalletPy() {
        assertEquals(CardBrand.VISA, CardNumber.brand("4242424242424242"))
        assertEquals(CardBrand.VISA, CardNumber.brand("4"))
        assertEquals(CardBrand.MASTERCARD, CardNumber.brand("5555555555554444"))
        assertEquals(CardBrand.MASTERCARD, CardNumber.brand("2221000000000009"))
        assertEquals(CardBrand.AMEX, CardNumber.brand("378282246310005"))
        assertEquals(CardBrand.AMEX, CardNumber.brand("34"))
        assertEquals(CardBrand.JCB, CardNumber.brand("3530111333300000"))
        assertEquals(CardBrand.DISCOVER, CardNumber.brand("6011111111111117"))
        assertEquals(CardBrand.DISCOVER, CardNumber.brand("6445000000000000"))
        assertEquals(CardBrand.UNIONPAY, CardNumber.brand("6200000000000005"))
        assertEquals(CardBrand.DINERS, CardNumber.brand("30569309025904"))
        assertEquals(CardBrand.DINERS, CardNumber.brand("36"))
        assertEquals(CardBrand.OTHER, CardNumber.brand("9999"))
        assertEquals(CardBrand.OTHER, CardNumber.brand(""))
        assertEquals(CardBrand.MASTERCARD, CardBrand.of("MasterCard"))
        assertEquals(CardBrand.OTHER, CardBrand.of(null))
    }

    @Test fun groupingIsFourFourFourFourAndAmexFourSixFive() {
        assertEquals("4242 4242 4242 4242", CardNumber.group("4242424242424242"))
        assertEquals("5555 5555 5555 4444", CardNumber.group("5555555555554444"))
        assertEquals("4242 42", CardNumber.group("424242"))
        assertEquals("4242", CardNumber.group("4242"))
        assertEquals("3782 822463 10005", CardNumber.group("378282246310005"))
        assertEquals("3056 930902 5904", CardNumber.group("30569309025904"))
        assertEquals("4242 4242 4242 4242 424", CardNumber.group("4242424242424242424"))
        assertEquals("", CardNumber.group(""))
    }

    @Test fun cursorOffsetsMapBothWays() {
        val n = "42424242"
        assertEquals(4, CardNumber.groupedOffset(n, 4))   // before the space, not after it
        assertEquals(6, CardNumber.groupedOffset(n, 5))
        assertEquals(9, CardNumber.groupedOffset(n, 8))
        for (i in 0..n.length) assertEquals(i, CardNumber.rawOffset(n, CardNumber.groupedOffset(n, i)))
        val amex = "378282246310005"
        assertEquals(11, CardNumber.groupedOffset(amex, 10))
        assertEquals(13, CardNumber.groupedOffset(amex, 11))
        for (i in 0..amex.length) assertEquals(i, CardNumber.rawOffset(amex, CardNumber.groupedOffset(amex, i)))
    }

    @Test fun lengthsAndCvc() {
        assertEquals(15, CardNumber.maxLength(CardBrand.AMEX))
        assertEquals(19, CardNumber.maxLength(CardBrand.VISA))
        assertEquals("4242", CardNumber.last4("4242 4242 4242 4242"))
        assertEquals("123", Cvc.clean("12a34", CardBrand.VISA))
        assertEquals("1234", Cvc.clean("12345", CardBrand.AMEX))
        assertTrue(Cvc.valid("123", CardBrand.MASTERCARD))
        assertFalse(Cvc.valid("1234", CardBrand.VISA))
        assertTrue(Cvc.valid("1234", CardBrand.AMEX))
        assertFalse(Cvc.valid("12", CardBrand.VISA))
    }

    private val now = YearMonth.of(2026, 11)

    @Test fun expiryInputIsCleanedAndFormatted() {
        assertEquals("04", Expiry.clean("4"))
        assertEquals("1", Expiry.clean("1"))
        assertEquals("0428", Expiry.clean("04/28"))
        assertEquals("1229", Expiry.clean("12/299"))
        assertEquals("04/28", Expiry.format("0428"))
        assertEquals("04/2", Expiry.format("042"))
        assertEquals("04", Expiry.format("04"))
        assertEquals("04/28", Expiry.display(4, 2028))
    }

    @Test fun expiryIsGoodThroughItsMonth() {
        assertEquals(ExpiryCheck.Ok(11, 2026), Expiry.check("1126", now))
        assertEquals(ExpiryCheck.Ok(4, 2028), Expiry.check("0428", now))
        assertEquals(ExpiryCheck.Expired, Expiry.check("1026", now))
        assertEquals(ExpiryCheck.Expired, Expiry.check("1225", now))
        assertEquals(ExpiryCheck.BadMonth, Expiry.check("1328", now))
        assertEquals(ExpiryCheck.BadMonth, Expiry.check("0028", now))
        assertEquals(ExpiryCheck.Incomplete, Expiry.check("042", now))
        assertEquals(ExpiryCheck.Ok(1, 2051), Expiry.check("0151", now))
        assertEquals(ExpiryCheck.TooFar, Expiry.check("0152", now))
        assertTrue(Expiry.expired(10, 2026, now))
        assertFalse(Expiry.expired(11, 2026, now))
    }

    @Test fun maskKeepsOnlyTheLastFour() {
        assertEquals("4242", CardMask.last4("••4242"))
        assertEquals("", CardMask.last4(null))
    }
}
