package chat.kelo.app.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The live view of a browser tab (server.py "LIVE VIEW API"), the parts that need no Android: where the picture sits on screen and
 * how a touch becomes a point on the page, when to reconnect and when to fall back to fetching one picture at a time, and the
 * messages going both ways. The screen itself is in LivePage.kt, the connection in LiveClient.kt.
 */

/** Where a frame of [frameW] x [frameH] is drawn inside a view (ContentScale.Fit: the whole picture, centred, letterboxed). */
data class FitBox(val scale: Float, val left: Float, val top: Float, val width: Float, val height: Float)

/** A point on the frame, in its own pixels (what tap / long_press send). */
data class FramePoint(val x: Int, val y: Int)

object LiveGeometry {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f

    /** The picture's box in the view; null while either size is unknown. */
    fun fit(viewW: Float, viewH: Float, frameW: Int, frameH: Int): FitBox? {
        if (viewW <= 0f || viewH <= 0f || frameW <= 0 || frameH <= 0) return null
        val s = min(viewW / frameW, viewH / frameH)
        val w = frameW * s
        val h = frameH * s
        return FitBox(s, (viewW - w) / 2f, (viewH - h) / 2f, w, h)
    }

    /**
     * A touch at ([x], [y]) in view pixels, as a frame pixel. The view is zoomed by [zoom] around its centre and then moved by
     * ([panX], [panY]) (what graphicsLayer does with scale and translation); null when the touch is outside the picture (the
     * letterbox bars), so a tap there sends nothing.
     */
    fun toFrame(x: Float, y: Float, viewW: Float, viewH: Float, frameW: Int, frameH: Int, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): FramePoint? {
        val box = fit(viewW, viewH, frameW, frameH) ?: return null
        val z = zoom.coerceAtLeast(0.01f)
        val cx = viewW / 2f
        val cy = viewH / 2f
        // undo the zoom and pan: the point of the un-zoomed view under the finger
        val ux = cx + (x - panX - cx) / z
        val uy = cy + (y - panY - cy) / z
        val fx = (ux - box.left) / box.scale
        val fy = (uy - box.top) / box.scale
        if (fx < 0f || fy < 0f || fx > frameW || fy > frameH) return null
        return FramePoint(fx.toInt().coerceIn(0, frameW - 1), fy.toInt().coerceIn(0, frameH - 1))
    }

    /**
     * A finger drag of ([dx], [dy]) view pixels as a scroll of the page, in frame pixels. Natural scrolling: moving the finger up
     * scrolls the page down, so the signs flip.
     */
    fun scrollDelta(dx: Float, dy: Float, viewW: Float, viewH: Float, frameW: Int, frameH: Int, zoom: Float = 1f): FramePoint {
        val box = fit(viewW, viewH, frameW, frameH) ?: return FramePoint(0, 0)
        val k = box.scale * zoom.coerceAtLeast(0.01f)
        return FramePoint((-dx / k).roundToInt(), (-dy / k).roundToInt())
    }

    /** The pan kept inside the zoomed picture: at 1x no pan, at 2x up to half the view each way. */
    fun clampPan(panX: Float, panY: Float, zoom: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        val maxX = (zoom - 1f).coerceAtLeast(0f) * viewW / 2f
        val maxY = (zoom - 1f).coerceAtLeast(0f) * viewH / 2f
        return (panX.coerceIn(-maxX, maxX) + 0f) to (panY.coerceIn(-maxY, maxY) + 0f)   // + 0f: never -0.0
    }

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
}

/** How sharp and how often: 720 px / 55 / 10 fps on Wi-Fi, less on a metered network (mobile data). */
data class LiveQuality(val maxWidth: Int, val quality: Int, val fps: Int) {
    companion object {
        val WIFI = LiveQuality(720, 55, 10)
        val METERED = LiveQuality(540, 45, 5)
        fun pick(metered: Boolean): LiveQuality = if (metered) METERED else WIFI
    }

    fun message(): JsonObject = buildJsonObject {
        put("type", "quality"); put("max_width", maxWidth); put("quality", quality); put("fps", fps)
    }

    fun query(): String = "max_width=$maxWidth&quality=$quality&fps=$fps"
}

/** {"type":"frame",...}: the JPEG that follows it is [bytes] long, [w] x [h] pixels. */
@Serializable
data class FrameHeader(
    val seq: Long = 0,
    val w: Int = 0,
    val h: Int = 0,
    val ts: Double = 0.0,
    val url: String = "",
    val title: String = "",
    val bytes: Int = 0,
    @SerialName("css_w") val cssW: Int = 0,
    @SerialName("css_h") val cssH: Int = 0,
)

@Serializable
data class LiveAgent(
    val busy: Boolean = false,
    val status: String? = null,
    @SerialName("task_id") val taskId: Long? = null,
    val title: String? = null,
    @SerialName("waiting_approval") val waitingApproval: Boolean = false,
)

/** {"type":"status"}: who controls the tab, what the agent is doing in it, how many are watching. */
@Serializable
data class LiveStatus(
    val controller: String = "agent",
    @SerialName("controlled_by") val controlledBy: String? = null,
    @SerialName("you_control") val youControl: Boolean = false,
    val label: String = "",
    val agent: LiveAgent = LiveAgent(),
    val viewers: Int = 0,
) {
    /** Another phone (or the owner's app) holds the tab */
    val someoneElse: Boolean get() = controller == "user" && !youControl
}

/** One text message from the server, sorted by type. */
sealed interface LiveIn {
    data class Frame(val header: FrameHeader) : LiveIn
    data class Status(val status: LiveStatus) : LiveIn
    data class Ended(val reason: String) : LiveIn
    data class Error(val code: String) : LiveIn
    data object Pong : LiveIn
    data object Other : LiveIn
}

/** The messages of the live view, both ways. Typed text is never part of anything but the one message that carries it. */
object LiveMessages {
    private val json = chat.kelo.app.core.KeloApi.json

    fun parse(text: String): LiveIn {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return LiveIn.Other
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return runCatching {
            when (s("type")) {
                "frame" -> LiveIn.Frame(json.decodeFromJsonElement(FrameHeader.serializer(), o))
                "status" -> LiveIn.Status(json.decodeFromJsonElement(LiveStatus.serializer(), o))
                "ended" -> LiveIn.Ended(s("reason"))
                "error" -> LiveIn.Error(s("code"))
                "pong" -> LiveIn.Pong
                else -> LiveIn.Other
            }
        }.getOrDefault(LiveIn.Other)
    }

    fun ping(): String = """{"type":"ping"}"""

    fun tap(p: FramePoint, frameW: Int, frameH: Int): JsonObject = point("tap", p, frameW, frameH)

    fun longPress(p: FramePoint, frameW: Int, frameH: Int): JsonObject = point("long_press", p, frameW, frameH)

    fun scroll(d: FramePoint, at: FramePoint?, frameW: Int, frameH: Int): JsonObject = buildJsonObject {
        put("type", "scroll"); put("dx", d.x); put("dy", d.y)
        if (at != null) { put("x", at.x); put("y", at.y) }
        put("frame_w", frameW); put("frame_h", frameH)
    }

    fun text(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

    fun key(name: String): JsonObject = buildJsonObject { put("type", "key"); put("key", name) }

    /** Back | forward | reload */
    fun nav(kind: String): JsonObject = buildJsonObject { put("type", kind) }

    fun control(take: Boolean): JsonObject = buildJsonObject { put("take", take) }

    private fun point(type: String, p: FramePoint, frameW: Int, frameH: Int) = buildJsonObject {
        put("type", type); put("x", p.x); put("y", p.y); put("frame_w", frameW); put("frame_h", frameH)
    }

    /** The control answer (POST …/control) or the X-You-Control / X-Controller headers, as a [LiveStatus] update. */
    fun controlAnswer(o: JsonObject?, before: LiveStatus): LiveStatus {
        if (o == null) return before
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return before.copy(
            controller = s("controller") ?: before.controller,
            controlledBy = s("controlled_by"),
            youControl = (o["you_control"] as? JsonPrimitive)?.booleanOrNull ?: before.youControl,
        )
    }

    /** Text longer than the server takes in one message is sent in pieces of at most [max] characters. */
    fun chunks(text: String, max: Int = MAX_TEXT): List<String> = if (text.isEmpty()) emptyList() else text.chunked(max)

    const val MAX_TEXT = 2000

    /** The keys the server knows, with what the buttons show. */
    val KEYS = listOf("Enter", "Backspace", "Tab", "Escape", "ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight")
}

/** Where the live view is. */
enum class LiveMode {
    /** Asking for a ticket / opening the WebSocket */
    CONNECTING,
    /** Frames come over the WebSocket */
    LIVE,
    /** Frames come one at a time (GET …/frame?after=&wait=10): the WebSocket could not be opened */
    POLLING,
    /** Waiting before trying again (the network dropped, too many attempts) */
    WAITING,
    /** The tab is gone: nothing more will come */
    ENDED,
}

/** Why there is nothing more to show. */
enum class LiveEnd { TAB_CLOSED, NO_TAB, NO_BROWSER }

/** What the connection should do next. */
sealed interface LiveStep {
    /** Open the WebSocket (with a new ticket when [freshTicket]) after [delayMs] */
    data class OpenSocket(val freshTicket: Boolean = true, val delayMs: Long = 0) : LiveStep
    /** Fetch frames one at a time after [delayMs] */
    data class Poll(val delayMs: Long = 0) : LiveStep
    data class End(val reason: LiveEnd) : LiveStep
}

/**
 * The reconnect rules, apart from the network so they can be tested:
 * - the WebSocket fails to connect twice in a row (a proxy without WebSocket support) → fetch frames one at a time from then on;
 * - 4401 (the ticket was used or expired) → one new ticket and one more try, then frames one at a time (they use the bearer token);
 * - 4404 or "ended" → the tab is gone; 4408 (silent for 60 s, e.g. the phone slept) → reconnect at once;
 * - 4429 (too many failed attempts) → wait half a minute;
 * - a dropped connection that had worked → reconnect, waiting a little longer each time (at most 15 s).
 */
class LiveMachine {
    var mode: LiveMode = LiveMode.CONNECTING
        private set
    var end: LiveEnd? = null
        private set
    /** The WebSocket attempts in a row that never opened */
    var connectFailures = 0
        private set
    private var ticketRetried = false
    private var drops = 0
    private var everOpened = false

    fun start(): LiveStep { mode = LiveMode.CONNECTING; return LiveStep.OpenSocket(freshTicket = true) }

    fun opened() {
        mode = LiveMode.LIVE
        connectFailures = 0
        everOpened = true
        drops = 0
        ticketRetried = false   // a later 4401 (a ticket that expired while away) gets its one retry again
    }

    /** The upgrade was refused, the server could not be reached, or no ticket could be had. */
    fun connectFailed(): LiveStep {
        if (mode == LiveMode.ENDED) return LiveStep.End(end ?: LiveEnd.TAB_CLOSED)
        connectFailures++
        return if (connectFailures >= FALLBACK_AFTER) poll()
        else { mode = LiveMode.WAITING; LiveStep.OpenSocket(freshTicket = true, delayMs = RETRY_MS) }
    }

    /** The server said {"type":"ended"} before closing. */
    fun ended(reason: String): LiveStep = finish(if (reason == "no_tab") LiveEnd.NO_TAB else LiveEnd.TAB_CLOSED)

    /** The WebSocket closed (or failed after it had opened: [code] 1006). */
    fun closed(code: Int): LiveStep {
        if (mode == LiveMode.ENDED) return LiveStep.End(end ?: LiveEnd.TAB_CLOSED)
        val wasOpen = mode == LiveMode.LIVE
        return when (code) {
            4401 -> if (!ticketRetried) {
                ticketRetried = true
                mode = LiveMode.CONNECTING
                LiveStep.OpenSocket(freshTicket = true)
            } else poll()
            4404 -> finish(LiveEnd.NO_TAB)
            4408 -> { mode = LiveMode.CONNECTING; LiveStep.OpenSocket(freshTicket = true) }
            4429 -> { mode = LiveMode.WAITING; LiveStep.OpenSocket(freshTicket = true, delayMs = TOO_MANY_MS) }
            else -> if (!wasOpen && !everOpened) connectFailed() else {
                drops++
                mode = LiveMode.WAITING
                LiveStep.OpenSocket(freshTicket = true, delayMs = backoff(drops))
            }
        }
    }

    /** One frame request answered: [status] 200 / 204 go on, 404 is the end, anything else waits and tries again. */
    fun polled(status: Int, failures: Int): LiveStep = when {
        mode == LiveMode.ENDED -> LiveStep.End(end ?: LiveEnd.TAB_CLOSED)
        status == 200 || status == 204 -> { mode = LiveMode.POLLING; LiveStep.Poll() }
        status == 404 -> finish(LiveEnd.NO_TAB)
        else -> { mode = LiveMode.POLLING; LiveStep.Poll(delayMs = backoff(failures)) }
    }

    /** The server has no browser at all (404 on the ticket): nothing live to show. */
    fun noBrowser(): LiveStep = finish(LiveEnd.NO_BROWSER)

    private fun poll(): LiveStep { mode = LiveMode.POLLING; return LiveStep.Poll() }

    private fun finish(reason: LiveEnd): LiveStep { mode = LiveMode.ENDED; end = reason; return LiveStep.End(reason) }

    companion object {
        const val FALLBACK_AFTER = 2
        const val RETRY_MS = 1_000L
        const val TOO_MANY_MS = 30_000L
        const val PING_MS = 20_000L

        /** 1 s, 2 s, 4 s, 8 s, then 15 s. */
        fun backoff(n: Int): Long = if (n <= 0) 0 else minOf(15_000L, 1_000L shl (n - 1).coerceAtMost(4))
    }
}

/** When the live view takes its tab over by itself (it was opened to solve a human check). */
object LiveRules {
    /** Once only, when asked to, after the first picture came, while nobody holds the tab and no take-over is on its way. */
    fun autoTake(asked: Boolean, done: Boolean, hasFrame: Boolean, controlling: Boolean, someoneElse: Boolean, busy: Boolean): Boolean =
        asked && !done && hasFrame && !controlling && !someoneElse && !busy
}
