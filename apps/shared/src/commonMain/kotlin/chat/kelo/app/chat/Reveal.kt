package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import chat.kelo.app.ui.theme.LocalReduceMotion

/**
 * How many characters of a reply to draw, following [RevealPacing]: a message that is [streaming] when it first shows starts from
 * nothing and catches up with [target] (the characters received so far) frame by frame; once it is behind it keeps going until it
 * has caught up, even after the reply ended. A message that was not streaming when it showed (history), and every message with
 * reduced motion, is drawn whole at once.
 */
@Composable
fun rememberRevealed(target: Int, streaming: Boolean): State<Float> {
    val reduce = LocalReduceMotion.current
    val state = remember { mutableStateOf(RevealPacing.start(target, animate = streaming && !reduce)) }
    val animate by rememberUpdatedState(streaming && !reduce)
    LaunchedEffect(target, reduce) {
        val now = state.value
        if (reduce || (!animate && now.done)) {
            state.value = RevealPacing.State(target.toFloat(), target, 0f)
            return@LaunchedEffect
        }
        state.value = RevealPacing.retarget(now, target)
        var last = withFrameNanos { it }
        while (!state.value.done) {
            withFrameNanos { t ->
                state.value = RevealPacing.advance(state.value, (t - last) / 1_000_000f)
                last = t
            }
        }
    }
    return remember { derivedStateOf { state.value.shown } }
}
