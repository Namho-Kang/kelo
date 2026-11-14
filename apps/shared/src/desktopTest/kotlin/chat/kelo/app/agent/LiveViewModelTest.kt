package chat.kelo.app.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The live view's view model: it owns the connection and closes it when cleared; it holds the zoom the pointer and pinch change. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LiveViewModelTest {
    private class FakeSource : LiveSource {
        override val frame: StateFlow<LiveFrame?> = MutableStateFlow(null)
        override val state: StateFlow<LiveState> = MutableStateFlow(LiveState())
        override val notices: SharedFlow<StringResource> = MutableSharedFlow()
        var starts = 0; var stops = 0; var closes = 0
        val sent = mutableListOf<JsonObject>()
        override fun start() { starts++ }
        override fun stop(giveBack: Boolean) { stops++ }
        override fun close() { closes++ }
        override suspend fun control(take: Boolean): LiveClient.ControlResult = LiveClient.ControlResult.Ok
        override fun send(event: JsonObject) { sent += event }
        override fun type(text: String) {}
    }

    private val sources = mutableMapOf<String, FakeSource>()
    private val factory = { tab: String -> viewModelFactory { initializer { LiveViewModel { sources.getOrPut(tab) { FakeSource() } } } } }

    @Test fun theConnectionClosesWhenTheViewModelIsCleared() {
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, factory("a"))["live-a", LiveViewModel::class]
        vm.start(); vm.stop()
        assertEquals(1, sources["a"]!!.starts); assertEquals(1, sources["a"]!!.stops)
        assertEquals(0, sources["a"]!!.closes)
        store.clear()
        assertEquals(1, sources["a"]!!.closes)
    }

    @Test fun aNewTabGetsANewViewModelAndTheSameTabKeepsItsOwn() {
        val store = ViewModelStore()
        val provider = ViewModelProvider.create(store, viewModelFactory {
            initializer { LiveViewModel { FakeSource() } }
        })
        val a = provider["live-a", LiveViewModel::class]
        val b = provider["live-b", LiveViewModel::class]
        assertNotSame(a, b)
        assertSame(a, provider["live-a", LiveViewModel::class])
    }

    @Test fun zoomStaysBetweenOneAndFiveAndResets() {
        val vm = LiveViewModel { FakeSource() }
        vm.zoomTo(10f, 800f, 600f)
        assertEquals(LiveGeometry.MAX_ZOOM, vm.zoom.value)
        vm.zoomTo(0.2f, 800f, 600f)
        assertEquals(1f, vm.zoom.value)
        vm.zoomTo(2f, 800f, 600f)
        vm.transform(1f, Offset(5000f, 5000f), 800f, 600f)
        assertTrue(vm.pan.value.x <= 800f && vm.pan.value.y <= 600f)
        vm.resetZoom()
        assertEquals(1f, vm.zoom.value); assertEquals(Offset.Zero, vm.pan.value)
    }

    @Test fun aDoubleTapZoomsInThenOutAndAButtonStepIsAQuarter() {
        val vm = LiveViewModel { FakeSource() }
        vm.toggleZoom(800f, 600f)
        assertEquals(LiveViewModel.ZOOM_IN, vm.zoom.value)
        vm.toggleZoom(800f, 600f)
        assertEquals(1f, vm.zoom.value)
        vm.zoomTo(1f * LiveViewModel.BUTTON_STEP, 800f, 600f)
        assertEquals(1.25f, vm.zoom.value)
    }

    @Test fun leavingTheLivePageClearsItsViewModelAndOpeningItAgainMakesANewOne() = androidx.compose.ui.test.runComposeUiTest {
        val made = mutableListOf<FakeSource>()
        var open by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            if (open) {
                val owner = chat.kelo.app.ui.platform.rememberPageViewModelStoreOwner("tab")
                androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner) {
                    androidx.lifecycle.viewmodel.compose.viewModel(key = "live-tab") { LiveViewModel { FakeSource().also { made += it } } }
                }
            }
        }
        waitForIdle()
        assertEquals(1, made.size); assertEquals(0, made[0].closes)
        open = false
        waitForIdle()
        assertEquals(1, made[0].closes)   // the page left: its view model was cleared, the source closed
        open = true
        waitForIdle()
        assertEquals(2, made.size)        // opened again: a new one
        assertEquals(0, made[1].closes)
    }
}
