package chat.kelo.app.agent

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.core.KeloApi
import chat.kelo.app.ui.platform.NetworkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource

/**
 * One browser tab's live view: the connection ([LiveSource]) lives in this view model's scope and closes with it, so the page owns no
 * server calls or coroutines of its own. Also holds how the picture is zoomed and moved (the pointer's zoom buttons and wheel, and the
 * phone's pinch), so it survives a recomposition and a rotation.
 *
 * [source] makes the connection from the scope it should run in (the real one: [liveViewModel]).
 */
class LiveViewModel(source: (CoroutineScope) -> LiveSource) : ViewModel() {
    private val live = source(viewModelScope)

    val frame: StateFlow<LiveFrame?> = live.frame
    val state: StateFlow<LiveState> = live.state
    /** Short lines for the user: "someone else is controlling", "the browser could not do that" */
    val notices = live.notices

    private val _zoom = MutableStateFlow(1f)
    /** The picture's zoom, [LiveGeometry.MIN_ZOOM] to [LiveGeometry.MAX_ZOOM] */
    val zoom: StateFlow<Float> = _zoom.asStateFlow()
    private val _pan = MutableStateFlow(Offset.Zero)
    val pan: StateFlow<Offset> = _pan.asStateFlow()

    /** On screen: the connection opens. */
    fun start() = live.start()

    /** Off screen (another app, the screen off): the connection closes; the tab stays held for a short while. */
    fun stop() = live.stop(giveBack = false)

    suspend fun control(take: Boolean): LiveClient.ControlResult = live.control(take)
    fun send(event: JsonObject) = live.send(event)
    fun type(text: String) = live.type(text)

    /** A pinch or drag: the new zoom and pan (clamped to the view of [viewW] x [viewH]). */
    fun transform(zoomBy: Float, panBy: Offset, viewW: Float, viewH: Float) {
        val z = LiveGeometry.clampZoom(_zoom.value * zoomBy)
        val (x, y) = LiveGeometry.clampPan(_pan.value.x + panBy.x, _pan.value.y + panBy.y, z, viewW, viewH)
        _zoom.value = z
        _pan.value = Offset(x, y)
    }

    /** Zoom to [z] around the centre (the zoom buttons, the wheel). */
    fun zoomTo(z: Float, viewW: Float, viewH: Float) {
        val zoom = LiveGeometry.clampZoom(z)
        val (x, y) = LiveGeometry.clampPan(_pan.value.x, _pan.value.y, zoom, viewW, viewH)
        _zoom.value = zoom
        _pan.value = Offset(x, y)
    }

    /** Back to the whole picture. */
    fun resetZoom() {
        _zoom.value = 1f
        _pan.value = Offset.Zero
    }

    /** Double tap or click: out when zoomed in, else in to [ZOOM_IN]. */
    fun toggleZoom(viewW: Float, viewH: Float) {
        if (_zoom.value > 1.01f) resetZoom() else zoomTo(ZOOM_IN, viewW, viewH)
    }

    override fun onCleared() {
        live.close()
    }

    companion object {
        /** What a double tap zooms in to. */
        const val ZOOM_IN = 2.5f
        /** One tap on a zoom button, and one notch of the wheel with the zoom key held. */
        const val BUTTON_STEP = 1.25f
        const val WHEEL_STEP = 1.15f
    }
}

/** The view model of [tabId]'s live view over the real connection ([LiveClient]); [network] says when pictures should be smaller. */
fun liveViewModel(api: KeloApi, tabId: String, network: NetworkInfo) = LiveViewModel { scope ->
    LiveClient(api, tabId, scope) { LiveQuality.pick(runCatching { network.isMetered() }.getOrDefault(false)) }
}
