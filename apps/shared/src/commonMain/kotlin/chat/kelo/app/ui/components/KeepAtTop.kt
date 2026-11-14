package chat.kelo.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * A list that opens at its top stays there while its content arrives. A lazy list keeps the row that was first on screen, so rows
 * that load in above it (a goal suggestion that comes after the goals, a new approval on the work page) would push the top of the
 * page out of view, under the floating header. Once the person scrolls, the list is theirs and this does nothing.
 * [contentKey] changes whenever rows may have been added or removed (for example their count).
 */
@Composable
fun KeepAtTopUntilScrolled(state: LazyListState, contentKey: Any?) {
    var scrolled by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) { snapshotFlow { state.isScrollInProgress }.first { it }; scrolled = true }
    LaunchedEffect(contentKey, scrolled) { if (!scrolled) state.scrollToItem(0) }
}
