package chat.kelo.app.ui.character

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import chat.kelo.app.ui.theme.LocalReduceMotion

/** What Kelo's character is doing; each has its own drawing (apps/android/design/character/kelo_<mood>.svg). */
enum class KeloMood { Idle, Working, Browser, Approval, Thinking, Done, Error, Offline, Sleeping, Listening, Reading }

/**
 * Kelo's character, drawn natively from the SVGs in apps/android/design/character: their shapes in [KeloArt] and their CSS animations
 * as [Anim]s, played by [KeloMotion] with the same durations, delays, keyframes, timing functions, directions and transform
 * origins. A frame clock (read only while drawing, so nothing recomposes per frame, and nothing is allocated per frame) drives it
 * while it is on screen (calm moods at about 30 frames a second); each mood starts from its own first frame, and a change of
 * mood cross-fades in [FADE_MS]. With reduced motion it holds a still picture ([KeloMotion.stillAt]).
 */
@Composable
fun KeloCharacter(mood: KeloMood, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val layer = remember { KeloLayer() }
    val clock = remember { mutableLongStateOf(0L) }
    // the slow, gentle moods redraw at most every [CALM_FRAME_MS] (half the work); the quick ones every frame
    val step = rememberUpdatedState(if (mood in CALM) CALM_FRAME_MS else 0L)
    if (!reduce) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            var last = Long.MIN_VALUE / 2
            while (true) withFrameMillis {
                val t = it - start
                if (t - last >= step.value) { clock.longValue = t; last = t }
            }
        }
    }
    // the mood shown, the one fading out, and when each began (on the clock)
    val shown = remember { MoodTrack(mood) }
    val fade = remember { Animatable(1f) }
    if (shown.now != mood) {
        shown.before = shown.now
        shown.beforeStart = shown.nowStart
        shown.now = mood
        shown.nowStart = clock.longValue
    }
    LaunchedEffect(mood) {
        if (shown.before == null || reduce) { fade.snapTo(1f); shown.before = null; return@LaunchedEffect }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(FADE_MS, easing = LinearEasing))
        shown.before = null
    }
    Canvas(modifier) {
        val f = fade.value
        val before = shown.before
        val t = clock.longValue
        if (reduce) {
            drawKelo(KeloArt.of(mood), KeloMotion.stillAt(mood), 1f, layer)
        } else {
            if (before != null && f < 1f) drawKelo(KeloArt.of(before), t - shown.beforeStart, 1f - f, layer)
            drawKelo(KeloArt.of(shown.now), t - shown.nowStart, if (before != null) f else 1f, layer)
        }
    }
}

/** The character at [timeMs] into its animation, still (frame comparisons, previews). */
@Composable
fun KeloCharacterAt(mood: KeloMood, timeMs: Long, modifier: Modifier = Modifier) {
    val layer = remember { KeloLayer() }
    Canvas(modifier) { drawKelo(KeloArt.of(mood), timeMs, 1f, layer) }
}

/** Moods whose motion is slow (≥ 1.4 s breaths, sways, pulses): drawn at about 30 frames a second. */
private val CALM = setOf(KeloMood.Idle, KeloMood.Sleeping, KeloMood.Offline, KeloMood.Reading)
private const val CALM_FRAME_MS = 32L

/** A change of mood cross-fades this long (ms). */
const val FADE_MS = 150

/** Plain fields: the drawing reads them, and every change comes with a recomposition or a new frame anyway. */
private class MoodTrack(first: KeloMood) {
    var now = first
    var nowStart = 0L
    var before: KeloMood? = null
    var beforeStart = 0L
}

/** The Paints used for opacity layers (a fading group, a mood fading out), reused every frame. */
internal class KeloLayer {
    val mood = Paint()
    val group = Paint()
}

// ---- the art, prepared once ---------------------------------------------------------------------------------------------------

internal sealed interface Item

/** A group of shapes, moved / faded / recoloured together by [anim] (null = not animated). */
internal class G(val anim: Anim?, items: List<Item>) : Item {
    val items: Array<Item> = items.toTypedArray()
}

/** One shape: its path data, fill and stroke (ARGB, null = none), stroke width, cap (butt/round/square) and join (miter/round/bevel). */
internal class S(private val d: String, fill: Long?, stroke: Long?, width: Float, cap: Int, join: Int) : Item {
    /** Built on first draw (keeps [KeloArt] plain data for tests) */
    val path: Path by lazy(LazyThreadSafetyMode.NONE) { PathParser().parsePathString(d).toPath() }
    val hasFill = fill != null
    val fill = Color(fill ?: 0L)
    val hasStroke = stroke != null
    val stroke = Color(stroke ?: 0L)
    val style = Stroke(
        width = width, miter = 4f,
        cap = when (cap) { 1 -> StrokeCap.Round; 2 -> StrokeCap.Square; else -> StrokeCap.Butt },
        join = when (join) { 1 -> StrokeJoin.Round; 2 -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
    )
}

/**
 * One CSS animation: `animation: <durationMs> <easing> infinite [<delayMs>] [alternate]` with its transform-origin ([ox], [oy]) and
 * @keyframes as up to three tracks. Transform: [ops] (0 translate, 1 scale, 2 rotate; applied in order) with two numbers per op
 * per keyframe in [tVals] (rotate's second is unused). Opacity: [oVals]. Fill: [fVals] (ARGB). Each track's [tKeys]/[oKeys]/[fKeys]
 * are its keyframe offsets (0..1, always from 0 to 1). Before its delay the element is at rest (no transform, [baseOpacity]).
 */
internal class Anim(
    val durationMs: Int,
    val delayMs: Int = 0,
    val easing: Int = EASE,
    val alternate: Boolean = false,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val ops: IntArray = IntArray(0),
    val tKeys: FloatArray = FloatArray(0),
    val tVals: FloatArray = FloatArray(0),
    val oKeys: FloatArray = FloatArray(0),
    val oVals: FloatArray = FloatArray(0),
    val baseOpacity: Float = 1f,
    val fKeys: FloatArray = FloatArray(0),
    val fVals: LongArray = LongArray(0),
) {
    /** The transform's numbers for the frame being drawn (filled by [KeloMotion.transform]) */
    val now = FloatArray(ops.size * 2)

    companion object {
        const val LINEAR = 0
        const val EASE = 1
        const val EASE_IN = 2
        const val EASE_OUT = 3
        const val EASE_IN_OUT = 4
    }
}

/** The SVGs' viewBox is 120 × 120. */
private const val VIEW = 120f
/** Where a fading group is composited (a little past the viewBox, for the 10 px white outline) */
private val LAYER_BOUNDS = Rect(-12f, -12f, VIEW + 12f, VIEW + 12f)

private fun DrawScope.drawKelo(art: G, t: Long, alpha: Float, layer: KeloLayer) {
    if (alpha <= 0f) return
    val k = size.minDimension / VIEW
    translate((size.width - VIEW * k) / 2f, (size.height - VIEW * k) / 2f) {
        scale(k, k, pivot = Offset.Zero) {
            if (alpha >= 1f) drawGroup(art, t, layer) else {
                layer.mood.alpha = alpha
                drawContext.canvas.saveLayer(LAYER_BOUNDS, layer.mood)
                drawGroup(art, t, layer)
                drawContext.canvas.restore()
            }
        }
    }
}

private fun DrawScope.drawGroup(g: G, t: Long, layer: KeloLayer) {
    val a = g.anim
    if (a == null) { drawItems(g, t, layer, null); return }
    val p = KeloMotion.progress(a, t)
    val opacity = KeloMotion.opacity(a, p)
    if (opacity <= 0f) return
    val fill = if (a.fKeys.isNotEmpty()) KeloMotion.fill(a, p) else null
    val moved = a.ops.isNotEmpty() && KeloMotion.transform(a, p)
    val canvas = drawContext.canvas
    if (opacity < 1f) {
        layer.group.alpha = opacity
        canvas.saveLayer(LAYER_BOUNDS, layer.group)
    }
    if (moved) {
        withTransform({
            translate(a.ox, a.oy)
            val v = a.now
            for (i in a.ops.indices) {
                val x = v[i * 2]
                val y = v[i * 2 + 1]
                when (a.ops[i]) {
                    0 -> translate(x, y)
                    1 -> scale(x, y, Offset.Zero)
                    else -> rotate(x, Offset.Zero)
                }
            }
            translate(-a.ox, -a.oy)
        }) { drawItems(g, t, layer, fill) }
    } else {
        drawItems(g, t, layer, fill)
    }
    if (opacity < 1f) canvas.restore()
}

private fun DrawScope.drawItems(g: G, t: Long, layer: KeloLayer, fill: Color?) {
    val items = g.items
    for (i in items.indices) {
        when (val it = items[i]) {
            is G -> drawGroup(it, t, layer)
            is S -> {
                if (it.hasFill) drawPath(it.path, fill ?: it.fill)
                if (it.hasStroke) drawPath(it.path, it.stroke, style = it.style)
            }
        }
    }
}

// ---- playing the CSS animations ----------------------------------------------------------------------------------------------------

/**
 * Plays an [Anim] the way a browser does: the timing function applies between each pair of keyframes of a track, "alternate" runs
 * play back on odd iterations, and before the delay the element is at rest.
 */
internal object KeloMotion {
    private val Ease: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    private val EaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)
    private val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    private val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** The still picture with reduced motion (ms into the mood): one that says what the mood is. */
    fun stillAt(mood: KeloMood): Long = when (mood) {
        KeloMood.Working, KeloMood.Browser -> 1_800L   // at the desk (their first frame faces front)
        KeloMood.Thinking -> 750L                        // the first dot up
        KeloMood.Done -> 360L                            // at the top of the jump
        KeloMood.Sleeping -> 1_900L                      // both z's in the air
        KeloMood.Listening -> 600L                       // the first wave bright
        else -> 0L
    }

    /** How far into the current iteration (0..1) at [t] ms, or -1 before the delay. */
    fun progress(a: Anim, t: Long): Float {
        val local = t - a.delayMs
        if (local < 0) return -1f
        val d = a.durationMs.toLong()
        val p = (local % d).toFloat() / d
        return if (a.alternate && (local / d) % 2L == 1L) 1f - p else p
    }

    private fun ease(kind: Int, x: Float): Float = when (kind) {
        Anim.LINEAR -> x
        Anim.EASE -> Ease.transform(x)
        Anim.EASE_IN -> EaseIn.transform(x)
        Anim.EASE_OUT -> EaseOut.transform(x)
        else -> EaseInOut.transform(x)
    }

    /** Fills [Anim.now]; false when at rest (before the delay). */
    fun transform(a: Anim, p: Float): Boolean {
        if (p < 0f) return false
        val n = a.ops.size * 2
        var i = 0
        while (i < a.tKeys.size - 2 && p >= a.tKeys[i + 1]) i++
        val span = a.tKeys[i + 1] - a.tKeys[i]
        val x = if (span <= 0f) 1f else ((p - a.tKeys[i]) / span).coerceIn(0f, 1f)
        val e = ease(a.easing, x)
        for (j in 0 until n) {
            val from = a.tVals[i * n + j]
            val to = a.tVals[(i + 1) * n + j]
            a.now[j] = from + (to - from) * e
        }
        return true
    }

    fun opacity(a: Anim, p: Float): Float {
        if (p < 0f || a.oKeys.isEmpty()) return a.baseOpacity
        var i = 0
        while (i < a.oKeys.size - 2 && p >= a.oKeys[i + 1]) i++
        val span = a.oKeys[i + 1] - a.oKeys[i]
        val x = if (span <= 0f) 1f else ((p - a.oKeys[i]) / span).coerceIn(0f, 1f)
        return a.oVals[i] + (a.oVals[i + 1] - a.oVals[i]) * ease(a.easing, x)
    }

    /** The animated fill, mixed in sRGB as CSS does; null (the shape's own fill) before the delay. */
    fun fill(a: Anim, p: Float): Color? {
        if (p < 0f) return null
        var i = 0
        while (i < a.fKeys.size - 2 && p >= a.fKeys[i + 1]) i++
        val span = a.fKeys[i + 1] - a.fKeys[i]
        val x = if (span <= 0f) 1f else ((p - a.fKeys[i]) / span).coerceIn(0f, 1f)
        val e = ease(a.easing, x)
        val from = a.fVals[i]
        val to = a.fVals[i + 1]
        fun ch(v: Long, shift: Int) = ((v shr shift) and 0xFF).toFloat()
        fun mix(shift: Int) = (ch(from, shift) + (ch(to, shift) - ch(from, shift)) * e) / 255f
        return Color(red = mix(16), green = mix(8), blue = mix(0), alpha = mix(24))
    }
}

/**
 * How far (in the art's 120-unit view box) to move a mood's drawing to the right so the character's body sits in the middle of a
 * round badge: several poses stand left of centre to leave room for a prop (desk, sign, plug, sound waves), which the badge may crop.
 * Measured from the SVGs: the body's horizontal centre (the front-facing pose for the moods that turn).
 */
fun KeloMood.centerShift(): Float = when (this) {
    KeloMood.Idle, KeloMood.Done, KeloMood.Error, KeloMood.Reading -> 0f
    KeloMood.Working, KeloMood.Browser, KeloMood.Offline -> 8f
    KeloMood.Approval, KeloMood.Listening -> 6f
    KeloMood.Sleeping -> 4f
    KeloMood.Thinking -> 2f
}
