package chat.kelo.app.wallet

import chat.kelo.app.chat.CardCountdown
import chat.kelo.app.chat.BrowserImage
import chat.kelo.app.core.ApiError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WalletRulesTest {
    private val ko = Locale.KOREA

    @Test fun moneyUsesEachCurrencysDecimals() {
        assertEquals(0, Money.decimals("KRW"))
        assertEquals(0, Money.decimals("JPY"))
        assertEquals(2, Money.decimals("USD"))
        assertEquals(2, Money.decimals("eur"))
        assertEquals(2, Money.decimals("ZZZ"))   // unknown: two
        assertEquals("30,000", Money.number(30_000.0, "KRW", ko))
        assertEquals("200,000", Money.number(200_000.0, "KRW", ko))
        assertEquals("3,000", Money.number(3_000.0, "JPY", ko))
        assertEquals("1,235", Money.number(1234.6, "JPY", ko))
        assertEquals("25.00", Money.number(25.0, "USD", ko))
        assertEquals("1,234.50", Money.number(1234.5, "USD", Locale.US))
        assertEquals("1.234,50", Money.number(1234.5, "EUR", Locale.GERMANY))
        assertEquals("0", Money.number(0.0, "KRW", ko))
    }

    @Test fun plainAndParseForTheEditor() {
        assertEquals("30000", Money.plain(30_000.0, "KRW"))
        assertEquals("25", Money.plain(25.0, "USD"))
        assertEquals("12.50", Money.plain(12.5, "USD"))
        assertEquals(1000.0, Money.parse("1,000", "KRW"))
        assertEquals(12.5, Money.parse("12.5", "USD"))
        assertNull(Money.parse("12.5", "KRW"))
        assertNull(Money.parse("12.555", "USD"))
        assertNull(Money.parse("-5", "USD"))
        assertNull(Money.parse("", "USD"))
        assertEquals(0.0, Money.parse("0", "JPY"))
        assertTrue(Money.validCode("USD"))
        assertFalse(Money.validCode("us"))
    }

    private fun row(cur: String, custom: Boolean = false, spent: Double = 0.0, def: Double? = 1.0) =
        WalletLimit(cur, 1.0, 10.0, def, def?.times(10), custom, spent)

    @Test fun limitsListOnlyWhatMatters() {
        val rows = listOf(row("KRW"), row("USD"), row("EUR", custom = true), row("JPY", spent = 500.0), row("XYZ", custom = true, def = null))
        assertEquals(listOf("KRW", "EUR", "JPY", "XYZ"), LimitRules.visible(rows, "KRW").map { it.currency })
        assertEquals(listOf("KRW", "USD", "EUR", "JPY", "XYZ"), LimitRules.visible(rows, "KRW", setOf("USD")).map { it.currency })
        assertEquals(listOf("USD"), LimitRules.addable(rows, LimitRules.visible(rows, "KRW")))
        assertTrue(rows.last().added)
        assertFalse(rows.first().added)
        assertEquals(LimitCheck.OK, LimitRules.check(10.0, 10.0))
        assertEquals(LimitCheck.OK, LimitRules.check(0.0, 0.0))
        assertEquals(LimitCheck.PER_ABOVE_MONTHLY, LimitRules.check(11.0, 10.0))
        assertEquals(LimitCheck.MISSING, LimitRules.check(null, 10.0))
        assertEquals(0.5f, LimitRules.used(50.0, 100.0))
        assertEquals(1f, LimitRules.used(5.0, 0.0))
        assertEquals(1f, LimitRules.used(500.0, 100.0))
        assertEquals("KRW", LimitRules.homeCurrency(ko))
        assertEquals("KRW", LimitRules.homeCurrency(Locale("ko")))      // an app language without a region
        assertEquals("JPY", LimitRules.homeCurrency(Locale("ja")))
        assertNull(LimitRules.homeCurrency(Locale("xx")))
        assertEquals(setOf("KRW", "USD"), LimitRules.homeCurrencies(Locale("ko"), Locale.US))
        assertEquals(listOf("KRW", "USD", "EUR", "JPY", "XYZ"), LimitRules.visible(rows, setOf("KRW", "USD")).map { it.currency })
    }

    @Test fun lockClosesAfterTwoIdleMinutesOrInTheBackground() {
        val lock = WalletLock()
        assertFalse(lock.isOpen(0))
        lock.unlock(1_000)
        assertTrue(lock.isOpen(1_000 + 119_999))
        assertFalse(lock.isOpen(1_000 + 120_000))
        lock.touch(200_000)   // closed: a touch does not reopen it
        assertFalse(lock.isOpen(200_001))

        lock.unlock(0)
        lock.touch(100_000)   // the two minutes start again
        assertTrue(lock.isOpen(219_999))
        assertEquals(1, lock.remaining(219_999))
        assertFalse(lock.isOpen(220_000))

        lock.unlock(0)
        lock.background()
        assertFalse(lock.isOpen(1))
        lock.unlock(5_000)
        assertFalse(lock.isOpen(4_000))   // a clock that went back closes it
    }

    @Test fun cardStatus() {
        val base = WalletCard("c", e2e = true, e2eAlg = CardBlob.ALG, e2eKeyId = "k1", expMonth = 1, expYear = 2030)
        assertEquals(CardStatus.USABLE, CardStatusRules.of(base, "k1"))
        assertEquals(CardStatus.NOT_ON_PHONE, CardStatusRules.of(base, "k2"))
        assertEquals(CardStatus.NOT_ON_PHONE, CardStatusRules.of(base, null))
        assertEquals(CardStatus.AUTO_ONLY, CardStatusRules.of(base.copy(serverCopy = true, autoPay = true), null))
        assertEquals(CardStatus.UNFINISHED, CardStatusRules.of(base.copy(e2e = false), "k1"))
        assertEquals(CardStatus.EXPIRED, CardStatusRules.of(base.copy(expired = true), "k1"))
    }

    @Test fun deliveryOutcomes() {
        assertNull(DeliveryRules.fromStatus(200))
        assertNull(DeliveryRules.fromStatus(409))   // already sent: fine
        assertEquals(CardSendError.NOT_WAITING, DeliveryRules.fromStatus(404))
        assertEquals(CardSendError.MISMATCH, DeliveryRules.fromStatus(422))
        assertEquals(CardSendError.NETWORK, DeliveryRules.fromStatus(ApiError.NO_RESPONSE))
        assertEquals(CardSendError.OTHER, DeliveryRules.fromStatus(500))
        assertEquals(CardSendError.CANCELLED, DeliveryRules.fromAuth(CardAuth.Cancelled))
        assertEquals(CardSendError.NO_LOCK, DeliveryRules.fromAuth(CardAuth.NoLock))
        assertEquals(CardSendError.NOT_ON_PHONE, DeliveryRules.fromAuth(CardAuth.KeyLost))
        assertEquals(CardSendError.OTHER, DeliveryRules.fromAuth(CardAuth.Failed("x")))
    }

    @Test fun countdownAndDataImage() {
        assertEquals("4:32", CardCountdown.text(272))
        assertEquals("0:05", CardCountdown.text(5))
        assertEquals("0:00", CardCountdown.text(-3))
        assertArrayEquals(byteArrayOf(1, 2, 3), BrowserImage.bytes("data:image/jpeg;base64,AQID"))
        assertNull(BrowserImage.bytes("https://example.com/a.jpg"))
        assertNull(BrowserImage.bytes("data:image/jpeg,AQID"))
    }
}
