package chat.kelo.app.ui.platform

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the app's bars give a page of a tab (the phone's tab bar under it, its header over it): how much of the bottom the tab bar
 * covers, the taps on the tab that is already open, and a way to hide the bars at once (the same frame as a detail starts sliding in).
 * The default is a window without bars.
 */
@Immutable
class TabPageFrame(
    val bottomInset: Dp = 0.dp,
    val reselect: Flow<Unit> = emptyFlow(),
    val hideChrome: (key: String, hidden: Boolean) -> Unit = { _, _ -> },
) {
    companion object {
        val None = TabPageFrame()
    }
}
