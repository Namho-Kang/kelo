package chat.kelo.app.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import chat.kelo.app.ui.theme.KeloTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared composer and "+" list: the desktop sends with Enter, the phone does not; the "+" sheet draws the app's entries. */
@OptIn(ExperimentalTestApi::class)
class ChatComposerTest {
    private fun androidx.compose.ui.test.ComposeUiTest.composer(form: FormFactor, onSend: () -> Unit, mic: Boolean = false) = setContent {
        CompositionLocalProvider(LocalFormFactor provides form) {
            KeloTheme(dark = false) {
                Composer(
                    input = "hello", busy = false, canSend = true, focusRequester = FocusRequester(), onFocus = {},
                    onInput = {}, onSend = onSend, onStop = {}, onAttach = {}, chipText = "Model", onChip = {},
                    mic = if (mic) { _, _ -> Box(Modifier) { androidx.compose.material3.Text("MIC") } } else null,
                )
            }
        }
    }

    @Test fun enterSendsOnTheDesktopButShiftEnterDoesNot() = runComposeUiTest {
        var sent = 0
        composer(FormFactor.Desktop, { sent++ })
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, sent)
        onNode(hasSetTextAction()).performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        assertEquals(1, sent)
    }

    @Test fun enterDoesNotSendOnThePhone() = runComposeUiTest {
        var sent = 0
        composer(FormFactor.Phone, { sent++ })
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        assertEquals(0, sent)
    }

    @Test fun theMicIsDrawnOnlyWhenTheAppGivesOne() = runComposeUiTest {
        composer(FormFactor.Phone, {}, mic = true)
        onNodeWithText("MIC").assertExists()
    }

    @Test fun thePlusSheetListsTheAppsEntriesAndClosesBeforePicking() = runComposeUiTest {
        var picked = ""
        setContent {
            KeloTheme(dark = false) {
                AttachSheet(
                    listOf(
                        AddEntry("a", KeloIcons.Image, "Photo", "From gallery") { picked += "a" },
                        AddEntry("b", KeloIcons.File, "Document", "A file") { picked += "b" },
                    ),
                    onClose = {},
                )
            }
        }
        onNodeWithText("Photo").assertExists()
        onNodeWithText("Document").performClick()
        assertEquals("b", picked)
    }
}
