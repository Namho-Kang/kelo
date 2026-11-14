package chat.kelo.app.ui.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conversion of apps/shared/strings (Android format) into Compose resources (gradle/strings-to-compose.gradle.kts), on lines made to be
 * hard: apostrophes, \" , quotes that keep spaces, whitespace and newlines folded as aapt folds them, \n \t \u00a0, %%, &amp;.
 * The task runs on src/desktopTest/strings-conversion/in; the files it wrote are compared with expected/ (reviewed by hand once).
 */
class StringsConversionTest {
    private val expected = File(System.getProperty("kelo.strings.expected"))
    private val actual = File(System.getProperty("kelo.strings.actual"))

    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

    @Test fun theSameFilesAreWritten() {
        assertEquals(files(expected), files(actual))
    }

    @Test fun everyFileIsTheExpectedOne() {
        for (path in files(expected)) assertEquals(File(expected, path).readText(), File(actual, path).readText(), path)
    }

    @Test fun languageFoldersFollowTheMapping() {
        val dirs = actual.list()!!.sorted()
        assertEquals(listOf("values", "values-id", "values-in", "values-ko", "values-zh", "values-zh-rCN", "values-zh-rHK", "values-zh-rTW"), dirs)
        // the duplicates are the same file
        assertEquals(File(actual, "values-in/strings.xml").readText(), File(actual, "values-id/strings.xml").readText())
        assertEquals(File(actual, "values-zh/strings.xml").readText(), File(actual, "values-zh-rCN/strings.xml").readText())
        assertEquals(File(actual, "values-zh-rTW/strings.xml").readText(), File(actual, "values-zh-rHK/strings.xml").readText())
    }

    @Test fun untranslatableLinesStayInTheDefaultFolder() {
        assertTrue("not_translatable" in File(actual, "values/strings.xml").readText())
        assertTrue("not_translatable" !in File(actual, "values-ko/strings.xml").readText())
    }
}
