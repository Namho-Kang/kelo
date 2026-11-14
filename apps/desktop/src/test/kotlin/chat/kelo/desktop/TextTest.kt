package chat.kelo.desktop

import chat.kelo.app.core.Languages
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.TextFormat
import chat.kelo.desktop.i18n.DesktopLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The desktop's text is apps/shared/strings read through Compose resources (every language has every line: apps/android/tools/strings_check.py).
 * What is checked here is what the desktop adds: the language -> locale mapping, Android-style formatting, the English fallback.
 */
class TextTest {
    @Test fun everyAppLanguageMapsToALocaleAndBack() {
        Languages.ALL.forEach { l ->
            val locale = DesktopLocale.of(l.code)
            // the code sent to the server for a language is the one the language has
            assertEquals(l.code, Languages.match(locale.toLanguageTag()))
        }
        assertEquals(Locale.forLanguageTag("zh-CN"), DesktopLocale.of("zh-Hans"))
        assertEquals(Locale.forLanguageTag("zh-TW"), DesktopLocale.of("zh-Hant"))
    }

    @Test fun formatsLikeAndroidAndFallsBack() {
        val ko = Words.of("ko")
        assertEquals("Kelo 데스크톱 1.0.0", ko.get(Res.string.desktop_version, "1.0.0"))
        assertEquals("Kelo", ko.get(Res.string.app_name))   // untranslatable: from English
        assertTrue(en(Res.string.connect_confirm_body).contains("server's"))   // aapt escapes undone
    }

    @Test fun pluralsPickTheirForm() {
        val en = Words.of("en")
        val one = en.plural(Res.plurals.work_badge_waiting, 1, 1)
        val many = en.plural(Res.plurals.work_badge_waiting, 5, 5)
        assertTrue(one.contains("1") && many.contains("5") && one != many.replace("5", "1"))
    }

    @Test fun textThatDoesNotFitItsArgsIsShownAsItIs() {
        assertEquals("%1\$s and %2\$s", TextFormat.format(Locale.ENGLISH, "%1\$s and %2\$s", arrayOf("a")))
        assertEquals("100%% done", TextFormat.format(Locale.ENGLISH, "100%% done", emptyArray()))   // no args: untouched, as Android
        assertEquals("100% done", TextFormat.format(Locale.ENGLISH, "100%% done", arrayOf<Any?>("x")))
    }
}
