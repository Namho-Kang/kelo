package chat.kelo.app.chat

import chat.kelo.app.chat.Markdown.Align
import chat.kelo.app.chat.Markdown.Block
import chat.kelo.app.chat.Markdown.Entry
import chat.kelo.app.chat.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun splitBubbles() {
        assertEquals(listOf("a", "b"), Markdown.splitBubbles("a<message_break/>b"))
        assertEquals(listOf("a", "b"), Markdown.splitBubbles("a <message_break /> b<message_break>"))
        assertEquals(listOf("a"), Markdown.splitBubbles("a<message_br"))   // half-typed tag hidden while streaming
        assertEquals(emptyList<String>(), Markdown.splitBubbles("  "))
    }

    @Test fun inlineSpans() {
        assertEquals(
            listOf(Span.Plain("a "), Span.Bold("b"), Span.Plain(" "), Span.Italic("c"), Span.Plain(" "), Span.Code("d"),
                Span.Plain(" "), Span.Link("e", "https://x.y/z")),
            Markdown.inline("a **b** *c* `d` [e](https://x.y/z)"),
        )
        assertEquals(listOf(Span.Plain("[e](javascript:x)")), Markdown.inline("[e](javascript:x)"))
    }

    @Test fun blocks() {
        val b = Markdown.blocks("intro\nmore\n\n- one\n* two\n1. first\n2) second\n```\ncode\n```\n# head\n```\nopen")
        assertEquals(
            listOf(
                Block.Para("intro\nmore"),
                Block.Items(listOf(Entry("one"), Entry("two"))),
                Block.Items(listOf(Entry("first", ordered = true, number = 1), Entry("second", ordered = true, number = 2))),
                Block.Code("code"),
                Block.Heading(1, "head"),
                Block.Code("open"),
            ),
            b,
        )
    }

    @Test fun headings() {
        assertEquals(
            listOf(Block.Heading(1, "One"), Block.Heading(2, "Two"), Block.Heading(3, "Three"), Block.Heading(3, "Four"), Block.Para("#nospace")),
            Markdown.blocks("# One\n## Two ##\n### Three\n#### Four\n#nospace"),
        )
        // a heading right under a paragraph line ends the paragraph
        assertEquals(listOf(Block.Para("text"), Block.Heading(2, "H")), Markdown.blocks("text\n## H"))
    }

    @Test fun quotes() {
        assertEquals(
            listOf(Block.Quote(listOf(Block.Para("a\nb"), Block.Items(listOf(Entry("c"))))), Block.Para("after")),
            Markdown.blocks("> a\n>b\n>\n> - c\n\nafter"),
        )
        assertEquals(listOf(Block.Quote(listOf(Block.Quote(listOf(Block.Para("deep")))))), Markdown.blocks("> > deep"))
    }

    @Test fun nestedLists() {
        val b = Markdown.blocks("- a\n  - b\n    1. c\n    2. d\n  - e\n- f\n  more of f")
        assertEquals(
            listOf(
                Block.Items(
                    listOf(
                        Entry("a"), Entry("b", 1), Entry("c", 2, true, 1), Entry("d", 2, true, 2), Entry("e", 1), Entry("f\nmore of f"),
                    ),
                ),
            ),
            b,
        )
        // an ordered list keeps the numbers written; a blank line ends it
        assertEquals(
            listOf(Block.Items(listOf(Entry("x", 0, true, 3))), Block.Items(listOf(Entry("y", 0, true, 4)))),
            Markdown.blocks("3. x\n\n4. y"),
        )
        // tab indents count as four spaces
        assertEquals(listOf(Block.Items(listOf(Entry("a"), Entry("b", 1)))), Markdown.blocks("* a\n\t* b"))
    }

    @Test fun tables() {
        val b = Markdown.blocks("| Name | Qty | Note |\n|:--|:-:|--:|\n| `a|b` | 2 |\n| c \\| d | 3 | x | extra |\n\nafter")
        assertEquals(
            listOf(
                Block.Table(
                    listOf("Name", "Qty", "Note"),
                    listOf(Align.START, Align.CENTER, Align.END),
                    listOf(listOf("`a|b`", "2", ""), listOf("c | d", "3", "x")),
                ),
                Block.Para("after"),
            ),
            b,
        )
        // without the delimiter row it is just text (a table still being written shows as text until then)
        assertEquals(listOf(Block.Para("| a | b |")), Markdown.blocks("| a | b |"))
        // no outer pipes
        assertEquals(listOf(Block.Table(listOf("a", "b"), listOf(Align.START, Align.START), listOf(listOf("1", "2")))), Markdown.blocks("a | b\n--- | ---\n1 | 2"))
    }

    @Test fun codeFencesKeepTheirLanguage() {
        assertEquals(
            listOf(Block.Code("val x = 1\n\nprintln(x)", "kotlin"), Block.Code("~~~ inside", ""), Block.Code("plain", "py")),
            Markdown.blocks("```kotlin\nval x = 1\n\nprintln(x)\n```\n````\n~~~ inside\n````\n~~~py\nplain\n~~~"),
        )
        // a fence inside a list ends the list
        assertEquals(listOf(Block.Items(listOf(Entry("a"))), Block.Code("x", "sh")), Markdown.blocks("- a\n  ```sh\n  x\n  ```").let {
            listOf(it[0], (it[1] as Block.Code).copy(text = (it[1] as Block.Code).text.trim()))
        })
    }

    @Test fun rules() {
        assertEquals(listOf(Block.Para("a"), Block.Rule, Block.Para("b"), Block.Rule, Block.Rule), Markdown.blocks("a\n---\nb\n* * *\n___"))
        assertEquals(listOf(Block.Items(listOf(Entry("-")))), Markdown.blocks("- -"))
    }

    @Test fun links() {
        assertEquals(
            listOf(Span.Plain("see "), Span.Link("https://a.b/c?d=1", "https://a.b/c?d=1"), Span.Plain(". and "),
                Span.Link("x", "http://x.y")),
            Markdown.inline("see https://a.b/c?d=1. and [x](http://x.y)"),
        )
        assertEquals(listOf(Span.Plain("(at "), Span.Link("https://q.r", "https://q.r"), Span.Plain(")")), Markdown.inline("(at https://q.r)"))
        // other schemes are never links
        assertEquals(listOf(Span.Plain("[e](mailto:a@b.c) ftp://x javascript:alert(1)")), Markdown.inline("[e](mailto:a@b.c) ftp://x javascript:alert(1)"))
        assertEquals(true, Markdown.isWebUrl("HTTPS://x"))
        assertEquals(false, Markdown.isWebUrl("intent://x"))
    }

    @Test fun plainTextDropsTheMarks() {
        val md = "# Title\n\nSome **bold** and `code`, see [docs](https://d.io) or https://x.y.\n\n- a\n  1. b\n\n> quoted\n\n| h1 | h2 |\n|---|---|\n| c | d |\n\n---\n\n```kt\nval x = 1\n```"
        assertEquals(
            "Title\n\nSome bold and code, see docs (https://d.io) or https://x.y.\n\n• a\n  1. b\n\nquoted\n\nh1 | h2\nc | d\n\nval x = 1",
            Markdown.plain(md),
        )
    }
}
