package chat.kelo.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Guards the files that live twice in the repository, once in android/kelo and once here (desktop/COPIES.md lists them):
 * an `identical` copy must still equal its Android original (the package line aside); a copy `edits` must be re-checked when its
 * Android original(s) changed (the recorded hash no longer matches); every file with the name of an Android or shared/ file, and every hand
 * copy in src/main/kotlin/chat/kelo/app, must be listed.
 */
class CopiesTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- the real repository

    @Test fun `copies of Android files are in step with their originals`() {
        val root = repoRoot()
        val problems = CopyGuard.parse(File(root, "desktop/COPIES.md").readText()).mapNotNull { CopyGuard.check(it, root) }
        assertTrue("\n" + problems.joinToString("\n\n"), problems.isEmpty())
    }

    @Test fun `every row of the copies tables starts with desktop`() {
        val problems = CopyGuard.strayRows(File(repoRoot(), "desktop/COPIES.md").readText())
        assertTrue("\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun `every desktop file with an Android file's name is listed in COPIES md`() {
        val root = repoRoot()
        val problems = CopyGuard.unlisted(root, CopyGuard.parse(File(root, "desktop/COPIES.md").readText()))
        assertTrue("\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun `the copies file has rows, each with a class, a current-looking hash and a reason`() {
        val rows = CopyGuard.parse(File(repoRoot(), "desktop/COPIES.md").readText())
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.cls in CopyGuard.CLASSES })
        // an identical/edits row records the Android original's hash (the test above compares it with the file as it is now)
        assertTrue(rows.filter { it.cls == "edits" }.all { Regex("[0-9a-f]{16}").matches(it.hash) })
        // an unrelated row says why the desktop file only shares a name
        val bare = rows.filter { it.cls == "unrelated" && it.why.isBlank() }.map { it.desktop }
        assertTrue("unrelated rows without a reason: $bare", bare.isEmpty())
    }

    // ---- the checks themselves

    private fun write(root: File, path: String, text: String) = File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test fun `an identical copy may differ in the package line only`() {
        val root = tmp.newFolder()
        write(root, "android/A.kt", "package chat.kelo.app.x\n\nclass A {\r\n    val a = 1\n}\n")
        write(root, "desktop/A.kt", "package chat.kelo.app.y\n\nclass A {\n    val a = 1\n}\n")
        val row = CopyGuard.Row("desktop/A.kt", listOf("android/A.kt"), "identical", "-", "")
        assertEquals(null, CopyGuard.check(row, root))
    }

    @Test fun `an identical copy that differs fails and names the pair and the resync`() {
        val root = tmp.newFolder()
        write(root, "android/A.kt", "package p\n\nclass A {\n    val a = 1\n}\n")
        write(root, "desktop/A.kt", "package p\n\nclass A {\n    val a = 2\n}\n")
        val msg = CopyGuard.check(CopyGuard.Row("desktop/A.kt", listOf("android/A.kt"), "identical", "-", ""), root)
        assertNotNull(msg)
        assertTrue(msg!!, "desktop/A.kt" in msg && "android/A.kt" in msg && "line 3" in msg && "val a = 1" in msg && "copy the Android file" in msg)
    }

    @Test fun `an edited copy passes while the Android original is unchanged and fails when it changes`() {
        val root = tmp.newFolder()
        val android = write(root, "android/A.kt", "package p\nclass A\n")
        write(root, "android/B.kt", "package p\nclass B\n")
        write(root, "desktop/A.kt", "package p\nclass A(val x: Int)\n")
        val hash = CopyGuard.hashOf(listOf(android.readBytes(), File(root, "android/B.kt").readBytes()))
        val row = CopyGuard.Row("desktop/A.kt", listOf("android/A.kt", "android/B.kt"), "edits", hash, "")
        assertEquals(null, CopyGuard.check(row, root))
        android.writeText("package p\nclass A { fun f() {} }\n")
        val msg = CopyGuard.check(row, root)
        assertNotNull(msg)
        assertTrue(msg!!, "desktop/A.kt" in msg && "android/A.kt" in msg && "changed" in msg && hash in msg && "copies_hash.py --update desktop/A.kt" in msg)
    }

    @Test fun `the hash does not depend on line endings and a missing original is reported`() {
        val root = tmp.newFolder()
        write(root, "android/A.kt", "a\r\nb\r\n")
        write(root, "desktop/A.kt", "x")
        assertEquals(CopyGuard.hashOf(listOf("a\nb\n".toByteArray())), CopyGuard.hashOf(listOf(File(root, "android/A.kt").readBytes())))
        val gone = CopyGuard.Row("desktop/A.kt", listOf("android/Gone.kt"), "edits", "0123456789abcdef", "")
        assertTrue(CopyGuard.check(gone, root)!!.contains("android/Gone.kt"))
        val noDesktop = CopyGuard.Row("desktop/Nope.kt", listOf("android/A.kt"), "unrelated", "-", "")
        assertTrue(CopyGuard.check(noDesktop, root)!!.contains("desktop/Nope.kt"))
    }

    @Test fun `a same-name file and a hand copy that are not listed are reported`() {
        val root = tmp.newFolder()
        write(root, "android/kelo/app/src/main/java/chat/kelo/app/ui/Same.kt", "x")
        write(root, "desktop/src/main/kotlin/chat/kelo/desktop/ui/Same.kt", "x")
        write(root, "desktop/src/main/kotlin/chat/kelo/desktop/ui/Own.kt", "x")
        write(root, "desktop/src/main/kotlin/chat/kelo/app/chat/Hand.kt", "x")
        write(root, "shared/src/commonMain/kotlin/chat/kelo/app/ui/Shared.kt", "x")
        write(root, "desktop/src/main/kotlin/chat/kelo/desktop/ui/Shared.kt", "x")
        val problems = CopyGuard.unlisted(root, emptyList())
        assertEquals(3, problems.size)
        assertTrue(problems.any { "desktop/src/main/kotlin/chat/kelo/desktop/ui/Same.kt" in it })
        assertTrue(problems.any { "desktop/src/main/kotlin/chat/kelo/app/chat/Hand.kt" in it })
        assertTrue(problems.any { "desktop/src/main/kotlin/chat/kelo/desktop/ui/Shared.kt" in it })   // the name of a file under shared/src
        val listed = listOf(
            CopyGuard.Row("desktop/src/main/kotlin/chat/kelo/desktop/ui/Same.kt", listOf("x"), "unrelated", "-", ""),
            CopyGuard.Row("desktop/src/main/kotlin/chat/kelo/app/chat/Hand.kt", listOf("x"), "edits", "0123456789abcdef", ""),
            CopyGuard.Row("desktop/src/main/kotlin/chat/kelo/desktop/ui/Shared.kt", listOf("x"), "unrelated", "-", ""),
        )
        assertEquals(emptyList<String>(), CopyGuard.unlisted(root, listed))
    }

    @Test fun `rows are read from the markdown table`() {
        val rows = CopyGuard.parse(
            """
            | desktop file | Android original(s) | class | hash | why |
            |---|---|---|---|---|
            | desktop/a/A.kt | android/a/A.kt, android/b/B.kt | edits | 0123456789abcdef | because |
            some text | desktop/x | not a row
            """.trimIndent(),
        )
        assertEquals(listOf(CopyGuard.Row("desktop/a/A.kt", listOf("android/a/A.kt", "android/b/B.kt"), "edits", "0123456789abcdef", "because")), rows)
    }

    @Test fun `a row of a copies table that does not start with desktop is reported with its line`() {
        val md = listOf(
            "| class | meaning |", "|---|---|", "| `edits` | another table, not checked |", "",
            "| desktop file | Android original(s) | class | hash | why |",   // line 5
            "|---|---|---|---|---|",
            "| desktop/a/A.kt | android/a/A.kt | edits | 0123456789abcdef | ok |",
            "| Desktop/b/B.kt | android/b/B.kt | edits | 0123456789abcdef | wrong case |",   // line 8
            "|desktop/c/C.kt | android/c/C.kt | edits | 0123456789abcdef | no space |",   // line 9
            "",
            "| desktop/d/D.kt | android/d/D.kt | edits | 0123456789abcdef | after the table: not checked |",
        ).joinToString("\n")
        val problems = CopyGuard.strayRows(md)
        assertEquals(2, problems.size)
        assertTrue(problems[0], "line 8" in problems[0] && "Desktop/b/B.kt" in problems[0])
        assertTrue(problems[1], "line 9" in problems[1] && "|desktop/c/C.kt" in problems[1])
    }

    @Test fun `an identical row with more than one original fails with a message and does not throw`() {
        val root = tmp.newFolder()
        write(root, "android/A.kt", "package p\nclass A\n")
        write(root, "android/B.kt", "package p\nclass B\n")
        write(root, "desktop/A.kt", "package p\nclass A\n")
        val msg = CopyGuard.check(CopyGuard.Row("desktop/A.kt", listOf("android/A.kt", "android/B.kt"), "identical", "-", ""), root)
        assertNotNull(msg)
        assertTrue(msg!!, "desktop/A.kt" in msg && "identical" in msg && "exactly one" in msg && "android/A.kt, android/B.kt" in msg)
    }

    @Test fun `only the first package line is not compared`() {
        val root = tmp.newFolder()
        write(root, "android/A.kt", "package p.a\n\nclass A {\n    val s = \"\"\"\npackage inside\n\"\"\"\n}\n")
        write(root, "desktop/A.kt", "package p.d\n\nclass A {\n    val s = \"\"\"\n\"\"\"\n}\n")
        val msg = CopyGuard.check(CopyGuard.Row("desktop/A.kt", listOf("android/A.kt"), "identical", "-", ""), root)
        assertNotNull(msg)
        assertTrue(msg!!, "package inside" in msg)
    }

    private fun repoRoot(): File =
        generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "desktop/COPIES.md").isFile }
            ?: error("desktop/COPIES.md not found above ${File(".").canonicalPath}")
}

internal object CopyGuard {
    val CLASSES = setOf("identical", "edits", "unrelated")

    /** One row of desktop/COPIES.md: paths are relative to apps/. */
    data class Row(val desktop: String, val originals: List<String>, val cls: String, val hash: String, val why: String)

    fun parse(md: String): List<Row> = md.lines().filter { it.startsWith("| desktop/") }.map { line ->
        val c = line.trim().trim('|').split("|").map { it.trim() }
        require(c.size >= 5) { "desktop/COPIES.md: a row needs 5 cells: $line" }
        Row(c[0], c[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }, c[2], c[3], c[4])
    }

    /** SHA-256 of the files one after the other with "\r" removed, first 16 hex digits (tools/copies_hash.py does the same). */
    fun hashOf(files: List<ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (f in files) md.update(f.filter { it != '\r'.code.toByte() }.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** The lines of the file without its first `package ` line (a later line that starts with "package " counts). */
    private fun lines(f: File): List<String> {
        val all = f.readText().replace("\r", "").split("\n")
        val pkg = all.indexOfFirst { it.startsWith("package ") }
        return if (pkg < 0) all else all.filterIndexed { i, _ -> i != pkg }
    }

    /**
     * Lines of the copies tables (each starts at the `| desktop file` header and runs while the lines start with a `|`) that are not
     * a row starting with `| desktop/`: the build and [parse] would skip them without a word.
     */
    fun strayRows(md: String): List<String> {
        val problems = mutableListOf<String>()
        var inTable = false
        md.lines().forEachIndexed { i, line ->
            val t = line.trimStart()
            when {
                t.startsWith("| desktop file") -> inTable = true
                !t.startsWith("|") -> inTable = false
                inTable && !line.startsWith("| desktop/") && !Regex("[|\\-: ]+").matches(t.trimEnd()) ->
                    problems += "desktop/COPIES.md line ${i + 1}: a row of the copies table must start with '| desktop/': $line"
            }
        }
        return problems
    }

    /** The problem with this pair, or null when it is in step. */
    fun check(row: Row, root: File): String? {
        val desktop = File(root, row.desktop)
        if (!desktop.isFile) return "desktop/COPIES.md lists ${row.desktop}, which no longer exists: remove or fix the row."
        if (row.cls !in CLASSES) return "desktop/COPIES.md: ${row.desktop} has the class '${row.cls}'; use identical, edits or unrelated."
        if (row.cls == "unrelated") return null
        if (row.cls == "identical" && row.originals.size != 1) {
            return "desktop/COPIES.md: ${row.desktop} is class 'identical' but lists ${row.originals.size} originals (${row.originals.joinToString(", ")}); " +
                "an identical copy has exactly one Android original: list that one, or use class 'edits' for a copy made of several files."
        }
        val originals = row.originals.map { File(root, it) }
        originals.firstOrNull { !it.isFile }?.let {
            return "${row.desktop} is a copy of ${row.originals.joinToString(", ")}, but ${it.relativeTo(root).invariantSeparatorsPath} is gone " +
                "(moved or renamed on Android?). Find the new place, re-check the desktop copy, then fix the row in desktop/COPIES.md " +
                "and run: python3 apps/desktop/tools/copies_hash.py --update ${row.desktop}"
        }
        if (row.cls == "identical") {
            val a = lines(originals.single())
            val d = lines(desktop)
            if (a == d) return null
            val at = (0 until maxOf(a.size, d.size)).first { a.getOrNull(it) != d.getOrNull(it) } + 1
            return "${row.desktop} must be an identical copy of ${row.originals.single()} (only the package line may differ), " +
                "but it differs at line $at (package lines not counted): Android has '${a.getOrNull(at - 1)}', the desktop has '${d.getOrNull(at - 1)}'. " +
                "To resync: copy the Android file over the desktop file and keep the desktop package line. " +
                "If the difference is deliberate, change the class to 'edits' in desktop/COPIES.md."
        }
        val now = hashOf(originals.map { it.readBytes() })
        if (now == row.hash) return null
        return "${row.desktop} is a copy of ${row.originals.joinToString(", ")}; the Android original changed since the desktop copy was " +
            "last checked (recorded hash ${row.hash}, now $now). Look at what changed on Android (git log -p), carry it into the " +
            "desktop copy if it belongs there, then record the new hash: python3 apps/desktop/tools/copies_hash.py --update ${row.desktop}"
    }

    /** Desktop files that have the name of an Android file or a file of shared/src, and hand copies in chat/kelo/app, which COPIES.md does not list. */
    fun unlisted(root: File, rows: List<Row>): List<String> {
        fun kt(dir: String) = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val androidNames = (kt("android/kelo/app/src/main/java") + kt("android/kelo/app/src/test/java") + kt("shared/src")).map { it.name }.toSet()
        val listed = rows.map { it.desktop }.toSet()
        val desktop = kt("desktop/src/main/kotlin") + kt("desktop/src/test/kotlin")
        return desktop.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().mapNotNull { path ->
            val name = path.substringAfterLast('/')
            when {
                path in listed -> null
                path.startsWith("desktop/src/main/kotlin/chat/kelo/app/") ->
                    "$path is a hand copy in chat/kelo/app but is not listed in desktop/COPIES.md: add a row (Android original, class, hash)."
                name in androidNames ->
                    "$path has the name of an Android file but is not listed in desktop/COPIES.md: add a row (class 'unrelated' if it is its own code)."
                else -> null
            }
        }
    }
}
