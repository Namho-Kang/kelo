package chat.kelo.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection

/** The desktop text engine has no phrase breaking: whole words. */
internal actual val PhraseLines: LineBreak = LineBreak.Paragraph

/** ... and no dictionary breaking either. */
internal actual fun dictionaryLines(): LineBreak = LineBreak.Paragraph

/** Each paragraph runs in its own script's direction (a Korean reply inside the Arabic app still reads left to right). */
internal actual fun TextStyle.platformText(): TextStyle = copy(textDirection = TextDirection.Content)
