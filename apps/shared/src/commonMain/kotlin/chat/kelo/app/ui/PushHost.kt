package chat.kelo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import chat.kelo.app.ui.theme.LocalReduceMotion
import chat.kelo.app.ui.theme.Motion
import chat.kelo.app.ui.platform.LocalPredictiveBack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * True inside a [PushHost]: its predictive back handler steps back, so pages in it (SubPage) do not add a plain BackHandler of
 * their own, which would take the gesture away from it.
 */
val LocalInPushHost = staticCompositionLocalOf { false }

/** Whether going from one page to another is a push (deeper), a pop (back out) or neither. */
enum class PushDirection { PUSH, POP, NONE }

object PushRules {
    fun direction(fromDepth: Int, toDepth: Int): PushDirection = when {
        toDepth > fromDepth -> PushDirection.PUSH
        toDepth < fromDepth -> PushDirection.POP
        else -> PushDirection.NONE
    }
}

/**
 * Pages that stack (a list and its detail, My and its sub pages), with Material 3 shared-axis steps between them. The phone's back
 * gesture is predictive: while the finger moves the step follows it, letting go finishes the step (or cancels it).
 *
 * [state] is what is shown; [depth] orders states (0 = root); [back] is the state one step back, or null at the root (then the
 * gesture is left to whoever is below, e.g. leaving the app). [onBack] makes that step for real.
 */
@Composable
fun <T : Any> PushHost(
    state: T,
    depth: (T) -> Int,
    back: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any = { it },
    content: @Composable (T) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val seek = remember { SeekableTransitionState(state) }
    val transition = rememberTransition(seek, label = "push")
    var gesture by remember { mutableStateOf(false) }
    val currentBack by rememberUpdatedState(back)
    val currentOnBack by rememberUpdatedState(onBack)

    LocalPredictiveBack.current(back != null) { progress ->
        val target = currentBack ?: return@current
        try {
            gesture = true
            progress.collect { p -> seek.seekTo(p.coerceIn(0f, 1f), target) }
            currentOnBack()   // finished: the state change below animates the rest of the way
            gesture = false   // after the step, so the effect sees the new state, not a cancelled gesture
        } catch (e: CancellationException) {
            gesture = false   // let go before the threshold: the effect below slides it back
            throw e
        }
    }
    LaunchedEffect(state, gesture) {
        if (gesture) return@LaunchedEffect
        when {
            seek.currentState != state -> if (reduce) seek.snapTo(state) else seek.animateTo(state)
            seek.targetState != state -> {
                // a cancelled back gesture: run the seek back to 0 from where the finger left it, then settle
                val from = seek.fraction
                if (!reduce && from > 0f) {
                    animate(from, 0f, animationSpec = tween((Motion.EXIT_MS * from).toInt().coerceAtLeast(1))) { v, _ ->
                        launch { if (v > 0f) seek.seekTo(v) }
                    }
                }
                seek.snapTo(state)
            }
        }
    }

    transition.AnimatedContent(
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = { pushTransform(depth(initialState), depth(targetState), reduce, followFinger = gesture) },
    ) { s -> CompositionLocalProvider(LocalInPushHost provides true) { content(s) } }
}

/**
 * Material 3 shared-axis (X) transitions: the new page fades in while moving a short way from the trailing side, the old one fades
 * out moving the other way. [followFinger]: the step is driven by a back gesture, so both move linearly with the finger.
 */
private fun <T> AnimatedContentTransitionScope<T>.pushTransform(from: Int, to: Int, reduce: Boolean, followFinger: Boolean = false): ContentTransform {
    if (reduce) return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    val inEase = if (followFinger) LinearEasing else Motion.EmphasizedDecelerate
    val outEase = if (followFinger) LinearEasing else Motion.EmphasizedAccelerate
    val inMs = if (followFinger) Motion.STANDARD_MS else Motion.ENTER_MS
    val outMs = if (followFinger) Motion.STANDARD_MS else Motion.EXIT_MS
    return when (PushRules.direction(from, to)) {
        PushDirection.PUSH -> ContentTransform(
            slideInHorizontally(tween(inMs, easing = inEase)) { it / SHIFT } + fadeIn(tween(inMs, easing = inEase)),
            slideOutHorizontally(tween(outMs, easing = outEase)) { -it / SHIFT } + fadeOut(tween(outMs, easing = outEase)),
            targetContentZIndex = 1f,
            sizeTransform = null,
        )
        PushDirection.POP -> ContentTransform(
            slideInHorizontally(tween(inMs, easing = inEase)) { -it / SHIFT } + fadeIn(tween(inMs, easing = inEase)),
            slideOutHorizontally(tween(outMs, easing = outEase)) { it / SHIFT } + fadeOut(tween(outMs, easing = outEase)),
            targetContentZIndex = -1f,
            sizeTransform = null,
        )
        PushDirection.NONE -> ContentTransform(fadeIn(tween(Motion.SMALL_MS)), fadeOut(tween(Motion.SMALL_MS)), sizeTransform = null)
    }
}

/** How far a page travels in a shared-axis step: a tenth of the width. */
private const val SHIFT = 10
