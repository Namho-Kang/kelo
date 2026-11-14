package chat.kelo.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rows that load in above the first one (goal suggestions after the goals) must not push the top of an untouched list out of view. */
@OptIn(ExperimentalTestApi::class)
class KeepAtTopTest {
    @Test fun rowsArrivingAboveLeaveAnUntouchedListAtItsTop() = runComposeUiTest {
        var rows by mutableStateOf(List(30) { "goal$it" })
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        setContent {
            state = rememberLazyListState()
            KeepAtTopUntilScrolled(state, rows.size)
            LazyColumn(Modifier.height(200.dp), state = state) { items(rows, key = { it }) { Box(Modifier.height(60.dp)) } }
        }
        waitForIdle()
        rows = List(3) { "suggestion$it" } + rows
        waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    @Test fun aListTheUserScrolledIsLeftAlone() = runComposeUiTest {
        var rows by mutableStateOf(List(30) { "goal$it" })
        var go by mutableStateOf(false)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        setContent {
            state = rememberLazyListState()
            KeepAtTopUntilScrolled(state, rows.size)
            LaunchedEffect(go) { if (go) state.animateScrollToItem(10) }
            LazyColumn(Modifier.height(200.dp), state = state) { items(rows, key = { it }) { Box(Modifier.height(60.dp)) } }
        }
        waitForIdle()
        go = true
        waitForIdle()
        val before = state.firstVisibleItemIndex
        assertTrue(before > 0, "the swipe moved the list")
        rows = List(3) { "suggestion$it" } + rows
        waitForIdle()
        assertEquals(before + 3, state.firstVisibleItemIndex)
    }
}
