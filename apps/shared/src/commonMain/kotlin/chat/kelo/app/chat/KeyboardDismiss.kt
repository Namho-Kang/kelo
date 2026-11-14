package chat.kelo.app.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import chat.kelo.app.ui.platform.FormFactor
import chat.kelo.app.ui.platform.LocalFormFactor
import kotlin.math.abs

/** When a touch on the message list should close the keyboard (pure, so it can be tested). */
object KeyboardDismissRules {
    /** A finished tap that no child (a button, a link) took, and that did not move further than the touch slop. */
    fun tapCloses(consumedByChild: Boolean, movedX: Float, movedY: Float, touchSlop: Float): Boolean =
        !consumedByChild && abs(movedX) <= touchSlop && abs(movedY) <= touchSlop

    /** Only a phone has a soft keyboard to close. */
    fun applies(formFactor: FormFactor): Boolean = formFactor == FormFactor.Phone
}

/**
 * The message list closes the keyboard like the iPhone's (scrollDismissesKeyboard + a tap): a tap on it that no child handled, or the
 * user dragging it. Watches the touches only after the children had them (PointerEventPass.Final) and never consumes anything, so
 * links, buttons, long-press and selection work as before.
 */
fun Modifier.dismissKeyboardOnTouch(): Modifier = composed {
    if (!KeyboardDismissRules.applies(LocalFormFactor.current)) return@composed this
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var consumed = down.isConsumed
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) consumed = true
                dx = change.position.x - down.position.x
                dy = change.position.y - down.position.y
                if (!change.pressed) break
            }
            if (KeyboardDismissRules.tapCloses(consumed, dx, dy, slop)) {
                keyboard?.hide()
                focus.clearFocus()
            }
        }
    }
}

/** The user dragging the list (not a scroll the app makes) closes the keyboard. */
@Composable
fun DismissKeyboardOnDrag(list: LazyListState) {
    if (!KeyboardDismissRules.applies(LocalFormFactor.current)) return
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                keyboard?.hide()
                focus.clearFocus()
            }
        }
    }
}
