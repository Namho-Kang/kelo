package chat.kelo.app.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.AddEntry
import chat.kelo.app.ui.components.KeloModal
import chat.kelo.app.ui.icons.KeloIcons
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.ui.platform.keloViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Kelo Nest in the app: the extensions the server offers for the app's surfaces (docs/nest-ui.md Part 1). The app only holds the
 * slots; what fills them is drawn by one [NestSurfaceView] the app root provides (a native renderer or a web view, not decided).
 * While there is none (always, for now) nothing from Nest shows anywhere: no tab, no My section, no home card, no "+" row.
 */

/** One extension placed on one of the app's surfaces (an item of GET /api/nest/extensions `places.<place>`). */
@Serializable
data class NestEntry(
    val key: String = "",
    @SerialName("package") val packageId: String = "",
    val surface: String = "",
    val place: String = "",
    val title: String = "",
    val icon: String = "",
    @SerialName("package_name") val packageName: String = "",
    val author: String = "",
    @SerialName("data_url") val dataUrl: String = "",
    val shown: Boolean = true,
)

/** GET /api/nest/extensions: [places] by place name ([NestPlace]); [maxTabs] is how many Nest tabs the bar may hold. */
@Serializable
data class NestExtensions(
    val schema: Int = 0,
    @SerialName("max_tabs") val maxTabs: Int = 0,
    val icons: JsonElement? = null,
    val places: Map<String, List<NestEntry>> = emptyMap(),
)

/** The places an extension can be put (the server's `place` names). */
object NestPlace {
    const val TAB = "tab"
    const val MY_SECTION = "my_section"
    const val HOME_CARD = "home_card"
    const val CHAT_CARD = "chat_card"
}

/** Draws one extension's surface. Provided by the app root through [LocalNestSurfaceView]; the screens never draw a Nest entry themselves. */
fun interface NestSurfaceView {
    @Composable fun Show(entry: NestEntry, modifier: Modifier)
}

/** The renderer of Nest surfaces; null = there is none, and then no Nest entry shows anywhere. */
val LocalNestSurfaceView = staticCompositionLocalOf<NestSurfaceView?> { null }

/** What the server offers, as the screens read it ([NestUiState.entries] are only the ones marked `shown`). */
data class NestUiState(val loaded: Boolean = false, val maxTabs: Int = 0, val byPlace: Map<String, List<NestEntry>> = emptyMap()) {
    fun entries(place: String): List<NestEntry> = byPlace[place].orEmpty().filter { it.shown }

    companion object {
        /** No extensions (an older server, a failed load, or before the answer). */
        val Empty = NestUiState()
    }
}

/** Fetches the extensions once per session and again on [reload]. A 404 (an older server) or an empty answer is an empty list. */
class NestViewModel(private val sessions: Sessions) : ViewModel() {
    private val _state = MutableStateFlow(NestUiState.Empty)
    val state: StateFlow<NestUiState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _state.value = try {
                val x = sessions.requireApi().get<NestExtensions>("/api/nest/extensions")
                NestUiState(loaded = true, maxTabs = x.maxTabs, byPlace = x.places)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                NestUiState(loaded = true)
            } catch (e: Exception) {
                NestUiState(loaded = true)   // an answer this app cannot read: nothing from Nest
            }
        }
    }
}

/** What the screens read; provided by [ProvideNest]. */
val LocalNest = compositionLocalOf { NestUiState.Empty }

/** Fetches Nest's extensions for the signed-in session and makes them available to everything inside. */
@Composable
fun ProvideNest(sessions: Sessions, content: @Composable () -> Unit) {
    val vm = keloViewModel("nest") { NestViewModel(sessions) }
    val state = vm.state.collectAsStateWithLifecycle().value
    CompositionLocalProvider(LocalNest provides state, content = content)
}

/** The entries of [place] to show: empty while the app has no [NestSurfaceView]. */
@Composable
fun nestEntries(place: String): List<NestEntry> {
    if (LocalNestSurfaceView.current == null) return emptyList()
    val nest = LocalNest.current
    return remember(nest, place) { nest.entries(place) }
}

/** The Nest home cards in the chat's empty / briefing area, each drawn by the app's [NestSurfaceView]. */
@Composable
fun NestHomeCards(entries: List<NestEntry>, modifier: Modifier = Modifier) {
    val view = LocalNestSurfaceView.current ?: return
    if (entries.isEmpty()) return
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        entries.forEach { view.Show(it, Modifier.fillMaxWidth()) }
    }
}

/**
 * The "+" sheet's rows for the Nest `chat_card` entries, and the sheet one opens over the chat (never inserted into the conversation).
 * Empty and drawing nothing while the app has no [NestSurfaceView]. Call [Sheet] where the chat's other sheets are.
 */
class NestChatCards(private val entries: List<NestEntry>, private val open: MutableState<NestEntry?>) {
    /** The rows to add after the app's own "+" entries. */
    fun rows(): List<AddEntry> = entries.map { e ->
        AddEntry("nest/${e.key}", KeloIcons.Spark, e.title.ifBlank { e.packageName }, e.packageName) { open.value = e }
    }

    @Composable
    fun Sheet() {
        val view = LocalNestSurfaceView.current ?: return
        val e = open.value ?: return
        KeloModal(onDismiss = { open.value = null }) { view.Show(e, Modifier.fillMaxWidth()) }
    }
}

@Composable
fun rememberNestChatCards(): NestChatCards {
    val entries = nestEntries(NestPlace.CHAT_CARD)
    val open = remember { mutableStateOf<NestEntry?>(null) }
    return remember(entries) { NestChatCards(entries, open) }
}
