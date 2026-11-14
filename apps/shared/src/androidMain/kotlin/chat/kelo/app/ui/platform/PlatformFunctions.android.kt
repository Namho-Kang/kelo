package chat.kelo.app.ui.platform

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import android.telephony.PhoneNumberUtils
import android.text.format.DateFormat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import chat.kelo.app.chat.TimeLabels
import java.time.ZoneId
import java.util.Date
import java.util.Locale

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

actual fun bestDatePattern(skeleton: String, locale: Locale): String = DateFormat.getBestDateTimePattern(locale, skeleton)

actual fun patternFormatter(locale: Locale, zone: ZoneId): PatternFormatter = object : PatternFormatter {
    override val patterns = TimeLabels.Patterns(
        time = DateFormat.getBestDateTimePattern(locale, "jmm"),
        monthDayTime = DateFormat.getBestDateTimePattern(locale, "MMMdjmm"),
        fullDateTime = DateFormat.getBestDateTimePattern(locale, "yMMMdjmm"),
    )
    private val formats = HashMap<String, SimpleDateFormat>()

    override fun format(pattern: String, atMs: Long): String =
        formats.getOrPut(pattern) { SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone(zone.id) } }.format(Date(atMs))
}

actual fun phoneShown(phone: String, country: String): String {
    if (phone.isBlank()) return ""
    return runCatching { PhoneNumberUtils.formatNumber(phone, country.uppercase()) }.getOrNull()?.takeIf { it.isNotBlank() } ?: phone
}

@Composable
actual fun windowHeight(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    return with(density) { (view.rootView.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels).toDp() }
}

@Composable
actual fun MessageContextArea(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) = content()
