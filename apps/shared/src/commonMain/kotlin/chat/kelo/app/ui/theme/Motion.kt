package chat.kelo.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The app's motion, built on the Material 3 motion tokens (m3.material.io/styles/motion):
 *  - things that arrive (a page, a sheet) use the emphasized-decelerate curve over a "medium 4" duration,
 *  - things that leave use the emphasized-accelerate curve over "short 4",
 *  - in-place changes (a colour, a size, a cross-fade) use the standard curve over "medium 2" or "short 3",
 *  - springs follow the Material 3 expressive spatial tokens.
 * With the system's "remove animations" setting on (the phone) or the user's own choice (the desktop), [LocalReduceMotion] is true and
 * every spec here snaps. Each app root provides it.
 */
object Motion {
    /** Material 3 emphasized decelerate: cubic-bezier(0.05, 0.7, 0.1, 1). */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material 3 emphasized accelerate: cubic-bezier(0.3, 0, 0.8, 0.15). */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Material 3 standard: cubic-bezier(0.2, 0, 0, 1). */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Medium 4: a page or sheet coming in */
    const val ENTER_MS = 400
    /** Short 4: a page or sheet going away */
    const val EXIT_MS = 200
    /** Medium 2: an in-place change of a larger thing (a card growing, a list item moving) */
    const val STANDARD_MS = 300
    /** Short 3: small things (a chip, a colour, a fade) */
    const val SMALL_MS = 150
    /** Short 2: a press going down */
    const val PRESS_MS = 100

    /** How far a pressed control shrinks. */
    const val PRESS_SHRINK = 0.96f

    fun <T> enter(reduce: Boolean = false): FiniteAnimationSpec<T> = if (reduce) snap() else tween(ENTER_MS, easing = EmphasizedDecelerate)
    fun <T> exit(reduce: Boolean = false): FiniteAnimationSpec<T> = if (reduce) snap() else tween(EXIT_MS, easing = EmphasizedAccelerate)
    fun <T> standard(reduce: Boolean = false): FiniteAnimationSpec<T> = if (reduce) snap() else tween(STANDARD_MS, easing = Standard)
    fun <T> small(reduce: Boolean = false): FiniteAnimationSpec<T> = if (reduce) snap() else tween(SMALL_MS, easing = Standard)

    /** Material 3 expressive "default spatial" spring: list items settling into place, new messages. */
    fun <T> rise(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    /** Material 3 expressive "fast spatial" spring with a little overshoot: a checkbox ticked, a bubble lifted. */
    fun <T> bump(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** The duration scale counts as "no animation" when it is 0. */
    fun isOff(durationScale: Float): Boolean = durationScale <= 0f
}

/**
 * Shrinks the element to [Motion.PRESS_SHRINK] while [source] is pressed (pass the same source to its clickable or button): down in
 * [Motion.PRESS_MS], back up with the standard curve. Does nothing with reduced motion.
 */
fun Modifier.pressShrink(source: InteractionSource, scale: Float = Motion.PRESS_SHRINK): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed && !reduce) scale else 1f,
        animationSpec = if (reduce) snap() else if (pressed) tween(Motion.PRESS_MS, easing = Motion.Standard) else tween(Motion.SMALL_MS, easing = Motion.Standard),
        label = "pressShrink",
    )
    graphicsLayer { scaleX = s; scaleY = s }
}

/** [Motion.enter] etc. that follow [LocalReduceMotion]. */
object MotionSpecs {
    @Composable fun <T> enter(): FiniteAnimationSpec<T> = Motion.enter(LocalReduceMotion.current)
    @Composable fun <T> exit(): FiniteAnimationSpec<T> = Motion.exit(LocalReduceMotion.current)
    @Composable fun <T> standard(): FiniteAnimationSpec<T> = Motion.standard(LocalReduceMotion.current)
    @Composable fun <T> small(): FiniteAnimationSpec<T> = Motion.small(LocalReduceMotion.current)
}
