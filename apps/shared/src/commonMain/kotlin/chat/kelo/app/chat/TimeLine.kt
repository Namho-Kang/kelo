package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.ui.platform.patternFormatter
import chat.kelo.app.ui.theme.Kelo
import java.time.ZoneId
import java.util.Locale

/** Turns a message's time into its time line's words ([TimeLabels.label]) with the app's language and this device's time zone. */
class TimeLabeler(locale: Locale, private val zone: ZoneId, private val today: String, private val yesterday: String) {
    private val formatter = patternFormatter(locale, zone)

    fun label(atMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        TimeLabels.label(atMs, nowMs, zone, formatter.patterns, today, yesterday, formatter::format)
}

@Composable
fun rememberTimeLabeler(): TimeLabeler {
    val locale = textLocale()
    val today = string(Res.string.chat_time_today)
    val yesterday = string(Res.string.chat_time_yesterday)
    val zone = ZoneId.systemDefault()
    return remember(locale, zone, today, yesterday) { TimeLabeler(locale, zone, today, yesterday) }
}

/** The small, muted, centred time line above a message that starts a new stretch of the chat (not a bubble; wraps in any language). */
@Composable
fun TimeLine(text: String) {
    Text(
        text,
        color = Kelo.colors.muted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .wrapContentWidth()
            .widthIn(max = chat.kelo.app.ui.platform.formFactor(phone = 280.dp, desktop = 320.dp))   // a wider window gives the line more room
            .semantics { heading() },
    )
}
