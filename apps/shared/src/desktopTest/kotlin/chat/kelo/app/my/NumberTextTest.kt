package chat.kelo.app.my

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * How 마이 → 사용량 / Kelo 서버 자원 write numbers. The desktop app runs this same test; the iPhone app's MyFormatNumbersTests has the
 * same cases, so all three round and write the same way.
 */
class NumberTextTest {
    private val en = Locale.US
    private val de = Locale.GERMANY
    private val ar = Locale.forLanguageTag("ar")

    @Test fun oneDecimalRoundsHalfUpOnTheShortestForm() {
        assertEquals("1.3", NumberText.oneDecimal(1.25, en))
        assertEquals("2.3", NumberText.oneDecimal(2.25, en))   // half even would say 2.2
        assertEquals("0.2", NumberText.oneDecimal(0.15, en))   // 0.1499999… in binary; its shortest form is 0.15
        assertEquals("1.0", NumberText.oneDecimal(1.0, en))
        assertEquals("1,3", NumberText.oneDecimal(1.25, de))
        assertEquals("١٫٣", NumberText.oneDecimal(1.25, ar))    // the language's own digits and decimal sign
    }

    @Test fun storageSizes() {
        val mib = 1024L * 1024
        val gib = 1024L * mib
        assertEquals("1.3" to NumberText.GB, NumberText.storage(gib + gib / 4, en))
        assertEquals("2.3" to NumberText.GB, NumberText.storage(2 * gib + gib / 4, en))
        assertEquals("1,3" to NumberText.GB, NumberText.storage(gib + gib / 4, de))
        assertEquals("5.3" to NumberText.MB, NumberText.storage(5 * mib + mib / 4, en))
        assertEquals("11" to NumberText.MB, NumberText.storage(10 * mib + mib / 2, en))   // whole from 10 MB, half up
        assertEquals("11" to NumberText.MB, NumberText.storage(10 * mib + mib / 2, ar))   // whole sizes keep plain digits
        assertEquals("2" to NumberText.KB, NumberText.storage(1536, en))
        assertEquals("0" to NumberText.KB, NumberText.storage(0, en))
    }

    @Test fun fileSizes() {
        val mib = 1024L * 1024
        assertEquals("1.3" to NumberText.MB, NumberText.file(mib + mib / 4, en))
        assertEquals("2" to NumberText.KB, NumberText.file(1536, en))
        assertEquals("1" to NumberText.KB, NumberText.file(100, en))   // never "0KB"
    }

    @Test fun cpusAndWholeNumbers() {
        assertEquals("2", NumberText.cpu(2.0))
        assertEquals("1.5", NumberText.cpu(1.5))
        assertEquals("0.25", NumberText.cpu(0.25))
        assertEquals("1,234,567", NumberText.whole(1234567, en))
        assertEquals("1.234.567", NumberText.whole(1234567, de))
    }

    @Test fun moneyIsTwoDecimalsHalfEvenInTheAppsLanguage() {
        assertEquals("$1,234.50", NumberText.money(1234.5, "USD", en))
        assertEquals("$0.12", NumberText.money(0.125, "USD", en))
        assertEquals("$0.38", NumberText.money(0.375, "USD", en))
        assertEquals("-$0.19", NumberText.money(-0.19, "USD", en))
        assertEquals("$0.00", NumberText.money(0.0, "", en))                      // no currency: dollars
        val euros = NumberText.money(1234.5, "EUR", de)
        assertTrue(euros, euros.startsWith("1.234,50") && euros.endsWith("€"))   // the app's language, not the computer's
    }
}
