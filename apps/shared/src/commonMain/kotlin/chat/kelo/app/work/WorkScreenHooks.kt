package chat.kelo.app.work

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.CardSenders
import chat.kelo.app.chat.ChatPlatform
import chat.kelo.app.ui.platform.LocalBackHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the work screen ([WorkScreen]) takes from the app around it. Every member has a default that suits the desktop (no tab bar, no
 * card on this device, plain page switching), so a window gives only what it has; the phone's shell gives its tab bar, its
 * push-style page transitions and its card sending.
 */
interface WorkScreenHooks {
    /** Tapping the work tab again while it is open: a sub page steps back, else the list scrolls to its top. */
    val reselect: Flow<Unit> get() = emptyFlow()

    /** How much of the bottom the app's own bar covers (the phone's tab bar). */
    val bottomInset: Dp get() = 0.dp

    /** What only the app can do (the phone starts its own work after an approval). */
    val platform: ChatPlatform get() = ChatPlatform.Default

    /** Hides the app's own top and tab bars while [hidden] is true (the phone does while a sub page is open); [key] names the asker. */
    fun hideChrome(key: String, hidden: Boolean) {}

    /** Sends a card after the owner's check (the phone's biometric); the desktop never holds a card. */
    @Composable fun cardSenders(): CardSenders = CardSenders.None

    /**
     * The pages of the work screen: [state] is the stack (the last is shown), [back] the stack one step back (null at the root). The
     * phone slides pages and follows its back gesture; the default just shows the top page and takes the system back.
     */
    @Composable fun PageStack(state: List<String>, back: List<String>?, onBack: () -> Unit, content: @Composable (List<String>) -> Unit) {
        if (back != null) LocalBackHandler.current(onBack)
        content(state)
    }

    /** The sign-in sheet for one keychain item, opened from a follow-up button (the secure form lives per app until its phase). */
    @Composable fun SignInSheet(name: String, onNotify: (String) -> Unit, onClose: () -> Unit) {}

    companion object {
        val Default: WorkScreenHooks = object : WorkScreenHooks {}
    }
}

/** The hooks of the work screen on screen, for the pages below it. */
val LocalWorkHooks = staticCompositionLocalOf { WorkScreenHooks.Default }

/** Sends a card after the owner's check, for the approval cards of the work pages. */
val LocalCardSenders = staticCompositionLocalOf { CardSenders.None }
