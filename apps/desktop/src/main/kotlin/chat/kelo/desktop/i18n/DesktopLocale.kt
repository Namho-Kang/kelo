package chat.kelo.desktop.i18n

import androidx.compose.runtime.Composable
import chat.kelo.app.core.Languages
import chat.kelo.app.ui.text.textLocale
import java.util.Locale

/**
 * The Java locale of one of the app's languages (chat.kelo.app.core.Languages codes; Languages.suggested already maps a system
 * Chinese to zh-Hans or zh-Hant by script and region). The text comes from Compose resources, which read Locale.getDefault(); the
 * app root sets it to the language the app is shown in (KeloFrame), and formats numbers and dates with it.
 * zh-Hans -> zh-CN, zh-Hant -> zh-TW (the resource folders values-zh / values-zh-rTW ...), pt-BR -> pt-BR, the rest by tag.
 */
object DesktopLocale {
    /** The system's own locale, taken before the app changes the default: "follow the system" reads this, never the app's own choice. */
    val system: Locale = Locale.getDefault()

    /**
     * Makes [locale] the language of the text (Compose resources read Locale.getDefault()). Code that formats with the implicit
     * default (String.format, localized dates) keeps the system's own format locale, as before.
     */
    fun apply(locale: Locale) {
        Locale.setDefault(locale)
        Locale.setDefault(Locale.Category.FORMAT, system)
    }

    fun of(code: String): Locale = when (code) {
        "zh-Hans" -> Locale.forLanguageTag("zh-CN")
        "zh-Hant" -> Locale.forLanguageTag("zh-TW")
        else -> Locale.forLanguageTag(code)
    }
}

/** The code (chat.kelo.app.core.Languages) of the language the app is shown in, for what is sent to the server in that language. */
@Composable
fun appLanguageCode(): String = Languages.match(textLocale().toLanguageTag()) ?: "en"
