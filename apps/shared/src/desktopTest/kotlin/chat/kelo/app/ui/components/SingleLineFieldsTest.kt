package chat.kelo.app.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A field that holds one value (an address, a key, a token, a model id, a code, an e-mail, a name, a number) never wraps: it is one
 * line that scrolls sideways, its hint is one line, and in a narrow column its button goes under it (FieldWithAction). Every field
 * of the app is checked here (FieldWrapScan), so a new screen that breaks this fails the unit tests.
 */
class SingleLineFieldsTest {
    private val root = File("src/commonMain/kotlin")

    @Test fun noOneValueFieldCanWrap() {
        // the field components themselves (rule 4) and every shared screen; each app scans its own screens the same way
        val components = File(root, "chat/kelo/app/ui/components/Components.kt")
        val problems = FieldWrapScan.scan(listOf(root), listOf(components))
        assertTrue("one-value fields that can wrap or are squeezed beside a button:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun theScanCatchesAWrappingFieldAndASqueezedButton() {
        val dir = kotlin.io.path.createTempDirectory("fieldscan").toFile()
        try {
            File(dir, "Bad.kt").writeText(
                """
                fun a() {
                    KeloTextField(url, { url = it }, "http://192.168.0.10:3001/v1", singleLine = false)
                    OutlinedTextField(value = v, onValueChange = {}, placeholder = { Text("API key") })
                    Row {
                        KeloTextField(key, { key = it }, "key", modifier = Modifier.weight(1f))
                        PrimaryButton("Save", onClick = {})
                    }
                    KeloTextField(note, { note = it }, stringResource(R.string.goals_note_placeholder), singleLine = false)
                }
                """.trimIndent(),
            )
            val p = FieldWrapScan.scan(listOf(dir), emptyList())
            assertTrue(p.joinToString("\n"), p.size == 3)   // the wrapping URL, the raw field with no line limit, the squeezed row
            assertTrue(p.none { it.line == 8 })              // a note may wrap
        } finally {
            dir.deleteRecursively()
        }
    }
}
