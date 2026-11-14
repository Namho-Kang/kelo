package chat.kelo.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.toComposeImageBitmap
import chat.kelo.app.chat.TimeLabels
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun bestDatePattern(skeleton: String, locale: Locale): String = SkeletonPattern.of(locale, skeleton)

/*
 * The desktop has no system "best pattern" call: the shapes Android gives the CJK languages are written out, and other languages
 * take the locale's own medium date pattern with the year and weekday added or dropped as the skeleton says.
 */
internal object SkeletonPattern {
    private val CJK = setOf("ko", "ja", "zh")

    fun of(locale: Locale, skeleton: String): String {
        // the clock: whether the locale writes 24 hours is in its short time pattern
        if (skeleton == "jm") return DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, java.time.chrono.IsoChronology.INSTANCE, locale)
        if (skeleton == "yMMMM") return when (locale.language) { "ko" -> "y년 M월"; "ja", "zh" -> "y年M月"; else -> "LLLL y" }
        // the shapes Android gives these languages ("11월 8일 (일)", "11月8日(日)")
        val year = 'y' in skeleton
        val day = 'E' in skeleton
        when (locale.language) {
            "ko" -> return (if (year) "y년 " else "") + "MMM d일" + (if (day) " (EEE)" else "")
            "ja" -> return (if (year) "y年" else "") + "MMMd日" + (if (day) "(EEE)" else "")
            "zh" -> return (if (year) "y年" else "") + "MMMd日" + (if (day) "EEE" else "")
        }
        var p = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM, null, java.time.chrono.IsoChronology.INSTANCE, locale)
        if ('y' !in skeleton) p = withoutYear(p)
        if ('E' in skeleton) p = if (locale.language in CJK) "$p (EEE)" else "EEE, $p"
        return p
    }

    /** "y. M. d." -> "M. d.", "MMM d, y" -> "MMM d", "dd.MM.y" -> "dd.MM", "y년 M월 d일" -> "M월 d일" */
    fun withoutYear(p: String): String {
        val m = Regex("y+(년|年|\\s?г\\.)?").find(p) ?: return p
        var start = m.range.first
        var end = m.range.last + 1
        if (p.substring(0, start).isBlank()) { while (end < p.length && p[end] in " ,./-") end++ }
        else { while (start > 0 && p[start - 1] in " ,./-") start-- }
        return p.removeRange(start, end).trim().ifEmpty { "MMM d" }
    }
}

actual fun patternFormatter(locale: Locale, zone: ZoneId): PatternFormatter = object : PatternFormatter {
    override val patterns = DesktopTimePatterns.of(locale)
    private val formats = HashMap<String, DateTimeFormatter>()

    override fun format(pattern: String, atMs: Long): String =
        formats.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern, locale).withZone(zone) }.format(Instant.ofEpochMilli(atMs))
}

/**
 * The desktop's [TimeLabels.Patterns], from the JDK's locale patterns (the JDK has no skeletons like Android's
 * getBestDateTimePattern). The medium date when it names the month in words ("MMM d, y"), else the long one (Korean "y년 M월 d일",
 * Japanese "y年M月d日": their medium dates are numbers only); the year-less pattern is the same with the year taken out.
 */
object DesktopTimePatterns {
    fun of(locale: Locale): TimeLabels.Patterns {
        fun pattern(date: FormatStyle?, time: FormatStyle?) =
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time, IsoChronology.INSTANCE, locale)
        val medium = pattern(FormatStyle.MEDIUM, FormatStyle.SHORT)
        val full = if (Regex("""M{3,}""").containsMatchIn(unquoted(medium))) medium else pattern(FormatStyle.LONG, FormatStyle.SHORT)
        return TimeLabels.Patterns(time = pattern(null, FormatStyle.SHORT), monthDayTime = withoutYear(full), fullDateTime = full)
    }

    /** [p] without its year field and the separator that goes with it: "MMM d, y, h:mm a" -> "MMM d, h:mm a", "y년 M월 d일 a h:mm" -> "M월 d일 a h:mm". */
    fun withoutYear(p: String): String {
        val rules = listOf(
            Regex("""^y+[^A-Za-z']*"""),                    // a year first (ko, ja, zh): with what follows it ("년 ", "年")
            Regex("""\s*'de'\s*y+"""),                      // es / pt: "d 'de' MMM 'de' y"
            Regex("""[^A-Za-z']*y+(\s*'[^']*'\.?)?"""),     // elsewhere: with the separator before it, and a word after it (ru "'г'.")
        )
        for (r in rules) {
            val m = r.find(p) ?: continue
            if (r === rules[0] || inPattern(p, m.range.first)) return (p.removeRange(m.range)).trim()
        }
        return p
    }

    private fun unquoted(p: String) = p.replace(Regex("""'[^']*'"""), "")
    /** Whether index [i] of [p] is outside a quoted literal. */
    private fun inPattern(p: String, i: Int) = p.substring(0, i).count { it == '\'' } % 2 == 0
}

actual fun phoneShown(phone: String, country: String): String = phone

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun windowHeight(): Dp {
    val density = LocalDensity.current
    return with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
actual fun MessageContextArea(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    androidx.compose.foundation.ContextMenuArea(items = { items.map { (label, action) -> androidx.compose.foundation.ContextMenuItem(label, action) } }) {
        androidx.compose.foundation.text.selection.SelectionContainer { content() }
    }
}
