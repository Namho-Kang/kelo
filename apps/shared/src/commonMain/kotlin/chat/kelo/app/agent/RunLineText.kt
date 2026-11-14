package chat.kelo.app.agent

import androidx.compose.runtime.Composable
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.plural
import chat.kelo.app.ui.text.string
import org.jetbrains.compose.resources.StringResource

/**
 * The words of a look-back run's status line (see [RunLine]), the same on Android and the desktop: the line's sentence with [whenText]
 * ("last night", "today", "Dec 3") and, for a failed run, its reason, then " · N new rules suggested" / " · no new rules" when
 * [RunLine.newRules] says so. The caller capitalises the first letter and draws it.
 */
@Composable
fun RunLine.text(whenText: String): String {
    var text = when {
        why != null -> string(line.res(), whenText, string(why.res()))
        withWhen -> string(line.res(), whenText)
        else -> string(line.res())
    }
    newRules?.let { n ->
        text += " · " + if (n > 0) plural(Res.plurals.lookback_line_new, n, n) else string(Res.string.lookback_line_none)
    }
    return text
}

private fun LookbackLine.res(): StringResource = when (this) {
    LookbackLine.DONE -> Res.string.lookback_line_done
    LookbackLine.NO_CHAT -> Res.string.lookback_line_no_chat
    LookbackLine.OFF -> Res.string.lookback_line_off
    LookbackLine.BUSY -> Res.string.lookback_line_busy
    LookbackLine.SKIPPED -> Res.string.lookback_line_skipped
    LookbackLine.FAILED_WHY -> Res.string.lookback_line_failed_why
    LookbackLine.FAILED -> Res.string.lookback_line_failed
    LookbackLine.NO_MODEL -> Res.string.lookback_line_no_model
}

private fun LookbackFail.res(): StringResource = when (this) {
    LookbackFail.MODEL -> Res.string.lookback_fail_model
    LookbackFail.ANSWER -> Res.string.lookback_fail_answer
}
