package chat.kelo.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Country names in the app's language (moved from the desktop's ShippingTest when the desktop took the shared shipping pages). */
class CountryNameTest {
    @Test fun inTheAppsLanguage() {
        assertEquals("South Korea", countryName("KR", "South Korea", Locale.ENGLISH))
        assertEquals("대한민국", countryName("KR", "South Korea", Locale.KOREAN))
        assertEquals("미국", countryName("US", "United States", Locale.KOREAN))
        assertEquals("日本", countryName("JP", "Japan", Locale.JAPANESE))
        // a code the JVM has no name for: the server's English name
        assertEquals("Somewhere", countryName("Q1", "Somewhere", Locale.KOREAN))
    }
}
