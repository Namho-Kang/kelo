// The apps' text has one home: apps/shared/strings (Android format: aapt's escapes and quoting). Android reads it as its own resources;
// Compose resources cannot read that format (they undo only \n \t \uXXXX \\ and know nothing of \' \" quotes or whitespace folding),
// so this task converts it at build time into the files Compose reads. No Python in the build; no markup inside strings (none exists).
//
// Applied by build.gradle.kts, which registers task `androidStringsToCompose` from here and gives its output to compose.resources.
// The golden test (src/desktopTest/.../StringsConversionTest) runs the same converter on src/desktopTest/strings-conversion.

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** The reading of the Android text and its writing for Compose. Pure JDK. */
object StringsConverter {
    /** values directory of the Android format -> the directories Compose looks in (language and -rREGION only, no b+zh+Hans form). */
    fun outputDirs(dir: String): List<String> = when (dir) {
        "values-b+zh+Hans" -> listOf("values-zh", "values-zh-rCN")
        "values-b+zh+Hant" -> listOf("values-zh-rTW", "values-zh-rHK")
        "values-in" -> listOf("values-in", "values-id")   // Android says "in", the JVM says "id"
        else -> listOf(dir)
    }

    class Entry(val kind: String, val name: String, val translatable: Boolean, val values: List<Pair<String?, String>>)

    /** XML-decoded element text -> what the app shows (aapt2's rules). A port of apps/android/tools/strings_res.py unescape(). */
    fun unescape(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        val n = raw.length
        var pendingSpace = false
        while (i < n) {
            val c = raw[i]
            if (c == '\\' && i + 1 < n) {
                val nxt = raw[i + 1]
                if (pendingSpace) { out.append(' '); pendingSpace = false }
                when {
                    nxt == 'n' -> out.append('\n')
                    nxt == 't' -> out.append('\t')
                    nxt == 'u' && i + 6 <= n && raw.substring(i + 2, i + 6).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> {
                        out.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                        continue
                    }
                    else -> out.append(nxt)   // \' \" \\ \@ \? and anything else: the character itself
                }
                i += 2
                continue
            }
            if (c == '"') {
                quoted = !quoted
                if (pendingSpace) { out.append(' '); pendingSpace = false }
                i++
                continue
            }
            if (!quoted && (c == ' ' || c == '\t' || c == '\r' || c == '\n')) {
                pendingSpace = out.isNotEmpty()   // leading whitespace is dropped
                i++
                continue
            }
            if (pendingSpace) { out.append(' '); pendingSpace = false }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** The text as Compose resources read it back: \ \n \t escaped, then XML-escaped. */
    fun composeEscape(text: String): String {
        val sb = StringBuilder()
        for (ch in text) when (ch) {
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun textOf(el: Element, where: String): String {
        val sb = StringBuilder()
        var c = el.firstChild
        while (c != null) {
            when (c.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(c.nodeValue)
                Node.COMMENT_NODE -> {}
                else -> throw GradleException("$where: markup inside a string (<${c.nodeName}>) is not supported")
            }
            c = c.nextSibling
        }
        return unescape(sb.toString())
    }

    private fun items(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val c = nodes.item(i)
            if (c is Element && c.tagName == "item") out += c
        }
        return out
    }

    fun read(file: File): List<Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = ArrayList<Entry>()
        var node = doc.documentElement.firstChild
        while (node != null) {
            val el = node as? Element
            node = node.nextSibling
            if (el == null) continue
            val name = el.getAttribute("name")
            val tr = el.getAttribute("translatable") != "false"
            val where = "${file.name}: $name"
            when (el.tagName) {
                "string" -> out += Entry("string", name, tr, listOf(null to textOf(el, where)))
                "plurals" -> out += Entry("plurals", name, tr, items(el).map { it.getAttribute("quantity") to textOf(it, where) })
                "string-array" -> out += Entry("array", name, tr, items(el).map { null to textOf(it, where) })
            }
        }
        return out
    }

    /** One language's Compose resources file. */
    fun render(entries: List<Entry>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        for (e in entries) when (e.kind) {
            "string" -> sb.append("    <string name=\"${e.name}\">${composeEscape(e.values[0].second)}</string>\n")
            "array" -> {
                sb.append("    <string-array name=\"${e.name}\">\n")
                for ((_, t) in e.values) sb.append("        <item>${composeEscape(t)}</item>\n")
                sb.append("    </string-array>\n")
            }
            else -> {
                sb.append("    <plurals name=\"${e.name}\">\n")
                for ((q, t) in e.values) sb.append("        <item quantity=\"$q\">${composeEscape(t)}</item>\n")
                sb.append("    </plurals>\n")
            }
        }
        return sb.append("</resources>\n").toString()
    }

    /** Converts every values* directory of [input] into [output]; fails on a name that appears twice in one language. */
    fun convert(input: File, output: File) {
        output.deleteRecursively()
        val dirs = input.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }.orEmpty().sortedBy { it.name }
        for (dir in dirs) {
            val entries = ArrayList<Entry>()
            val seen = HashSet<String>()
            for (file in dir.listFiles { f -> f.isFile && f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }) {
                for (e in read(file)) {
                    if (dir.name != "values" && !e.translatable) continue   // untranslatable lines live only in values/
                    if (!seen.add(e.kind + ":" + e.name)) throw GradleException("${dir.name}: ${e.name} appears twice (second time in ${file.name})")
                    entries += e
                }
            }
            val text = render(entries)
            for (target in outputDirs(dir.name)) {
                val d = File(output, target)
                d.mkdirs()
                File(d, "strings.xml").writeText(text, Charsets.UTF_8)
            }
        }
    }
}

/** apps/shared/strings (Android format) -> Compose resources (values* / strings.xml). */
@CacheableTask
abstract class AndroidStringsToCompose : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun convert() = StringsConverter.convert(input.get().asFile, output.get().asFile)
}

val androidStringsToCompose = tasks.register<AndroidStringsToCompose>("androidStringsToCompose") {
    group = "kelo"
    description = "Converts apps/shared/strings (Android format) into Compose resources."
    input.set(layout.projectDirectory.dir("strings"))
    output.set(layout.buildDirectory.dir("generated/keloStrings/composeResources"))
}

extra["keloStrings"] = androidStringsToCompose.flatMap { it.output }

/** The golden test's input: the same converter on a few lines made to be hard (src/desktopTest/strings-conversion/in). */
val convertStringsFixtures = tasks.register<AndroidStringsToCompose>("convertStringsFixtures") {
    input.set(layout.projectDirectory.dir("src/desktopTest/strings-conversion/in"))
    output.set(layout.buildDirectory.dir("generated/stringsFixtures"))
}
