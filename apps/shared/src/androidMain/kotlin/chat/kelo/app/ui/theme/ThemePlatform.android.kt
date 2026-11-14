package chat.kelo.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak

/** Lines break between words, never inside one ("로그인" stays whole): phrase-based word breaking, which Android applies to CJK text from Android 13 on (older phones break as before). */
internal actual val PhraseLines: LineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

/** Chinese and Thai: ICU's dictionary finds the word ends; strict rules keep closing punctuation off a line start. */
internal actual fun dictionaryLines(): LineBreak = LineBreak.Paragraph.copy(strictness = LineBreak.Strictness.Strict, wordBreak = LineBreak.WordBreak.Default)

internal actual fun TextStyle.platformText(): TextStyle = this
