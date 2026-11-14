package chat.kelo.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import chat.kelo.app.chat.TimeLabels
import java.time.ZoneId
import java.util.Locale

/*
 * Small pure functions whose body differs per platform (the shared screens call these; each app's source set holds the body).
 */

/** The picture in [bytes] (JPEG, PNG, WebP ...) as an image to draw, or null when it cannot be read. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * The date or time pattern the language writes for [skeleton] ("MMMd", "yMMMdEEE", "jm"), as `DateTimeFormatter.ofPattern` takes it:
 * the phone asks the system, the desktop builds it from the locale's own pattern.
 */
expect fun bestDatePattern(skeleton: String, locale: Locale): String

/** What the chat's time lines need from the platform's date formatting ([TimeLabels]): the patterns of the locale and a pattern rendered. */
interface PatternFormatter {
    val patterns: TimeLabels.Patterns

    /** [pattern] (one of [patterns]) rendered for the moment [atMs]. */
    fun format(pattern: String, atMs: Long): String
}

/** The formatter for [locale] and [zone]: the phone uses the system's best patterns and ICU, the desktop the JDK's locale patterns. */
expect fun patternFormatter(locale: Locale, zone: ZoneId): PatternFormatter

/** A saved E.164 number as people write it in that country ("010-1234-5678"), else as it is: the phone asks the system's formatter, the desktop shows it as saved. */
expect fun phoneShown(phone: String, country: String): String

/** The height of the window the UI is drawn in (the phone: its root view, else the display; the desktop: its window). */
@Composable
expect fun windowHeight(): Dp

/**
 * A message's own menu and selection where the pointer has them: the phone shows [content] as it is (its long-press actions sheet does
 * the work); the desktop makes a right-click menu of [items] (label to action) and lets the text be selected.
 */
@Composable
expect fun MessageContextArea(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit)
