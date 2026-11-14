package chat.kelo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.awt.KeyboardFocusManager

/**
 * The window coming back to the front: a Kelo window becomes active again (the user signed in in the browser and returned). The
 * desktop's version of the phone's "back in the foreground"; [notifyReturn] is what the window listener calls (tests call it too).
 */
object WindowReturns {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val returns: SharedFlow<Unit> = events

    fun notifyReturn() { events.tryEmit(Unit) }

    init {
        runCatching {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow") { e ->
                if (e.newValue != null && e.oldValue == null) notifyReturn()
            }
        }
    }
}

/**
 * Runs [load] when [key] changes and every time a Kelo window becomes active again, so a page shows the state a sign-in in the
 * browser left behind without being touched. Provided to the shared screens as [chat.kelo.app.ui.platform.LocalReloadOnReturn].
 */
@Composable
fun ReloadOnReturn(key: Any?, load: suspend () -> Unit) {
    val current by rememberUpdatedState(load)
    LaunchedEffect(key) { current() }
    LaunchedEffect(Unit) { WindowReturns.returns.collectLatest { current() } }
}
