package chat.kelo.app.chat

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The composer takes the focus when the chat opens on the desktop, and never on the phone (it would raise the keyboard). */
@OptIn(ExperimentalTestApi::class)
class ComposerAutoFocusTest {
    private fun focusedAfterOpening(form: FormFactor): Boolean {
        var focused = false
        var focusedNow = false
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalFormFactor provides form) {
                    val requester = remember { FocusRequester() }
                    ComposerAutoFocus(requester)
                    var text by remember { mutableStateOf("") }
                    BasicTextField(text, { text = it }, Modifier.focusRequester(requester).onFocusChanged { focusedNow = it.isFocused })
                }
            }
            waitForIdle()
            focused = focusedNow   // read before the test scene is torn down (which clears the focus)
        }
        return focused
    }

    @Test fun theDesktopComposerIsFocusedOnOpen() = assertTrue(focusedAfterOpening(FormFactor.Desktop))
    @Test fun thePhoneComposerIsNot() = assertFalse(focusedAfterOpening(FormFactor.Phone))
}
