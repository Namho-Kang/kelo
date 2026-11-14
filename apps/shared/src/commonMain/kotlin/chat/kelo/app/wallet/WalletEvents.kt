package chat.kelo.app.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import chat.kelo.app.ui.platform.Haptics
import chat.kelo.app.ui.platform.LocalHaptics
import kotlinx.coroutines.flow.Flow

/** What a wallet view model tells its page once: feedback for the owner's finger, and the step a page makes after an action worked. */
sealed interface WalletEvent {
    data object Confirm : WalletEvent
    data object Reject : WalletEvent
    /** The thing the page was for was saved (a card, an address); the page goes back to the list. */
    data object Saved : WalletEvent
    data object Deleted : WalletEvent
    /** Something on the server changed that the wallet list shows: it loads again. */
    data object Changed : WalletEvent
    /** The automatic payments the owner had not seen were marked as seen. */
    data object Seen : WalletEvent
    /** The owner's check should come up now (the lock asks by itself when the page is in front). */
    data object AskCheck : WalletEvent
}

/** Plays the haptics of [events] and hands the others to [onEvent]. */
@Composable
internal fun HandleWalletEvents(events: Flow<WalletEvent>, haptics: Haptics = LocalHaptics.current, onEvent: (WalletEvent) -> Unit = {}) {
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                WalletEvent.Confirm -> haptics.confirm()
                WalletEvent.Reject -> haptics.reject()
                else -> onEvent(e)
            }
        }
    }
}
