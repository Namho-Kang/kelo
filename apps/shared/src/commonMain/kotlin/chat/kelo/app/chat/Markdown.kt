package chat.kelo.app.chat

/**
 * The Markdown a reply uses: headings (h1-h3; deeper ones count as h3), paragraphs, block quotes, ordered and unordered lists
 * (nested by indent), tables, fenced code blocks with their language, horizontal rules; inline bold, italic, `code` and links.
 * Parsed into plain data here; [MarkdownText] draws it (never as HTML).
 *
 * Every block except a code fence ends at a blank line, so a text can be parsed in pieces cut at blank lines outside a fence
 * ([stableCut]) and give the same blocks as parsing it whole ([IncrementalBlocks] relies on that).
 */
object Markdown {
    sealed interface Block {
        data class Para(val text: String) : Block
        /** [level] 1..3 */
        data class Heading(val level: Int, val text: String) : Block
        /** [lang]: the fence's info word ("kotlin"), empty when there is none */
        data class Code(val text: String, val lang: String = "") : Block
        /** A list; nesting is each entry's [Entry.depth] */
        data class Items(val entries: List<Entry>) : Block
        data class Quote(val blocks: List<Block>) : Block
        /** Every row has as many cells as [header] */
        data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    /** One list entry: [number] is its own number in an ordered list (as written), 0 in a bullet list. */
    data class Entry(val text: String, val depth: Int = 0, val ordered: Boolean = false, val number: Int = 0)

    enum class Align { START, CENTER, END }

    sealed interface Span {
        data class Plain(val text: String) : Span
        data class Code(val text: String) : Span
        data class Bold(val text: String) : Span
        data class Italic(val text: String) : Span
        data class Link(val text: String, val url: String) : Span
    }

    private val INLINE = Regex(
        """(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\s][^*\n]*\*|\[[^\]\n]+\]\(https?://[^\s)]+\)|https?://[^\s<>()\[\]]+)""",
    )
    private val LINK = Regex("""^\[([^\]]+)\]\((https?://[^\s)]+)\)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBER = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)(\s+#+)?\s*$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
    private val DELIMITER = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    /** What a bare address may not end with (the sentence's own punctuation) */
    private const val TRAILING = ".,;:!?'\""

    /** Only these open in the browser; anything else stays text. */
    fun isWebUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var at = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first < at) continue
            if (m.range.first > at) out += Span.Plain(text.substring(at, m.range.first))
            var tok = m.value
            var end = m.range.last + 1
            when {
                tok.startsWith("`") && tok.endsWith("`") && tok.length > 2 -> out += Span.Code(tok.substring(1, tok.length - 1))
                tok.startsWith("**") && tok.endsWith("**") && tok.length > 4 -> out += Span.Bold(tok.substring(2, tok.length - 2))
                tok.startsWith("*") && tok.endsWith("*") && tok.length > 2 -> out += Span.Italic(tok.substring(1, tok.length - 1))
                tok.startsWith("[") -> out += LINK.find(tok)?.let { Span.Link(it.groupValues[1], it.groupValues[2]) } ?: Span.Plain(tok)
                else -> {   // a bare address: its trailing punctuation belongs to the sentence
                    val trimmed = tok.trimEnd { it in TRAILING }
                    end -= tok.length - trimmed.length
                    tok = trimmed
                    out += if (tok.length > "https://".length) Span.Link(tok, tok) else Span.Plain(tok)
                }
            }
            at = end
        }
        if (at < text.length) out += Span.Plain(text.substring(at))
        return merge(out)
    }

    /** Neighbouring plain runs as one. */
    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        for (s in spans) {
            val last = out.lastOrNull()
            if (s is Span.Plain && last is Span.Plain) out[out.lastIndex] = Span.Plain(last.text + s.text) else out += s
        }
        return out
    }

    /** The fence a line opens ("```" or "~~~", as long as written), or null. */
    fun fenceOf(line: String): String? {
        val t = line.trim()
        val ch = t.firstOrNull() ?: return null
        if (ch != '`' && ch != '~') return null
        val n = t.takeWhile { it == ch }.length
        return if (n >= 3) t.substring(0, n) else null
    }

    /** [line] closes a fence opened with [fence]: the same character, at least as many, nothing after. */
    fun closesFence(line: String, fence: String): Boolean {
        val t = line.trim()
        return t.length >= fence.length && t.all { it == fence[0] }
    }

    private fun indentOf(s: String): Int {
        var n = 0
        for (ch in s) {
            when (ch) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        i + 1 < lines.size && lines[i].contains('|') && DELIMITER.matches(lines[i + 1]) &&
            (lines[i + 1].contains('|') || lines[i].trim().startsWith("|"))

    /** A table row's cells: outer pipes dropped, split at pipes that are not escaped (\|) or inside `code`. */
    fun cells(row: String): List<String> {
        var t = row.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var code = false
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                ch == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { cur.append('|'); i++ }
                ch == '`' -> { code = !code; cur.append(ch) }
                ch == '|' && !code -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    private fun alignOf(cell: String): Align {
        val c = cell.trim()
        return when {
            c.startsWith(":") && c.endsWith(":") -> Align.CENTER
            c.endsWith(":") -> Align.END
            else -> Align.START
        }
    }

    /** A line that starts some other block (it ends a paragraph or a list without a blank line). */
    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        return fenceOf(line) != null || HEADING.matches(line) || RULE.matches(line) || QUOTE.matches(line) ||
            BULLET.matches(line) || NUMBER.matches(line) || isTableStart(lines, i)
    }

    fun blocks(text: String): List<Block> {
        val lines = text.split("\n")
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) { out += Block.Para(para.joinToString("\n")); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceOf(line)
            if (fence != null) {   // fenced block; an unclosed one (still streaming) runs to the end
                flush()
                val lang = line.trim().substring(fence.length).trim().substringBefore(' ').trim('{', '}', '.')
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !closesFence(lines[i], fence)) body += lines[i++]
                i++
                out += Block.Code(body.joinToString("\n"), lang)
                continue
            }
            if (line.isBlank()) { flush(); i++; continue }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flush()
                out += Block.Heading(heading.groupValues[1].length.coerceAtMost(3), heading.groupValues[2].trim())
                i++
                continue
            }
            if (RULE.matches(line)) { flush(); out += Block.Rule; i++; continue }
            if (QUOTE.matches(line)) {
                flush()
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val m = QUOTE.matchEntire(lines[i]) ?: break
                    inner += m.groupValues[1]
                    i++
                }
                out += Block.Quote(blocks(inner.joinToString("\n")))
                continue
            }
            if (isTableStart(lines, i)) {
                flush()
                val header = cells(lines[i])
                val align = cells(lines[i + 1]).map(::alignOf)
                val n = header.size
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val r = cells(lines[i])
                    rows += List(n) { k -> r.getOrElse(k) { "" } }
                    i++
                }
                out += Block.Table(header, List(n) { k -> align.getOrElse(k) { Align.START } }, rows)
                continue
            }
            if (BULLET.matches(line) || NUMBER.matches(line)) {
                flush()
                i = list(lines, i, out)
                continue
            }
            para += line
            i++
        }
        flush()
        return out
    }

    /** A list from line [start]; returns the line after it. Deeper indent nests (up to [MAX_DEPTH]); an indented line continues an entry. */
    private fun list(lines: List<String>, start: Int, out: MutableList<Block>): Int {
        val entries = mutableListOf<Entry>()
        val indents = ArrayList<Int>()   // the indent of each open level
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val number = NUMBER.matchEntire(line)
            val bullet = if (number == null) BULLET.matchEntire(line) else null
            if (number == null && bullet == null) {
                if (entries.isNotEmpty() && indentOf(line) >= 2 && !startsBlock(lines, i)) {
                    val last = entries.last()
                    entries[entries.lastIndex] = last.copy(text = last.text + "\n" + line.trim())
                    i++
                    continue
                }
                break
            }
            val indent = indentOf((number ?: bullet)!!.groupValues[1])
            if (indents.isEmpty()) {
                if (indent >= 2) indents += 0   // a list that starts indented (after a blank line in a nested list) stays nested
                indents += indent
            } else if (indent > indents.last() + 1) {
                if (indents.size <= MAX_DEPTH) indents += indent
            } else {
                while (indents.size > 1 && indent < indents.last() - 1) indents.removeAt(indents.lastIndex)
            }
            val depth = indents.size - 1
            // a numbered list right after a bullet list (or the other way round) is a list of its own
            if (depth == 0 && entries.isNotEmpty() && entries.first().ordered != (number != null)) break
            entries += if (number != null) Entry(number.groupValues[3], depth, ordered = true, number = number.groupValues[2].toIntOrNull() ?: 1)
            else Entry(bullet!!.groupValues[2], depth)
            i++
        }
        out += Block.Items(entries)
        return i
    }

    private const val MAX_DEPTH = 4

    /**
     * Where the final blocks of [text] end while it is still growing: the offset just after the last blank line that is outside a
     * code fence (0 when there is none). Blocks before it can no longer change as text is added, so they are parsed once.
     * Scanning starts at [from], which must itself be such an offset (0 or an earlier answer).
     */
    fun stableCut(text: String, from: Int = 0): Int {
        var cut = from
        var fence: String? = null
        var at = from
        while (true) {
            val nl = text.indexOf('\n', at)
            if (nl < 0) break   // the last line is not complete yet
            val line = text.substring(at, nl)
            val open = fence
            if (open != null) {
                if (closesFence(line, open)) fence = null
            } else {
                val f = fenceOf(line)
                if (f != null) fence = f
                else if (line.isBlank()) cut = nl + 1
            }
            at = nl + 1
        }
        return cut
    }

    /**
     * The text as a person reads it, without the Markdown marks: what 복사, 공유 and 텍스트 선택 give for a reply. Lists keep a
     * bullet or their number, table cells are separated by " | ", code stays as written.
     */
    fun plain(text: String): String = blocks(text).mapNotNull(::plainBlock).joinToString("\n\n")

    private fun plainBlock(b: Block): String? = when (b) {
        is Block.Para -> plainInline(b.text)
        is Block.Heading -> plainInline(b.text)
        is Block.Code -> b.text
        is Block.Items -> b.entries.joinToString("\n") { e ->
            "  ".repeat(e.depth) + (if (e.ordered) "${e.number}. " else "• ") + plainInline(e.text)
        }
        is Block.Quote -> b.blocks.mapNotNull(::plainBlock).joinToString("\n\n")
        is Block.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString(" | ") { plainInline(it) } }
        Block.Rule -> null
    }

    private fun plainInline(text: String): String = inline(text).joinToString("") { s ->
        when (s) {
            is Span.Plain -> s.text
            is Span.Code -> s.text
            is Span.Bold -> s.text
            is Span.Italic -> s.text
            is Span.Link -> if (s.text == s.url) s.url else "${s.text} (${s.url})"
        }
    }

    private const val BREAK = "<message_break/>"
    private val BREAK_RE = Regex("""<message_break\s*/?>""")

    /** Split one reply into chat bubbles at <message_break/>; hides a half-typed tag while the reply streams. */
    fun splitBubbles(text: String): List<String> {
        var t = text
        for (n in minOf(BREAK.length - 1, t.length) downTo 1) {
            if (t.endsWith(BREAK.substring(0, n))) { t = t.dropLast(n); break }
        }
        return t.split(BREAK_RE).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * [Markdown.blocks] for a text that keeps growing (a reply being written): the blocks before the last blank line are parsed once
 * and kept; only the block still being written is parsed again when more text arrives. Gives the same blocks as parsing the whole
 * text each time.
 */
class IncrementalBlocks {
    private var headText = ""
    private var head: List<Markdown.Block> = emptyList()

    fun blocks(text: String): List<Markdown.Block> {
        if (!text.startsWith(headText)) { headText = ""; head = emptyList() }   // not a continuation: start over
        val cut = Markdown.stableCut(text, headText.length)
        if (cut > headText.length) {
            head = head + Markdown.blocks(text.substring(headText.length, cut))
            headText = text.substring(0, cut)
        }
        return if (cut >= text.length) head else head + Markdown.blocks(text.substring(cut))
    }
}
