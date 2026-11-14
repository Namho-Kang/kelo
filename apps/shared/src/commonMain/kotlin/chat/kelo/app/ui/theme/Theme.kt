package chat.kelo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import chat.kelo.app.ui.text.textLocale

/**
 * Kelo's palette, light and dark. Every text and icon colour comes from here, never a fixed black or white: each pair the app draws
 * (text on its background) reaches WCAG AA, 4.5:1 for body text and 3:1 for large titles and icons (ThemeContrastTest checks them).
 *
 * - [accent]: links, section headings, selected things and filled buttons; [onAccent] is the text and icons on an accent fill.
 * - [danger] / [onDanger], [success], [warn] (waiting for the user): as text on [bg] / [card] and as fills.
 * - The *Soft / *Ink pairs: small badges and bubbles (a tinted background with its own readable text).
 * - [media] / [onMedia]: the camera and full-screen pictures, dark in both themes.
 */
@Immutable
data class KeloColors(
    val bg: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val bubble: Color,
    val mine: Color,
    val danger: Color,
    val success: Color,
    val dark: Boolean,
    val onAccent: Color,
    val onDanger: Color,
    val onSuccess: Color,
    val warn: Color,
    val onWarn: Color,
    val dangerSoft: Color,
    val dangerInk: Color,
    val successSoft: Color,
    val successInk: Color,
    val infoSoft: Color,
    val infoInk: Color,
    val neutralSoft: Color,
    val neutralInk: Color,
    /** Text and icons that cannot be used right now (a disabled button), still readable at 3:1 */
    val disabledInk: Color,
    val disabledFill: Color,
    val media: Color = Color(0xFF000000),
    val onMedia: Color = Color(0xFFFFFFFF),
    /** The floating header's soft fade and the avatar's shadow */
    val scrim: Color,
)

val LightKeloColors = KeloColors(
    bg = Color(0xFFFBFBFA), card = Color(0xFFFFFFFF), ink = Color(0xFF1A1A1A), muted = Color(0xFF6B6B71),
    line = Color(0xFFE4E4E7), accent = Color(0xFF4F52E0), bubble = Color(0xFFECEEF0), mine = Color(0xFFDBE4FF),
    danger = Color(0xFFC62828), success = Color(0xFF237A47), dark = false,
    onAccent = Color(0xFFFFFFFF), onDanger = Color(0xFFFFFFFF), onSuccess = Color(0xFFFFFFFF), warn = Color(0xFF8F5A00), onWarn = Color(0xFFFFFFFF),
    dangerSoft = Color(0xFFFDE8E8), dangerInk = Color(0xFFA51111),
    successSoft = Color(0xFFDFF5E6), successInk = Color(0xFF1B6B3C),
    infoSoft = Color(0xFFE3ECFF), infoInk = Color(0xFF3B4CCF),
    neutralSoft = Color(0xFFEEF2F6), neutralInk = Color(0xFF3C4A5C),
    disabledInk = Color(0xFF7C7C82), disabledFill = Color(0xFFEAEAEC),
    scrim = Color(0xFF000000),
)

val DarkKeloColors = KeloColors(
    bg = Color(0xFF141416), card = Color(0xFF1F1F23), ink = Color(0xFFF2F2F3), muted = Color(0xFF9A9AA1),
    line = Color(0xFF34343A), accent = Color(0xFF8C8FF7), bubble = Color(0xFF26262B), mine = Color(0xFF343A6B),
    danger = Color(0xFFFF6B6B), success = Color(0xFF4CC17F), dark = true,
    onAccent = Color(0xFF111114), onDanger = Color(0xFF111114), onSuccess = Color(0xFF111114), warn = Color(0xFFE0A84A), onWarn = Color(0xFF111114),
    dangerSoft = Color(0xFF3B1D1D), dangerInk = Color(0xFFF5A5A5),
    successSoft = Color(0xFF1D3328), successInk = Color(0xFFA9DCBF),
    infoSoft = Color(0xFF263056), infoInk = Color(0xFFC7D0FF),
    neutralSoft = Color(0xFF262B33), neutralInk = Color(0xFFC3CAD4),
    disabledInk = Color(0xFF7A7A82), disabledFill = Color(0xFF2A2A2F),
    scrim = Color(0xFF000000),
)

/** The letters on a service's or a site's coloured tile (the tiles are dark enough in both themes). */
val TileInk = Color(0xFFFFFFFF)

val LocalKeloColors = staticCompositionLocalOf { LightKeloColors }

object Kelo {
    val colors: KeloColors
        @Composable @ReadOnlyComposable get() = LocalKeloColors.current
}

/** Type sizes: page titles 28sp, first-run titles 26sp, body 16sp, small 13sp, labels 11sp. System sans font (Noto Sans KR on Android). */
/**
 * Lines break between words, never inside one ("로그인" stays whole): phrase-based word breaking where the platform's text engine has
 * it (Android 13 on; older phones break as before), whole words elsewhere. Per platform: ThemePlatform.{android,desktop}.kt.
 */
internal expect val PhraseLines: LineBreak

/** Chinese and Thai have no spaces: the dictionary finds the word ends (where the text engine has one). */
internal expect fun dictionaryLines(): LineBreak

/** What else a platform's text engine needs on every style (the desktop sets each paragraph's direction from its own script). */
internal expect fun TextStyle.platformText(): TextStyle

@Suppress("FunctionName")
private fun WordStyle(fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: FontWeight? = null, lineHeight: androidx.compose.ui.unit.TextUnit) =
    TextStyle(fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, lineBreak = PhraseLines)

private val KeloTypography = Typography(
    displaySmall = WordStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),   // h2: page titles
    headlineMedium = WordStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp), // h1: first-run titles
    titleLarge = WordStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),          // top bars
    titleMedium = WordStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = WordStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), // .section-head, .agent-name
    bodyLarge = WordStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = WordStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = WordStyle(fontSize = 13.sp, lineHeight = 18.sp),                                    // .small
    labelLarge = WordStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), // buttons
    labelSmall = WordStyle(fontSize = 11.sp, lineHeight = 14.sp),                                   // nav labels
)

/** How lines break in a language: whole phrases (ko, ja), the dictionary (zh, th), whole words with hyphens (long-word languages). */
internal enum class LineRule { PHRASE, DICTIONARY, HYPHENATE, PLAIN }

/** The line rule for a language tag ("de-AT" -> HYPHENATE). */
internal fun lineRuleFor(tag: String?): LineRule = when (tag.orEmpty().lowercase().substringBefore('-').substringBefore('_')) {
    // Korean and Japanese: never split a word (or phrase) across lines (Android 13+ breaks between phrases)
    "ko", "ja" -> LineRule.PHRASE
    // Chinese and Thai have no spaces: ICU's dictionary finds the word ends; strict rules keep closing punctuation off a line start
    "zh", "th" -> LineRule.DICTIONARY
    // long compound words (German, Dutch, Russian…) hyphenate instead of overflowing a narrow button or being clipped
    "de", "nl", "ru", "uk", "pl", "fr", "es", "it", "pt", "tr", "id", "vi", "en" -> LineRule.HYPHENATE
    else -> LineRule.PLAIN   // Arabic, Hindi and the rest: whole words, no hyphenation
}

/**
 * Every style in the app's language (not the phone's): line breaking follows the text's locale (see [lineRuleFor]), and Korean
 * keeps its words whole only when the text says it is Korean.
 */
private fun Typography.inLocale(tag: String?): Typography {
    if (tag.isNullOrBlank()) return this
    val l = androidx.compose.ui.text.intl.LocaleList(tag)
    val rule = lineRuleFor(tag)
    val lineBreak = when (rule) {
        LineRule.PHRASE -> PhraseLines
        LineRule.DICTIONARY -> dictionaryLines()
        LineRule.HYPHENATE, LineRule.PLAIN -> LineBreak.Paragraph
    }
    val hyphens = if (rule == LineRule.HYPHENATE) androidx.compose.ui.text.style.Hyphens.Auto else androidx.compose.ui.text.style.Hyphens.None
    fun TextStyle.l() = copy(localeList = l, lineBreak = lineBreak, hyphens = hyphens).platformText()
    return copy(
        displayLarge = displayLarge.l(), displayMedium = displayMedium.l(), displaySmall = displaySmall.l(),
        headlineLarge = headlineLarge.l(), headlineMedium = headlineMedium.l(), headlineSmall = headlineSmall.l(),
        titleLarge = titleLarge.l(), titleMedium = titleMedium.l(), titleSmall = titleSmall.l(),
        bodyLarge = bodyLarge.l(), bodyMedium = bodyMedium.l(), bodySmall = bodySmall.l(),
        labelLarge = labelLarge.l(), labelMedium = labelMedium.l(), labelSmall = labelSmall.l(),
    )
}

@Composable
fun KeloTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkKeloColors else LightKeloColors
    val tag = textLocale().toLanguageTag()   // the language the app is shown in (each app root provides LocalTextLocale)
    val typography = androidx.compose.runtime.remember(tag) { KeloTypography.inLocale(tag) }
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.ink, onPrimary = c.bg, secondary = c.accent, onSecondary = c.onAccent,
            background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
            surfaceVariant = c.bubble, onSurfaceVariant = c.muted, surfaceContainer = c.card,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.card, surfaceContainerLow = c.card,
            outline = c.line, outlineVariant = c.line, error = c.danger, onError = c.onDanger,
        )
    } else {
        lightColorScheme(
            primary = c.ink, onPrimary = c.bg, secondary = c.accent, onSecondary = c.onAccent,
            background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
            surfaceVariant = c.bubble, onSurfaceVariant = c.muted, surfaceContainer = c.card,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.card, surfaceContainerLow = c.card,
            outline = c.line, outlineVariant = c.line, error = c.danger, onError = c.onDanger,
        )
    }
    CompositionLocalProvider(LocalKeloColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // text that names no colour (page titles, section heads) takes the theme's ink, never Material's default black
            CompositionLocalProvider(LocalContentColor provides c.ink, content = content)
        }
    }
}
