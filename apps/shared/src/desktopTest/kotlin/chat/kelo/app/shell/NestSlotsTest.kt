package chat.kelo.app.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.my.MyFakeServer
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nest's slots with a fake renderer: where entries show, that nothing shows without one, and what the server's answer becomes. */
@OptIn(ExperimentalTestApi::class)
class NestSlotsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private val fake = NestSurfaceView { e, modifier -> Text("nest:${e.key}", modifier) }
    private fun entry(key: String, place: String, shown: Boolean = true) = NestEntry(key = key, place = place, title = key, packageName = "pkg", shown = shown)
    private val state = NestUiState(
        loaded = true, maxTabs = 1,
        byPlace = mapOf(
            NestPlace.HOME_CARD to listOf(entry("home1", NestPlace.HOME_CARD), entry("hidden", NestPlace.HOME_CARD, shown = false)),
            NestPlace.CHAT_CARD to listOf(entry("chat1", NestPlace.CHAT_CARD)),
            NestPlace.TAB to listOf(entry("t1", NestPlace.TAB), entry("t2", NestPlace.TAB)),
        ),
    )

    @Composable
    private fun Slots(view: NestSurfaceView?) = CompositionLocalProvider(LocalNestSurfaceView provides view, LocalNest provides state) {
        Column {
            NestHomeCards(nestEntries(NestPlace.HOME_CARD))
            Text("rows:" + rememberNestChatCards().rows().joinToString(",") { it.id })
            Text("tabs:" + rememberTabs(builtInTabs(withWork = false) { {} }).joinToString(",") { it.id })
        }
    }

    @Test fun withoutARendererNothingFromNestShowsAnywhere() = runComposeUiTest {
        setContent { Slots(null) }
        onNodeWithText("rows:").assertExists()
        onNodeWithText("tabs:chat,goals,connectors,my").assertExists()
        onNodeWithText("nest:home1").assertDoesNotExist()
    }

    @Test fun withARendererEntriesLandWhereTheirPlaceSaysAndOnlyShownOnes() = runComposeUiTest {
        setContent { Slots(fake) }
        onNodeWithText("nest:home1").assertExists()
        onNodeWithText("nest:hidden").assertDoesNotExist()
        onNodeWithText("rows:nest/chat1").assertExists()
        // max_tabs is 1: the second Nest tab is cut; 채팅 and 마이 are still there
        onNodeWithText("tabs:chat,goals,connectors,nest/t1,my").assertExists()
    }

    @BeforeTest fun main() { Dispatchers.setMain(Dispatchers.Default) }
    @AfterTest fun reset() { vms.clear(); Dispatchers.resetMain() }

    @Test fun theServersAnswerBecomesTheEntriesAndAnOlderServerGivesNone() {
        val s = MyFakeServer { m, p, _ ->
            if (m == "GET" && p == "/api/nest/extensions")
                ok("""{"schema":1,"max_tabs":2,"icons":{},"places":{"tab":[{"key":"k","package":"p","surface":"s","place":"tab","title":"T","shown":true}]}}""")
            else null
        }
        try {
            val vm = vms.make { NestViewModel(s.sessions()) }
            until("the answer") { vm.state.value.loaded }
            assertEquals(2, vm.state.value.maxTabs)
            assertEquals(listOf("k"), vm.state.value.entries(NestPlace.TAB).map { it.key })
        } finally { s.shutdown() }
        val old = MyFakeServer { _, _, _ -> fail(404, "none") }
        try {
            val vm = vms.make { NestViewModel(old.sessions()) }
            until("the answer") { vm.state.value.loaded }
            assertTrue(vm.state.value.entries(NestPlace.TAB).isEmpty())
            assertEquals(0, vm.state.value.maxTabs)
        } finally { old.shutdown() }
    }
}
