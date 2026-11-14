@file:OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")   // ResourceEnvironment(...) in loadString (see its note)

package chat.kelo.app.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/*
 * The apps' text for Compose: the strings of apps/shared/strings, converted at build time into Compose resources (chat.kelo.app.res.Res).
 *
 * Compose's own stringResource(res, args) only knows "%1$s" and "%1$d"; the app's text uses Android's and Java's format everywhere
 * (%d, %%, %.1f ...). So these functions read the raw text (no args handed to Compose) and format it themselves with
 * String.format, as Android's getString does. Text without args is returned as it is, also as Android does.
 */

/**
 * The locale numbers and dates in the text are formatted with, and the language the theme sets line breaking for; each app root
 * provides the language the app is shown in. Without one [textLocale] reads the default locale each time: a staticCompositionLocalOf
 * default is computed once and would keep the language of the first composition after the app's language changed.
 */
val LocalTextLocale = staticCompositionLocalOf<Locale?> { null }

@Composable
@ReadOnlyComposable
fun textLocale(): Locale = LocalTextLocale.current ?: Locale.getDefault()

@Composable
fun string(res: StringResource, vararg args: Any?): String = TextFormat.format(textLocale(), stringResource(res), args)

@Composable
fun plural(res: PluralStringResource, count: Int, vararg args: Any?): String =
    TextFormat.format(textLocale(), pluralStringResource(res, count), args)

@Composable
fun stringArray(res: StringArrayResource): List<String> = stringArrayResource(res)

/**
 * For code that runs outside composition (a coroutine, a notification): the text in the language of [Locale.getDefault].
 * The environment is built from the default locale alone (the text has no theme or density variants), so it also works where
 * Compose's own system environment cannot be read (no screen: headless tests).
 */
suspend fun loadString(res: StringResource, vararg args: Any?): String =
    TextFormat.format(Locale.getDefault(), getString(environment(), res), args)

suspend fun loadPlural(res: PluralStringResource, count: Int, vararg args: Any?): String =
    TextFormat.format(Locale.getDefault(), getPluralString(environment(), res, count), args)

suspend fun loadArray(res: StringArrayResource): List<String> = getStringArray(environment(), res)

private fun environment(): ResourceEnvironment {
    val l = Locale.getDefault()
    return ResourceEnvironment(LanguageQualifier(l.language), RegionQualifier(l.country), ThemeQualifier.LIGHT, DensityQualifier.MDPI)
}

/** Android's and Java's formatting ("%1$s", "%d", "%%"); text that does not fit its args is shown unformatted, as the desktop did. */
object TextFormat {
    fun format(locale: Locale, text: String, args: Array<out Any?>): String =
        if (args.isEmpty()) text else try { String.format(locale, text, *args) } catch (e: java.util.IllegalFormatException) { text }
}
