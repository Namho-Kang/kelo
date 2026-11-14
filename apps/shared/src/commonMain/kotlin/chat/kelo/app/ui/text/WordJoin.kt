package chat.kelo.app.ui.text

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Keeping Korean words whole when a text engine breaks lines between any two syllables (Compose Desktop's Skia). */
object WordJoin {
    const val JOINER = '\u2060'

    private fun hangul(c: Char): Boolean = c in '가'..'힣'

    /** U+2060 between every two neighbouring Hangul syllables; spans, paragraph styles, annotations and links move with their text. */
    fun join(src: AnnotatedString): AnnotatedString {
        val s = src.text
        if ((0 until s.length - 1).none { hangul(s[it]) && hangul(s[it + 1]) }) return src
        val map = IntArray(s.length + 1)
        val out = StringBuilder(s.length + s.length / 2)
        for (i in s.indices) {
            map[i] = out.length
            out.append(s[i])
            if (i + 1 < s.length && hangul(s[i]) && hangul(s[i + 1])) out.append(JOINER)
        }
        map[s.length] = out.length
        return buildAnnotatedString {
            append(out.toString())
            src.spanStyles.forEach { addStyle(it.item, map[it.start], map[it.end]) }
            src.paragraphStyles.forEach { addStyle(it.item, map[it.start], map[it.end]) }
            src.getStringAnnotations(0, s.length).forEach { addStringAnnotation(it.tag, it.item, map[it.start], map[it.end]) }
            src.getLinkAnnotations(0, s.length).forEach { r ->
                when (val a = r.item) {
                    is LinkAnnotation.Url -> addLink(a, map[r.start], map[r.end])
                    is LinkAnnotation.Clickable -> addLink(a, map[r.start], map[r.end])
                }
            }
        }
    }

    fun strip(s: String): String = if (s.indexOf(JOINER) < 0) s else s.replace(JOINER.toString(), "")
}

/** What a platform does to a text before it is drawn: nothing by default; the desktop root provides [WordJoin.join]. */
val LocalWordJoiner = staticCompositionLocalOf<(AnnotatedString) -> AnnotatedString> { { it } }

/**
 * A text that keeps its words whole where the platform needs it (see [LocalWordJoiner]). Tests and screen readers get the original
 * text (the semantics), never the drawn one.
 */
@Composable
fun JoinedText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle, color: Color) {
    val join = LocalWordJoiner.current
    val shown = remember(text, join) { join(text) }
    Text(
        shown, style = style, color = color,
        modifier = if (shown === text) modifier else modifier.clearAndSetSemantics { this.text = text },
    )
}

/** The clipboard with the joiners taken out of anything copied (a selection of a joined text). */
class JoinerFreeClipboard(private val inner: ClipboardManager) : ClipboardManager by inner {
    override fun setText(annotatedString: AnnotatedString) {
        inner.setText(AnnotatedString(WordJoin.strip(annotatedString.text)))
    }
}
