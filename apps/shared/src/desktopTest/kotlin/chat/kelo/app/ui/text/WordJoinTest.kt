package chat.kelo.app.ui.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WordJoinTest {
    @Test fun `joiners only between Hangul syllables`() {
        assertEquals("가\u2060나 다 a가", WordJoin.join(AnnotatedString("가나 다 a가")).text)
    }

    @Test fun `text without neighbouring syllables is returned as it is`() {
        val t = AnnotatedString("hello 가 world")
        assertSame(t, WordJoin.join(t))
    }

    @Test fun `styles and links follow their words`() {
        val t = buildAnnotatedString {
            append("앞 ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("굵은글") }
            append(" ")
            withLink(LinkAnnotation.Url("https://kelo.chat")) { append("링크") }
        }
        val j = WordJoin.join(t)
        val bold = j.spanStyles.single()
        assertEquals("굵은글", WordJoin.strip(j.text.substring(bold.start, bold.end)))
        val link = j.getLinkAnnotations(0, j.length).single()
        assertEquals("링크", WordJoin.strip(j.text.substring(link.start, link.end)))
        assertEquals(t.text, WordJoin.strip(j.text))
    }

    @Test fun `on the desktop engine lines break between words`() {
        val m = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val text = "내일까지예요 그리고 파일을 지우고 다시 올려 주세요 확인했어요"
        val joined = WordJoin.join(AnnotatedString(text))
        val r = m.measure(joined, TextStyle(fontSize = 16.sp), constraints = Constraints(maxWidth = 120))
        val lines = (0 until r.lineCount).map { WordJoin.strip(joined.text.substring(r.getLineStart(it), r.getLineEnd(it))).trim() }
        val words = text.split(" ").toSet()
        lines.forEach { line -> line.split(" ").filter { it.isNotEmpty() }.forEach { assertTrue(it in words, "\"$it\" is a broken word in $lines") } }
    }

    @Test fun `copied text has no joiners`() = assertEquals("가나다", WordJoin.strip("가\u2060나\u2060다"))
}
