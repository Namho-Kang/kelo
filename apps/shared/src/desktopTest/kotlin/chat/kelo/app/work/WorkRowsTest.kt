package chat.kelo.app.work

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.TestViewModels
import chat.kelo.app.agent.*
import chat.kelo.app.my.MyFakeServer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The work list's rows: their order is the one list the screen draws, and a jump to a section is the index of its row. */
@OptIn(ExperimentalTestApi::class)
class WorkRowsTest {
    private val vms = TestViewModels()
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }
    @After fun stop() { vms.clear(); server?.shutdown(); Dispatchers.resetMain() }

    private fun input(watchers: Loadable<WatcherList>, running: Int): WorkRowsInput {
        val s = MyFakeServer { _, _, _ -> null }.also { server = it }
        val sessions = s.sessions()
        val vm = vms.make { AgentViewModel(sessions) }
        val tasks = (1..running).map { AgentTask(it.toLong(), title = "일 $it", status = AgentTask.STATUS_RUNNING, startedAt = 1_000.0 + it) }
        return WorkRowsInput(
            vm = vm, tasks = Loadable(TaskList(tasks)), workers = Loadable(WorkerList()), perms = Loadable(Permissions()),
            schedules = Loadable(ScheduleList()), browser = Loadable(BrowserTabs()), watchers = watchers,
            followups = Loadable(chat.kelo.app.followup.FollowupList()), followupBusy = emptySet(), entries = Loadable(emptyList()),
            filter = PastFilter.ALL, showPaused = false, onTogglePaused = {}, onFilter = {}, today = LocalDate.of(2026, 11, 13),
            push = {}, onOpenLive = {}, container = { error("not used") },
        )
    }

    private val watching = Loadable(WatcherList(listOf(Watcher(7, name = "제주 항공권 가격", enabled = true))))

    @Test fun theWatchersSectionIsFarBelowTheFoldAndItsIndexIsItsRow() {
        val rows = buildWorkRows(input(watching, running = 40))
        val keys = rows.map { it.key }
        assertTrue(keys.size == keys.toSet().size, "every row has its own key")
        val now = keys.indexOf(SECTION_NOW)
        val watch = keys.indexOf(SECTION_WATCH)
        assertTrue(watch >= now + 40, "after the 40 running tasks: $watch vs $now")
        assertEquals("watch:7", keys[watch + 1])
        assertTrue(keys.indexOf(SECTION_UPCOMING) > watch)
    }

    @Test fun aSectionThatIsStillLoadingHasNoRowYetAndGetsOneWhenItsDataComes() {
        assertEquals(-1, buildWorkRows(input(Loadable(), 3)).indexOfFirst { it.key == SECTION_WATCH })
        assertTrue(buildWorkRows(input(watching, 3)).indexOfFirst { it.key == SECTION_WATCH } > 0)
    }

    @Test fun theListDrawsExactlyTheRowsAndAJumpLandsOnTheSection() = runComposeUiTest {
        val rows = buildWorkRows(input(watching, running = 40)).map { r -> WorkRow(r.key, r.contentType) { Text(r.key) } }
        val index = rows.indexOfFirst { it.key == SECTION_WATCH }
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        val drawn = linkedSetOf<String>()
        setContent {
            state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            WorkRowsList(rows, state, PaddingValues())
            androidx.compose.runtime.LaunchedEffect(Unit) { scope.launch { state.animateScrollToItem(index) } }
        }
        waitUntil(timeoutMillis = 5_000) { state.layoutInfo.visibleItemsInfo.any { it.key == SECTION_WATCH } }
        // what the list composes while it is scrolled through is the rows' keys, in their order
        val visible = state.layoutInfo.visibleItemsInfo
        visible.forEach { drawn += it.key as String }
        assertEquals(rows.map { it.key }.subList(visible.first().index, visible.last().index + 1), visible.map { it.key })
        assertTrue(index == visible.first { it.key == SECTION_WATCH }.index)
    }
}
