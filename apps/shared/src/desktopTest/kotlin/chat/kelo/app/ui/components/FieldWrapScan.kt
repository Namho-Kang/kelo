package chat.kelo.app.ui.components

import java.io.File

/**
 * Reads the app's Compose sources and finds every text field that can wrap although it holds one value (an address, a key, a code, a
 * name, a number), and every one-value field squeezed in a row beside a text button. Used by SingleLineFieldsTest on Android and on
 * the desktop app (which has its own field components): a new screen that breaks the rule fails the build.
 *
 * The rules:
 *  1. a field call (KeloTextField, OutlinedTextField, TextField, BasicTextField, AddressBox) that is multi-line (`singleLine = false`,
 *     no `singleLine` on a raw Material field, a MultiLine line limit) must be a message-like field: its call names a note, a prompt,
 *     a comment, an answer, a reply, a message, a review or pasted JSON, or types sentences;
 *  2. a single-line raw Material field with a placeholder shows it through FieldPlaceholder (one line, cut with …, never wrapped);
 *  3. a Row that holds a field and a text button (PrimaryButton, SecondaryButton, Button, OutlinedButton, TextButton) is a
 *     FieldWithAction instead, so the button goes under the field in a narrow column (a Row of columns, panes side by side, is not
 *     a field row);
 *  4. the components themselves: FieldPlaceholder keeps its one-line hint at maxLines = 1 and softWrap = false, and KeloTextField
 *     shows its hint through it.
 */
object FieldWrapScan {
    private val FIELD_CALL = Regex("""(?<![\w.])(KeloTextField|OutlinedTextField|TextField|BasicTextField|AddressBox)\s*\(""")
    private val RAW = setOf("OutlinedTextField", "TextField", "BasicTextField")
    private val MESSAGE = Regex("""note|prompt|comment|answer|reply|message|review|json|Sentences""", RegexOption.IGNORE_CASE)
    private val TEXT_BUTTON = Regex("""(?<![\w.])(PrimaryButton|SecondaryButton|Button|OutlinedButton|TextButton)\s*\(""")
    private val ROW = Regex("""(?<![\w.])Row\s*[({]""")

    data class Problem(val file: String, val line: Int, val rule: String) {
        override fun toString() = "$file:$line  $rule"
    }

    /** Every problem under [roots] (Kotlin sources). [components]: the files that define the field components (rule 4). */
    fun scan(roots: List<File>, components: List<File>): List<Problem> {
        val out = mutableListOf<Problem>()
        val compNames = components.map { it.canonicalPath }.toSet()
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val name = f.relativeTo(root).path
                val src = stripComments(f.readText())
                val isComponent = f.canonicalPath in compNames
                for (m in FIELD_CALL.findAll(src)) {
                    val fn = m.groupValues[1]
                    if (isComponent && fn in RAW) continue   // the component's own field: rule 4
                    val args = balanced(src, m.range.last) ?: continue
                    if (src.substring(0, m.range.first).trimEnd().endsWith("fun")) continue   // a definition, not a call
                    val line = lineOf(src, m.range.first)
                    val before = src.substring(maxOf(0, m.range.first - 240), m.range.first)
                    val multi = when {
                        Regex("""singleLine\s*=\s*false""").containsMatchIn(args) -> true
                        Regex("""TextFieldLineLimits\.MultiLine""").containsMatchIn(args) -> true
                        fn in RAW && !Regex("""singleLine\s*=|lineLimits\s*=""").containsMatchIn(args) -> true
                        else -> false
                    }
                    if (multi && !MESSAGE.containsMatchIn(args + before)) {
                        out += Problem(name, line, "$fn can wrap but holds one value: make it single-line (or name what message it holds)")
                    }
                    val variableSingle = Regex("""singleLine\s*=\s*singleLine""").containsMatchIn(args)
                    if (fn in RAW && !multi && Regex("""placeholder\s*=""").containsMatchIn(args) &&
                        !args.contains("FieldPlaceholder(") && !variableSingle) {
                        out += Problem(name, line, "$fn: show the placeholder with FieldPlaceholder (one line, never wrapped)")
                    }
                    if (fn in RAW && variableSingle && Regex("""placeholder\s*=""").containsMatchIn(args) && !args.contains("FieldPlaceholder(")) {
                        out += Problem(name, line, "$fn: show the placeholder with FieldPlaceholder(text, singleLine)")
                    }
                }
                for (m in ROW.findAll(src)) {
                    val body = if (src[m.range.last] == '{') {
                        balanced(src, m.range.last, '{', '}')
                    } else {
                        val args = balanced(src, m.range.last) ?: continue
                        val after = m.range.last + 1 + args.length + 1
                        val rest = src.substring(minOf(after, src.length)).trimStart()
                        if (rest.startsWith("{")) balanced(src, src.indexOf('{', after), '{', '}') else null
                    } ?: continue
                    // a row of whole panes (a list beside a detail) is not a field row: only fields laid out straight in the row count
                    if (Regex("""(?<![\w.])(Lazy)?Column\s*[({]""").containsMatchIn(body)) continue
                    val field = FIELD_CALL.findAll(body).firstOrNull { !(isComponent && it.groupValues[1] in RAW) } ?: continue
                    val fieldArgs = balanced(body, field.range.last) ?: ""
                    if (Regex("""singleLine\s*=\s*false""").containsMatchIn(fieldArgs)) continue
                    if (TEXT_BUTTON.containsMatchIn(body)) {
                        out += Problem(name, lineOf(src, m.range.first), "a one-value field and a text button side by side in a Row: use FieldWithAction")
                    }
                }
            }
        }
        for (f in components) {
            val src = f.readText()
            val ph = Regex("""fun FieldPlaceholder\([^)]*\)\s*\{""").find(src)
            if (ph == null) {
                out += Problem(f.name, 1, "FieldPlaceholder is missing")
            } else {
                val body = balanced(src, src.indexOf('{', ph.range.first), '{', '}').orEmpty()
                if (!Regex("""maxLines\s*=\s*1""").containsMatchIn(body) || !Regex("""softWrap\s*=\s*false""").containsMatchIn(body)) {
                    out += Problem(f.name, lineOf(src, ph.range.first), "FieldPlaceholder must keep a one-line hint at maxLines = 1, softWrap = false")
                }
            }
            val kf = Regex("""fun KeloTextField\(""").find(src)
            if (kf != null) {
                val sig = balanced(src, kf.range.last).orEmpty()
                val body = balanced(src, src.indexOf('{', kf.range.last + sig.length), '{', '}').orEmpty()
                if (!body.contains("FieldPlaceholder(")) out += Problem(f.name, lineOf(src, kf.range.first), "KeloTextField must show its hint through FieldPlaceholder")
                if (!Regex("""singleLine\s*:\s*Boolean\s*=\s*true""").containsMatchIn(sig)) out += Problem(f.name, lineOf(src, kf.range.first), "KeloTextField must be single-line by default")
            }
        }
        return out
    }

    /** The text between the bracket at [open] and its partner (strings and chars skipped), or null. */
    fun balanced(s: String, open: Int, o: Char = '(', c: Char = ')'): String? {
        if (open < 0 || open >= s.length || s[open] != o) return null
        var depth = 0
        var i = open
        var inStr = false
        var raw = false
        while (i < s.length) {
            val ch = s[i]
            if (inStr) {
                if (raw && s.startsWith("\"\"\"", i)) { inStr = false; i += 3; continue }
                if (!raw && ch == '\\') { i += 2; continue }
                if (!raw && ch == '"') inStr = false
                i++; continue
            }
            if (s.startsWith("\"\"\"", i)) { inStr = true; raw = true; i += 3; continue }
            if (ch == '"') { inStr = true; raw = false; i++; continue }
            if (ch == '\'' && i + 2 < s.length) {
                val end = s.indexOf('\'', i + 1 + if (s[i + 1] == '\\') 2 else 1)
                if (end in (i + 2)..(i + 4)) { i = end + 1; continue }
            }
            if (ch == o) depth++
            if (ch == c) { depth--; if (depth == 0) return s.substring(open + 1, i) }
            i++
        }
        return null
    }

    private fun stripComments(s: String): String {
        // line and block comments become spaces of the same length (lines keep their numbers); strings are left alone
        val b = StringBuilder(s)
        var i = 0
        var inStr = false
        while (i < b.length) {
            val ch = b[i]
            if (inStr) { if (ch == '\\') { i += 2; continue }; if (ch == '"' || ch == '\n') inStr = false; i++; continue }
            if (ch == '"') { inStr = !b.startsWith("\"\"\"", i); if (!inStr) { val e = b.indexOf("\"\"\"", i + 3); i = if (e < 0) b.length else e + 3; continue }; i++; continue }
            if (b.startsWith("//", i)) { while (i < b.length && b[i] != '\n') { b.setCharAt(i, ' '); i++ }; continue }
            if (b.startsWith("/*", i)) {
                val e = b.indexOf("*/", i + 2).let { if (it < 0) b.length else it + 2 }
                for (k in i until e) if (b[k] != '\n') b.setCharAt(k, ' ')
                i = e; continue
            }
            i++
        }
        return b.toString()
    }

    private fun StringBuilder.startsWith(p: String, at: Int) = at + p.length <= length && substring(at, at + p.length) == p

    private fun lineOf(s: String, at: Int) = s.substring(0, at).count { it == '\n' } + 1
}
