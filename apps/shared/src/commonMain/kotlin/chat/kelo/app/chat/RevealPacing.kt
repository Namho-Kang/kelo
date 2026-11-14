package chat.kelo.app.chat

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

/**
 * How fast a reply's text is revealed while it streams in. The turn follower hands over text in chunks (one per poll); instead of
 * each chunk appearing at once, a "revealed characters" counter runs toward the received length.
 *
 * Kelo's rule is a reading speed with a catch-up: text is written out at [READ_PER_MS] characters per millisecond (about 140 a
 * second, a little faster than anyone reads, so it never feels held back), and whenever more has arrived than that speed would
 * show within [CATCH_UP_MS], the speed rises so the backlog is shown within [CATCH_UP_MS] instead. However slow the frames, the
 * counter is never more than [MAX_LAG_MS] behind what has arrived. The newest [FADE_CHARS] characters fade in from [MIN_ALPHA].
 *
 * Pure logic (time is passed in), so it can be tested without a screen.
 */
object RevealPacing {
    /** The steady speed: characters per millisecond */
    const val READ_PER_MS = 0.14f
    /** A backlog bigger than the steady speed covers in this time is shown within it */
    const val CATCH_UP_MS = 700f
    /** The counter is never further behind than this */
    const val MAX_LAG_MS = 1000f
    /** How many characters at the revealed edge fade in */
    const val FADE_CHARS = 6f
    /** The faintest a just-revealed character is drawn */
    const val MIN_ALPHA = 0.15f

    /** [shown] characters revealed (fractional, for a smooth fade) of [target] received; [perMs] is the current speed. */
    data class State(val shown: Float, val target: Int, val perMs: Float) {
        val done: Boolean get() = shown >= target
    }

    /** A message as it first appears: a streaming one starts from nothing, anything else (history, reduced motion) is whole. */
    fun start(target: Int, animate: Boolean): State =
        if (animate) retarget(State(0f, 0, 0f), target) else State(target.toFloat(), target, 0f)

    /** The speed for a backlog of [chars]: the reading speed, or faster when that would take longer than [CATCH_UP_MS]. */
    fun speed(chars: Float): Float = maxOf(READ_PER_MS, chars / CATCH_UP_MS)

    /** How long revealing a backlog of [chars] characters takes from a standstill. */
    fun duration(chars: Float): Float = if (chars <= 0f) 0f else chars / speed(chars)

    /** More text arrived (or the text changed): the speed follows the new backlog. */
    fun retarget(s: State, target: Int): State {
        if (target <= s.shown) return State(target.toFloat(), target, 0f)   // shrank (or already there): no going back over it
        return State(s.shown, target, speed(target - s.shown))
    }

    /** [dtMs] of time passed. Never overshoots the received length, never slows below what keeps the lag within [MAX_LAG_MS]. */
    fun advance(s: State, dtMs: Float): State {
        if (s.done || dtMs <= 0f) return if (s.shown > s.target) s.copy(shown = s.target.toFloat()) else s
        val backlog = s.target - s.shown
        val perMs = maxOf(s.perMs, backlog / MAX_LAG_MS, READ_PER_MS)
        return State((s.shown + perMs * dtMs).coerceAtMost(s.target.toFloat()), s.target, perMs)
    }

    /** The opacity of character [index] when [shown] characters are revealed: the last [FADE_CHARS] fade in, the rest are solid. */
    fun alpha(index: Int, shown: Float): Float = ((shown - index) / FADE_CHARS).coerceIn(MIN_ALPHA, 1f)
}
