package chat.kelo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import chat.kelo.app.ui.components.FieldWrapScan
import chat.kelo.app.ui.components.FieldWithAction
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.PrimaryButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A field that holds one value never wraps (as on Android): every field of the desktop app is checked from its source
 * (FieldWrapScan, shared with the Android test), and the field component itself is drawn in a narrow column with a long hint and a
 * long value, which must each stay one line; its button goes under it there (FieldWithAction).
 */
class SingleLineFieldsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noOneValueFieldCanWrap() {
        val root = File("src/main/kotlin")
        val problems = FieldWrapScan.scan(listOf(root), emptyList())   // the components are in apps/shared/ (its own test checks them)
        assertTrue("one-value fields that can wrap or are squeezed beside a button:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun lines(text: String): Int {
        val node = compose.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.firstOrNull()?.lineCount ?: -1
    }

    @Test fun aLongHintAndALongValueStayOneLineInANarrowColumn() {
        val hint = "http://192.168.0.10:3001/v1 (OpenAI-compatible server address)"
        var value = ""
        compose.setContent {
            KeloFrameForTest {
                Box(Modifier.width(240.dp)) {
                    FieldWithAction(
                        field = { m -> KeloTextField(value, { value = it }, hint, modifier = m) },
                        action = { PrimaryButton("저장", onClick = {}) },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the hint wrapped", 1, lines(hint))
        // narrow: the button sits under the field, not beside it
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val button = compose.onNode(hasText("저장")).fetchSemanticsNode().boundsInRoot
        assertTrue("the button is beside the field in a narrow column", button.top >= field.bottom)
    }

    @Test fun theButtonStaysBesideAFieldWhereThereIsRoom() {
        compose.setContent {
            KeloFrameForTest {
                Box(Modifier.width(720.dp)) {
                    FieldWithAction(field = { m -> KeloTextField("", {}, "API key", modifier = m) }, action = { PrimaryButton("저장", onClick = {}) })
                }
            }
        }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val button = compose.onNode(hasText("저장")).fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= field.right && button.top < field.bottom)
    }

    @Test fun aLongTypedValueScrollsInsteadOfWrapping() {
        val long = "sk-or-v1-" + "a1b2c3d4e5".repeat(12)
        compose.setContent {
            KeloFrameForTest { Box(Modifier.width(240.dp)) { KeloTextField(long, {}, "key") } }
        }
        compose.waitForIdle()
        assertEquals("the value wrapped", 1, lines(long))
        compose.onRoot().assertExists()
    }
}

@androidx.compose.runtime.Composable
private fun KeloFrameForTest(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(chat.kelo.app.ui.platform.LocalFormFactor provides chat.kelo.app.ui.platform.FormFactor.Desktop) {
        chat.kelo.app.ui.theme.KeloTheme(dark = false) { content() }
    }
}
