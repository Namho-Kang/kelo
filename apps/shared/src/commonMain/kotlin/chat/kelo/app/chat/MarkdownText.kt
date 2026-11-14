package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.foundation.background
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.LocalHaptics
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.KeloColors
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** One bubble's Markdown, drawn as Compose text (never as HTML). Links open in the browser. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    // parsed and styled once per text and palette, not on every recomposition or scroll back into view of a kept item
    val blocks = remember(text, c) {
        val styles = MdStyles.of(c)
        Markdown.blocks(text).map { styles.style(it) }
    }
    StyledMarkdown(blocks, modifier)
}

/** Opens only http(s) addresses, in the browser (through the app's own handler); anything else (or no browser) does nothing. */
private class WebOnlyUriHandler(private val system: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        if (!Markdown.isWebUrl(uri)) return
        try {
            system.openUri(uri)
        } catch (_: Exception) {   // no browser to open it (Android: ActivityNotFound; others: IllegalArgument), or refused (SecurityException)
        }
    }
}

/**
 * Styled blocks; with [visible] (characters, fractional) only that much is drawn, the last few characters fading in (a reply being
 * revealed). Blocks wholly revealed are drawn as they are, so only the block at the edge changes from frame to frame.
 */
@Composable
fun StyledMarkdown(blocks: List<Styled>, modifier: Modifier = Modifier, visible: Float? = null) {
    val system = LocalUriHandler.current
    val links = remember(system) { WebOnlyUriHandler(system) }
    CompositionLocalProvider(LocalUriHandler provides links) {
        Blocks(blocks, modifier, visible)
    }
}

@Composable
private fun Blocks(blocks: List<Styled>, modifier: Modifier, visible: Float?) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var left = visible ?: Float.MAX_VALUE
        for (b in blocks) {
            if (left <= 0f) break
            val show = if (left >= b.length) null else left   // null: the whole block
            left -= b.length
            Block(b, show)
        }
    }
}

@Composable
private fun Block(b: Styled, show: Float?) {
    val c = Kelo.colors
    val body = MaterialTheme.typography.bodyLarge
    when (b) {
        is Styled.Para -> JoinedText(b.text.revealed(show, c.ink), style = body, color = c.ink)
        is Styled.Heading -> JoinedText(b.text.revealed(show, c.ink), style = headingStyle(b.level), color = c.ink, modifier = Modifier.padding(top = 2.dp))
        is Styled.Code -> CodeBlock(b, show)
        is Styled.Items -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var left = show ?: Float.MAX_VALUE
            for (e in b.entries) {
                if (left <= 0f) break
                val entryShow = if (left >= e.text.length) null else left
                left -= e.text.length
                Row(Modifier.padding(start = (18 * e.depth).dp)) {
                    Text(
                        if (e.ordered) string(Res.string.step_number, e.number) else string(bulletFor(e.depth)),
                        style = body, color = if (e.ordered) c.ink else c.muted,
                        modifier = Modifier.widthIn(min = 16.dp).padding(end = 6.dp),
                    )
                    JoinedText(e.text.revealed(entryShow, c.ink), style = body, color = c.ink)
                }
            }
        }
        is Styled.Quote -> {
            val bar = c.muted.copy(alpha = 0.45f)
            Box(
                Modifier
                    .drawBehind {
                        val w = 3.dp.toPx()
                        drawRoundRect(bar, size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
                    }
                    .padding(start = 13.dp),
            ) { Blocks(b.blocks, Modifier, show) }
        }
        is Styled.Table -> TableCard(b, show)
        is Styled.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.muted.copy(alpha = 0.35f)))
    }
}

private fun bulletFor(depth: Int): StringResource = when (depth % 3) {
    0 -> Res.string.chat_bullet
    1 -> Res.string.md_bullet_2
    else -> Res.string.md_bullet_3
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    2 -> TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    else -> TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
}

/** A fenced block: a header with its language and a 복사 button (a check for a moment once copied), the code scrolling sideways. */
@Composable
private fun CodeBlock(b: Styled.Code, show: Float?) {
    val c = Kelo.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(c.line.copy(alpha = 0.5f)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                b.lang.ifEmpty { string(Res.string.md_code_plain) },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            CopyButton(b.raw)
        }
        Text(
            b.text.revealed(show, c.ink),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = c.ink,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** 복사: puts [text] on the clipboard; the icon turns into a check (and the label into 복사됨) for a moment. */
@Composable
fun CopyButton(text: String, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHaptics.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    val label = string(if (copied) Res.string.md_copied else Res.string.md_copy)
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(), role = Role.Button) {
                clipboard.setText(AnnotatedString(text))
                haptics.tick()
                copied = true
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (copied) KeloIcons.Check else KeloIcons.Copy, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
    }
}

const val COPIED_MS = 1500L

/**
 * A table: a card that scrolls sideways when it is wider than the bubble. Each column is as wide as its widest cell, between
 * [CELL_MIN] and [CELL_MAX] (longer text wraps inside the cell); the header row is bold on a tinted band.
 */
@Composable
private fun TableCard(t: Styled.Table, show: Float?) {
    val c = Kelo.colors
    val cols = t.header.size
    var left = show ?: Float.MAX_VALUE
    val shown = ArrayList<List<AnnotatedString>>()
    for (row in listOf(t.header) + t.rows) {
        if (left <= 0f) break
        shown += row.map { cell ->
            val cellShow = if (left >= cell.length) null else left
            left -= cell.length
            if (cellShow != null && cellShow <= 0f) AnnotatedString("") else cell.revealed(cellShow, c.ink)
        }
    }
    if (shown.isEmpty()) return
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        TableGrid(cols) {
            shown.forEachIndexed { r, row ->
                row.forEachIndexed { k, cell ->
                    val header = r == 0
                    val last = r == shown.lastIndex
                    Box(
                        Modifier
                            .then(if (header) Modifier.background(c.bubble) else Modifier)
                            .drawBehind {
                                if (!last) drawRect(c.line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            cell,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            color = c.ink,
                            textAlign = when (t.align.getOrNull(k)) {
                                Markdown.Align.CENTER -> TextAlign.Center
                                Markdown.Align.END -> TextAlign.End
                                else -> TextAlign.Start
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private val CELL_MIN = 56.dp
private val CELL_MAX = 240.dp

/** Cells in row-major order, [cols] to a row: columns share one width, rows one height. */
@Composable
private fun TableGrid(cols: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val minW = CELL_MIN.roundToPx()
        val maxW = CELL_MAX.roundToPx()
        val rows = (measurables.size + cols - 1) / cols
        val widths = IntArray(cols) { minW }
        measurables.forEachIndexed { k, m ->
            val col = k % cols
            widths[col] = maxOf(widths[col], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxW))
        }
        val heights = IntArray(rows)
        measurables.forEachIndexed { k, m ->
            heights[k / cols] = maxOf(heights[k / cols], m.minIntrinsicHeight(widths[k % cols]))
        }
        val placeables = measurables.mapIndexed { k, m -> m.measure(Constraints.fixed(widths[k % cols], heights[k / cols])) }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (col in 0 until cols) {
                    placeables.getOrNull(r * cols + col)?.place(x, y)
                    x += widths[col]
                }
                y += heights[r]
            }
        }
    }
}

/** A Markdown block with its inline styling already applied; [length] is how many characters it draws. */
sealed interface Styled {
    val length: Int

    class Para(val text: AnnotatedString) : Styled {
        override val length: Int get() = text.length
    }
    class Heading(val level: Int, val text: AnnotatedString) : Styled {
        override val length: Int get() = text.length
    }
    class Code(val text: AnnotatedString, val lang: String, val raw: String) : Styled {
        override val length: Int get() = text.length
    }
    class Items(val entries: List<Entry>) : Styled {
        override val length: Int = entries.sumOf { it.text.length }
    }
    class Entry(val text: AnnotatedString, val depth: Int, val ordered: Boolean, val number: Int)
    class Quote(val blocks: List<Styled>) : Styled {
        override val length: Int = blocks.sumOf { it.length }
    }
    class Table(val header: List<AnnotatedString>, val align: List<Markdown.Align>, val rows: List<List<AnnotatedString>>) : Styled {
        override val length: Int = header.sumOf { it.length } + rows.sumOf { r -> r.sumOf { it.length } }
    }
    data object Rule : Styled {
        override val length: Int get() = 1
    }
}

/** The inline styles for one palette. */
class MdStyles private constructor(private val code: SpanStyle, private val link: TextLinkStyles) {
    fun style(b: Markdown.Block): Styled = when (b) {
        is Markdown.Block.Para -> Styled.Para(styled(b.text))
        is Markdown.Block.Heading -> Styled.Heading(b.level, styled(b.text))
        is Markdown.Block.Code -> Styled.Code(AnnotatedString(b.text), b.lang, b.text)
        is Markdown.Block.Items -> Styled.Items(b.entries.map { Styled.Entry(styled(it.text), it.depth, it.ordered, it.number) })
        is Markdown.Block.Quote -> Styled.Quote(b.blocks.map(::style))
        is Markdown.Block.Table -> Styled.Table(b.header.map(::styled), b.align, b.rows.map { r -> r.map(::styled) })
        Markdown.Block.Rule -> Styled.Rule
    }

    private fun styled(text: String): AnnotatedString = buildAnnotatedString {
        Markdown.inline(text).forEach { s ->
            when (s) {
                is Markdown.Span.Plain -> append(s.text)
                is Markdown.Span.Code -> withStyle(code) { append(s.text) }
                is Markdown.Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
                is Markdown.Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                is Markdown.Span.Link ->
                    if (Markdown.isWebUrl(s.url)) withLink(LinkAnnotation.Url(s.url, link)) { append(s.text) } else append(s.text)
            }
        }
    }

    companion object {
        fun of(c: KeloColors) = MdStyles(
            SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),   // no box behind inline code (owner 2026-11-12: a white box on the bubble looked bad)
            TextLinkStyles(style = SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)),
        )
    }
}

/**
 * A reply's bubbles (split at <message_break/>) as styled blocks, for a text that keeps growing: each bubble's finished blocks are
 * parsed and styled once ([IncrementalBlocks]); only the block being written is parsed again, when more text arrives (never per
 * frame).
 */
class StreamingMarkdown(private val styles: MdStyles) {
    private val parsers = ArrayList<IncrementalBlocks>()
    private var styled = HashMap<Markdown.Block, Styled>()

    fun bubbles(text: String): List<List<Styled>> {
        val parts = Markdown.splitBubbles(text)
        while (parsers.size < parts.size) parsers += IncrementalBlocks()
        val next = HashMap<Markdown.Block, Styled>()
        val out = parts.mapIndexed { i, part ->
            parsers[i].blocks(part).map { b -> next.getOrPut(b) { styled[b] ?: styles.style(b) } }
        }
        styled = next   // only what is still shown is kept
        return out
    }
}

/** [shown] characters of this text (all of it when null), the last [RevealPacing.FADE_CHARS] fading in. */
private fun AnnotatedString.revealed(shown: Float?, ink: Color): AnnotatedString {
    if (shown == null || shown >= length) return this
    val n = ceil(shown).toInt().coerceIn(0, length)
    val from = (n - RevealPacing.FADE_CHARS.toInt() - 1).coerceAtLeast(0)
    return buildAnnotatedString {
        append(this@revealed.subSequence(0, n))
        for (i in from until n) addStyle(SpanStyle(color = ink.copy(alpha = RevealPacing.alpha(i, shown))), i, i + 1)
    }
}
