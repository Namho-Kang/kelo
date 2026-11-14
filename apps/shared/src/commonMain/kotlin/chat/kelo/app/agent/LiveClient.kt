package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.StringResource
import chat.kelo.app.ui.platform.decodeImage
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The newest picture of the tab, decoded, with what the server said about it. */
class LiveFrame(val image: ImageBitmap, val header: FrameHeader)

/** What the live page shows around the picture. */
data class LiveState(
    val mode: LiveMode = LiveMode.CONNECTING,
    val end: LiveEnd? = null,
    val status: LiveStatus = LiveStatus(),
    /** A control request is on its way */
    val controlBusy: Boolean = false,
)

@Serializable
private data class Ticket(val ticket: String = "")

/**
 * One tab's live view: the WebSocket (`/api/browser/sessions/{id}/live`, after a one-use ticket), or, when it cannot be opened
 * twice in a row, the same frames one request at a time (`GET …/frame?after=&wait=10`). Frames are decoded off the main thread and
 * only the newest is kept: one still being decoded when a newer one comes is dropped. Input goes over the WebSocket when it is
 * open, else as POST …/input. Nothing typed is ever logged or kept: a text event lives only in the one message that carries it.
 *
 * [start] and [stop] follow the page being on screen; [stop] lets go of the tab (the agent goes on) and closes the connection.
 */
/** What the live view's view model takes from a connection (a fake in tests; [LiveClient] is the real one). */
interface LiveSource {
    val frame: StateFlow<LiveFrame?>
    val state: StateFlow<LiveState>
    val notices: SharedFlow<StringResource>
    fun start()
    fun stop(giveBack: Boolean)
    fun close()
    suspend fun control(take: Boolean): LiveClient.ControlResult
    fun send(event: JsonObject)
    fun type(text: String)
}

class LiveClient(
    private val api: KeloApi,
    private val tabId: String,
    private val scope: CoroutineScope,
    private val quality: () -> LiveQuality,
) : LiveSource {
    private val _frame = MutableStateFlow<LiveFrame?>(null)
    override val frame: StateFlow<LiveFrame?> = _frame.asStateFlow()
    private val _state = MutableStateFlow(LiveState())
    override val state: StateFlow<LiveState> = _state.asStateFlow()
    private val _notices = MutableSharedFlow<StringResource>(extraBufferCapacity = 4)
    /** Short lines for the user (string resources): "someone else is controlling", "the browser could not do that" */
    override val notices: SharedFlow<StringResource> = _notices.asSharedFlow()

    private val machine = LiveMachine()
    private val path = "/api/browser/sessions/${KeloApi.segment(tabId)}"

    private var socket: WebSocket? = null
    /** Callbacks of an older socket are ignored */
    @Volatile private var generation = 0
    @Volatile private var pendingHeader: FrameHeader? = null
    @Volatile private var lastSeq = 0L
    private var connectJob: Job? = null
    private var pingJob: Job? = null
    private var pollJob: Job? = null
    private var statusJob: Job? = null
    private var decodeJob: Job? = null
    private var running = false
    private val inputLock = Mutex()
    private val frames = Channel<Pair<FrameHeader, ByteArray>>(Channel.CONFLATED)

    override fun start() {
        if (running) return
        running = true
        if (decodeJob == null) decodeJob = scope.launch(Dispatchers.Default) { decodeLoop() }
        if (machine.mode == LiveMode.ENDED) return
        // coming back from the background: carry on the way it worked before (frames one at a time stays that way)
        run(if (machine.mode == LiveMode.POLLING) LiveStep.Poll() else machine.start())
    }

    /** Off screen: the connection closes (the server stops sending), and a held tab goes back to the agent. */
    override fun stop(giveBack: Boolean) {
        if (!running) return
        running = false
        val held = _state.value.status.youControl
        closeAll()
        val pollWasOn = machine.mode == LiveMode.POLLING
        scope.launch(kotlinx.coroutines.NonCancellable) {
            if (giveBack && held) runCatching { api.post<JsonElement>("$path/control", LiveMessages.control(false)) }
            if (pollWasOn) runCatching { api.delete<JsonElement>("$path/frame") }
        }
        if (giveBack && held) _state.update { it.copy(status = it.status.copy(youControl = false, controller = "agent", controlledBy = null)) }
    }

    /** The page went away for good. */
    override fun close() {
        stop(true)
        decodeJob?.cancel(); decodeJob = null
    }

    private fun closeAll() {
        generation++
        connectJob?.cancel(); pingJob?.cancel(); pollJob?.cancel(); statusJob?.cancel()
        socket?.close(1000, null)
        socket = null
        pendingHeader = null
    }

    private fun run(step: LiveStep) {
        if (!running) return
        when (step) {
            is LiveStep.OpenSocket -> {
                _state.update { it.copy(mode = machine.mode) }
                connectJob?.cancel()
                connectJob = scope.launch {
                    if (step.delayMs > 0) delay(step.delayMs)
                    openSocket()
                }
            }
            is LiveStep.Poll -> {
                closeAll()
                _state.update { it.copy(mode = LiveMode.POLLING) }
                pollJob = scope.launch { if (step.delayMs > 0) delay(step.delayMs); pollLoop() }
                statusJob = scope.launch { statusLoop() }
            }
            is LiveStep.End -> {
                closeAll()
                _state.update { it.copy(mode = LiveMode.ENDED, end = step.reason) }
            }
        }
    }

    // ---- the WebSocket
    private suspend fun openSocket() {
        val ticket = try {
            api.post<Ticket>("$path/live-ticket").ticket
        } catch (e: ApiError) {
            when {
                e.status == 404 -> run(machine.noBrowser())
                e.status == 401 -> {}   // the app signs out
                else -> run(machine.connectFailed())
            }
            return
        }
        val gen = ++generation
        val q = quality()
        // the ticket is enough (the token does not travel twice); through KE2E when the session is pinned (KeloApi.newWebSocket)
        socket = api.newWebSocket("$path/live?ticket=${KeloApi.segment(ticket)}&${q.query()}", Listener(gen))
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        private var opened = false
        private var closed = false

        private fun mine() = gen == generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!mine()) return
            opened = true
            scope.launch {
                if (!mine()) return@launch
                machine.opened()
                _state.update { it.copy(mode = LiveMode.LIVE) }
                webSocket.send(quality().message().toString())
                pingJob?.cancel()
                pingJob = scope.launch {
                    while (isActive && mine()) {
                        delay(LiveMachine.PING_MS)
                        if (mine()) webSocket.send(LiveMessages.ping())
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!mine()) return
            when (val m = LiveMessages.parse(text)) {
                is LiveIn.Frame -> pendingHeader = m.header
                is LiveIn.Status -> _state.update { it.copy(status = m.status) }
                is LiveIn.Ended -> { closed = true; scope.launch { if (mine()) run(machine.ended(m.reason)) } }
                is LiveIn.Error -> onServerError(m.code)
                else -> {}
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!mine()) return
            val h = pendingHeader ?: return
            pendingHeader = null
            frames.trySend(h to bytes.toByteArray())   // conflated: an older frame not yet decoded is dropped
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish(code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(if (opened) 1006 else -1)

        private fun finish(code: Int) {
            if (closed) return
            closed = true
            scope.launch {
                if (!mine() || !running) return@launch
                pingJob?.cancel()
                socket = null
                run(if (code == -1) machine.connectFailed() else machine.closed(code))
            }
        }
    }

    private fun onServerError(code: String) {
        when (code) {
            "not_controller", "held" -> {
                _state.update { it.copy(status = it.status.copy(youControl = false)) }
                _notices.tryEmit(Res.string.live_not_controller)
            }
            "browser" -> _notices.tryEmit(Res.string.live_input_failed)
            else -> {}
        }
    }

    // ---- frames one at a time
    private suspend fun pollLoop() {
        var failures = 0
        while (running) {
            val q = quality()
            val (code, headers, body) = try {
                fetch("$path/frame?after=$lastSeq&wait=10&${q.query()}")
            } catch (e: IOException) {
                Triple(0, null, null)
            }
            failures = if (code == 200 || code == 204) 0 else failures + 1
            if (headers != null) {
                val you = headers("X-You-Control") == "1"
                val controller = headers("X-Controller") ?: "agent"
                _state.update { it.copy(status = it.status.copy(youControl = you, controller = controller)) }
            }
            if (code == 200 && headers != null && body != null) {
                val h = FrameHeader(
                    seq = headers("X-Frame-Seq")?.toLongOrNull() ?: (lastSeq + 1),
                    w = headers("X-Frame-W")?.toIntOrNull() ?: 0,
                    h = headers("X-Frame-H")?.toIntOrNull() ?: 0,
                    url = decode(headers("X-Frame-Url")),
                    title = decode(headers("X-Frame-Title")),
                    bytes = body.size,
                    cssW = headers("X-Frame-Css-W")?.toIntOrNull() ?: 0,
                    cssH = headers("X-Frame-Css-H")?.toIntOrNull() ?: 0,
                )
                lastSeq = h.seq
                frames.trySend(h to body)
            }
            if (code == 401) return
            when (val next = machine.polled(code, failures)) {
                is LiveStep.End -> { run(next); return }
                is LiveStep.Poll -> if (next.delayMs > 0) delay(next.delayMs)
                else -> {}
            }
        }
    }

    /** Without the WebSocket the agent's line and the viewers come from the tab list, every few seconds. */
    private suspend fun statusLoop() {
        while (running) {
            runCatching { api.get<BrowserTabsLite>("/api/browser/sessions?thumbnails=false") }.onSuccess { l ->
                val t = l.items.firstOrNull { it.id == tabId }
                if (t != null) _state.update {
                    it.copy(status = it.status.copy(label = t.statusText, viewers = t.viewers, controlledBy = t.controlledBy,
                        agent = it.status.agent.copy(busy = t.status == "working")))
                }
            }
            delay(5_000)
        }
    }

    private suspend fun fetch(p: String): Triple<Int, ((String) -> String?)?, ByteArray?> = suspendCancellableCoroutine { cont ->
        val call = api.client.newCall(api.requestBuilder(p).get().build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val bytes = if (r.code == 200) r.body?.bytes() else null
                        val hs = r.headers
                        cont.resume(Triple(r.code, { name: String -> hs[name] }, bytes))
                    }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private fun decode(v: String?): String = runCatching { URLDecoder.decode(v.orEmpty(), "UTF-8") }.getOrDefault("")

    private suspend fun decodeLoop() {
        for ((h, bytes) in frames) {
            val bmp = decodeImage(bytes) ?: continue
            if (h.seq > 0) lastSeq = maxOf(lastSeq, h.seq)
            val header = if (h.w > 0 && h.h > 0) h else h.copy(w = bmp.width, h = bmp.height)
            _frame.value = LiveFrame(bmp, header)
        }
    }

    // ---- control and input
    sealed interface ControlResult {
        data object Ok : ControlResult
        data object Held : ControlResult
        data object Gone : ControlResult
        data class Failed(val detail: String) : ControlResult
    }

    override suspend fun control(take: Boolean): ControlResult {
        _state.update { it.copy(controlBusy = true) }
        return try {
            val o = api.post<JsonObject>("$path/control", LiveMessages.control(take))
            _state.update { it.copy(status = LiveMessages.controlAnswer(o, it.status)) }
            ControlResult.Ok
        } catch (e: ApiError) {
            when (e.status) {
                409 -> ControlResult.Held
                404 -> ControlResult.Gone
                else -> ControlResult.Failed(e.detail)
            }
        } finally {
            _state.update { it.copy(controlBusy = false) }
        }
    }

    /** One input event (tap, scroll, key, text, back…): over the WebSocket when it is open, else POST …/input, in order. */
    override fun send(event: JsonObject) {
        val ws = socket
        if (ws != null && machine.mode == LiveMode.LIVE && ws.send(event.toString())) return
        scope.launch {
            inputLock.withLock {
                try {
                    api.post<JsonElement>("$path/input", event)
                } catch (e: ApiError) {
                    when (e.status) {
                        409 -> onServerError("not_controller")
                        422, 502 -> _notices.tryEmit(Res.string.live_input_failed)
                        else -> if (e.isNetwork) _notices.tryEmit(Res.string.live_input_failed)
                    }
                }
            }
        }
    }

    /** Typed text, in pieces the server takes. The text is not kept anywhere after this. */
    override fun type(text: String) = LiveMessages.chunks(text).forEach { send(LiveMessages.text(it)) }
}

/** GET /api/browser/sessions?thumbnails=false, only what the live view's strip needs. */
@Serializable
data class BrowserTabsLite(val items: List<TabLite> = emptyList())

@Serializable
data class TabLite(
    val id: String = "",
    val status: String = "",
    @kotlinx.serialization.SerialName("status_text") val statusText: String = "",
    val viewers: Int = 0,
    @kotlinx.serialization.SerialName("controlled_by") val controlledBy: String? = null,
)

/** The tab a chat reply works in: this phone's own (its device id), or "owner" when signed in with the access key. */
suspend fun ownTabId(api: KeloApi): String = api.get<chat.kelo.app.core.DeviceList>("/api/devices").current?.toString() ?: "owner"
