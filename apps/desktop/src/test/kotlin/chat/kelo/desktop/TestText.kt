package chat.kelo.desktop

import chat.kelo.app.ui.text.loadArray
import chat.kelo.app.ui.text.loadPlural
import chat.kelo.app.ui.text.loadString
import chat.kelo.desktop.i18n.DesktopLocale
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

/**
 * The text of one language for tests that look for what a screen shows: Words.of("ko").get(Res.string.x, args). It reads the same
 * Compose resources the app does (apps/shared/strings), with the default locale of that language for the moment of the call.
 */
class Words private constructor(private val locale: Locale) {
    private fun <T> inLanguage(block: suspend () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try { return runBlocking { block() } } finally { Locale.setDefault(saved) }
    }

    fun get(res: StringResource, vararg args: Any?): String = inLanguage { loadString(res, *args) }
    fun plural(res: PluralStringResource, count: Int, vararg args: Any?): String = inLanguage { loadPlural(res, count, *args) }
    fun array(res: StringArrayResource): List<String> = inLanguage { loadArray(res) }

    companion object {
        fun of(code: String): Words = Words(DesktopLocale.of(code))
    }
}

/** English text, for a test that reads a line the way the default language shows it. */
fun en(res: StringResource, vararg args: Any?): String = Words.of("en").get(res, *args)
