package chat.kelo.app.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.string
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/**
 * How long a placeholder sweep takes (the Material 3 loading-indicator rhythm of about 1.2 s), and the shortest time a placeholder
 * stays once shown (Material 3 "long 2", so a fast answer does not flash it).
 */
object SkeletonTiming {
    const val SWEEP_MS = 1200
    const val MIN_SHOWN_MS = 500L

    /** How much longer a placeholder shown since [shownAt] must stay at [now]. */
    fun remaining(shownAt: Long, now: Long, min: Long = MIN_SHOWN_MS): Long = (min - (now - shownAt)).coerceAtLeast(0L)
}

/**
 * True while [loading], and for the rest of [SkeletonTiming.MIN_SHOWN_MS] after it stops, so a placeholder that appeared does
 * not blink away after a few milliseconds.
 */
@Composable
fun rememberSkeletonShown(loading: Boolean): Boolean {
    var shown by remember { mutableStateOf(loading) }
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(loading) {
        if (loading) {
            if (!shown) since = System.currentTimeMillis()
            shown = true
        } else if (shown) {
            delay(SkeletonTiming.remaining(since, System.currentTimeMillis()))
            shown = false
        }
    }
    return shown || loading
}

/** A soft highlight sweeping across every [SkeletonTiming.SWEEP_MS] (still with reduced motion). */
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    val c = Kelo.colors
    val base = c.bubble
    val shine = if (c.dark) c.ink.copy(alpha = 0.06f) else c.card.copy(alpha = 0.65f)
    val reduce = LocalReduceMotion.current
    val clipped = this.clip(shape).background(base)
    if (reduce) return@composed clipped
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(SkeletonTiming.SWEEP_MS, easing = chat.kelo.app.ui.theme.Motion.Standard)), label = "sweep")
    clipped.drawBehind {
        val band = size.width.coerceAtLeast(1f) * 0.4f
        val start = -band + (size.width + 2 * band) * x
        drawRect(Brush.linearGradient(listOf(Color.Transparent, shine, Color.Transparent), start = Offset(start, 0f), end = Offset(start + band, 0f)))
    }
}

/** One grey bar. */
@Composable
fun SkeletonBar(modifier: Modifier = Modifier, width: Dp? = null, fraction: Float = 1f, height: Dp = 14.dp) {
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(fraction))
            .height(height)
            .shimmer(RoundedCornerShape(height / 2)),
    )
}

/** A few lines of text being loaded. */
@Composable
fun SkeletonLines(lines: Int = 3, modifier: Modifier = Modifier) {
    val label = string(Res.string.loading)
    Column(modifier.fillMaxWidth().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i -> SkeletonBar(fraction = if (i == lines - 1) 0.55f else if (i % 2 == 0) 0.92f else 0.78f) }
    }
}

/** A card of rows being loaded (a list group: a title line and a shorter line, a badge on the right). */
@Composable
fun SkeletonRows(rows: Int = 5, modifier: Modifier = Modifier) {
    val c = Kelo.colors
    val label = string(Res.string.loading)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).semantics { contentDescription = label },
    ) {
        repeat(rows) { i ->
            if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBar(fraction = if (i % 2 == 0) 0.45f else 0.6f, height = 14.dp)
                    SkeletonBar(fraction = if (i % 2 == 0) 0.8f else 0.7f, height = 11.dp)
                }
                SkeletonBar(width = 52.dp, height = 20.dp, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
