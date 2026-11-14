package chat.kelo.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * What the shared screens need from the app around them. Each app root provides these (Android: MainActivity, desktop: KeloFrame);
 * the defaults are the phone's, with no feedback, so a test or a preview needs nothing.
 */

/** The kind of window the UI is drawn in. Shared components keep both looks and pick by this (a size, a hover, a layout). */
enum class FormFactor { Phone, Desktop }

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }

/** One value per form factor: where a component differs in size or spacing, the only place that says which is which. */
@Composable
@ReadOnlyComposable
fun <T> formFactor(phone: T, desktop: T): T = when (LocalFormFactor.current) {
    FormFactor.Phone -> phone
    FormFactor.Desktop -> desktop
}

/** The feedback kinds the app uses. */
enum class Haptic { CONFIRM, REJECT, LONG_PRESS, TICK }

/** Touch feedback; the desktop has none. */
@Immutable
interface Haptics {
    fun perform(kind: Haptic)
    fun confirm() = perform(Haptic.CONFIRM)
    fun reject() = perform(Haptic.REJECT)
    fun longPress() = perform(Haptic.LONG_PRESS)
    fun tick() = perform(Haptic.TICK)

    companion object {
        /** No feedback at all (the desktop, tests). */
        val None: Haptics = object : Haptics {
            override fun perform(kind: Haptic) {}
        }
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { Haptics.None }

/**
 * Where a full page sits in a wide window: [topInset] below the app's floating header and at most [maxWidth] wide, centred.
 * The phone's pages fill the screen ([None]); the desktop root provides its own.
 */
@Immutable
class PageFrame(val topInset: Dp, val maxWidth: Dp) {
    companion object {
        val None = PageFrame(0.dp, Dp.Unspecified)
    }
}

val LocalPageFrame = staticCompositionLocalOf { PageFrame.None }

/** How much of a page's top the app's floating header covers (the phone's tab pages put their content below it; 0 elsewhere). */
val LocalHeaderHeight = compositionLocalOf { 0.dp }

/** The top bar's height as a Dp, for content padding. */
@Composable
fun headerHeight(): Dp = LocalHeaderHeight.current

/**
 * The system's "back" for a full page: the phone's back gesture. The Android root provides the handler (and skips it inside its push
 * host, whose predictive back does it); the desktop has none (its pages take Escape themselves).
 */
val LocalBackHandler = staticCompositionLocalOf<@Composable (onBack: () -> Unit) -> Unit> { {} }

/** Sends the app to the background (the phone's "back" at a screen that covers everything); the desktop has no such step, the default does nothing. */
val LocalAppBackground = staticCompositionLocalOf<() -> Unit> { {} }

/** Hands a text to the system's share sheet. */
fun interface TextSharer {
    fun share(text: String)
}

/** The app's way to share a text; null (the desktop, for now) means there is none and the share action is not shown. The Android root provides one. */
val LocalTextSharer = staticCompositionLocalOf<TextSharer?> { null }

/**
 * Hides the app's own chrome (the phone's top bar and tab bar) while [hidden] is true and the caller is on screen: `chromeHider(key, hidden)`.
 * The Android root provides it; the desktop has no such chrome (the default does nothing).
 */
val LocalChromeHider = staticCompositionLocalOf<@Composable (key: String, hidden: Boolean) -> Unit> { { _, _ -> } }

/**
 * Keeps the window out of screenshots and the recent-apps preview while it is composed (a secret being typed). The Android root
 * provides FLAG_SECURE handling; the desktop does nothing.
 */
val LocalSecureWindow = staticCompositionLocalOf<@Composable () -> Unit> { {} }

/**
 * Runs `load` when `key` changes and every time the app comes back to the foreground (the user did something in the browser and
 * came back): `reloadOnReturn(key, load)`. The Android root provides the lifecycle-aware one; by default it only runs on `key`.
 */
val LocalReloadOnReturn = staticCompositionLocalOf<@Composable (key: Any?, load: suspend () -> Unit) -> Unit> {
    { key, load -> LaunchedEffect(key) { load() } }
}

/**
 * A back gesture that reports its progress (0..1) while the finger moves; the collector runs until the gesture ends, and a cancelled
 * gesture cancels it. `LocalPredictiveBack.current(enabled) { progress -> ... }`. The Android root provides the system's predictive
 * back; the desktop has none (the default does nothing).
 */
val LocalPredictiveBack = staticCompositionLocalOf<@Composable (enabled: Boolean, onGesture: suspend (progress: kotlinx.coroutines.flow.Flow<Float>) -> Unit) -> Unit> { { _, _ -> } }

/**
 * Asks the owner to prove it is them (fingerprint, face or the screen lock) before a page opens: `LocalVaultGate.current(onNoLock, title,
 * onNotOpened, onOpen)` returns the action that starts the check. [onOpen]'s argument is true when the device has no screen lock. With
 * [onNoLock] given, a device without a lock never opens: [onNoLock] is called instead. The Android root provides the biometric one;
 * the default (the desktop) opens at once.
 */
val LocalVaultGate = staticCompositionLocalOf<@Composable (onNoLock: (() -> Unit)?, title: String?, onNotOpened: (() -> Unit)?, onOpen: (noLock: Boolean) -> Unit) -> () -> Unit> {
    { _, _, _, onOpen -> { onOpen(false) } }
}

/** The vault gate with the default title: see [LocalVaultGate]. */
@Composable
fun rememberVaultGate(
    onNoLock: (() -> Unit)? = null, title: String? = null, onNotOpened: (() -> Unit)? = null, onOpen: (noLock: Boolean) -> Unit,
): () -> Unit = LocalVaultGate.current(onNoLock, title, onNotOpened, onOpen)

/** What the app's window is doing, for pages that lock when it goes to the background. */
interface AppLifecycle {
    /** The window is in front and can take input. */
    val resumed: Boolean

    /** The window is on screen (also when only paused: a dialog over it); false once it is stopped or being torn down. */
    val started: Boolean

    /** Calls [onResume] each time the window comes to the front (also once when this is first composed in front) and [onStop] each time it leaves the screen. */
    @Composable fun OnEvents(onResume: () -> Unit, onStop: () -> Unit)

    companion object {
        /** A window that is always in front (the desktop, tests): [OnEvents] reports one resume. */
        val Always: AppLifecycle = object : AppLifecycle {
            override val resumed = true
            override val started = true
            @Composable override fun OnEvents(onResume: () -> Unit, onStop: () -> Unit) { LaunchedEffect(Unit) { onResume() } }
        }
    }
}

val LocalAppLifecycle = staticCompositionLocalOf { AppLifecycle.Always }
