package chat.kelo.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Routes (tab ids) inside the signed-in app; a tab's id is its route. */
object Routes {
    const val CHAT = "chat"
    const val WORK = "work"
    const val GOALS = "goals"
    const val CONNECTORS = "connectors"
    const val MY = "my"

    /** The Connectors tab opened at one connector ("connectors/" keeps the tab lit). */
    const val CONNECTOR_OPEN = "connectors/open/{id}"
    fun connectorOpen(id: String): String = "connectors/open/" + encode(id)

    /** Percent-encodes everything but letters, digits and `_-!.~'()*` (what Android's Uri.encode keeps). */
    private fun encode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c in "_-!.~'()*")) append(c)
            else append('%').append("%02X".format(b.toInt() and 0xFF))
        }
    }
}

/** What a tap on a bottom tab does; kept apart from the UI so it can be tested. */
object TabNav {
    enum class Tap {
        /** Another tab: go there (its last place is restored) */
        SWITCH,
        /** This tab, but on one of its sub routes ("connectors/open/{id}"): go to the tab's own route */
        TO_ROOT,
        /** This tab's own route: the page goes back to its start (closes an open detail, scrolls to the top) */
        RESET,
    }

    /** [current] is this tab or one of its sub routes ("connectors/..." keeps 커넥터 lit). */
    fun isIn(current: String?, tab: String): Boolean = current == tab || current?.startsWith("$tab/") == true

    fun onTap(current: String?, tab: String): Tap = when {
        current == tab -> Tap.RESET
        isIn(current, tab) -> Tap.TO_ROOT
        else -> Tap.SWITCH
    }
}

/** Taps on the tab that is already open, by route. Only a page on screen hears them; nothing is kept for later. */
class TabReselect {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val taps: SharedFlow<String> = flow.asSharedFlow()
    fun emit(route: String) {
        flow.tryEmit(route)
    }
}

val LocalTabReselect = staticCompositionLocalOf { TabReselect() }

/** How tall the top app bar is: pages start their content this far down. 0 where there is no bar. */
val LocalHeaderHeight = chat.kelo.app.ui.platform.LocalHeaderHeight

/**
 * The top app bar's trailing actions, owned by the page on screen: a tab's page registers its actions for its route with
 * [ShellBarActions] while it is shown, and the shell's bar draws them.
 */
class ShellBarSlots {
    internal val byRoute = androidx.compose.runtime.mutableStateMapOf<String, @Composable androidx.compose.foundation.layout.RowScope.() -> Unit>()
}

val LocalShellBarSlots = staticCompositionLocalOf { ShellBarSlots() }

/** Puts [content] at the end of the top app bar while [route]'s page is on screen. */
@Composable
fun ShellBarActions(route: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val slots = LocalShellBarSlots.current
    val latest = rememberUpdatedState(content)
    androidx.compose.runtime.DisposableEffect(slots, route) {
        slots.byRoute[route] = { latest.value(this) }
        onDispose { slots.byRoute.remove(route) }
    }
}

/**
 * How tall the bottom tab bar is (with the navigation bar under it). The page itself takes the whole window and the tab bar floats
 * over its bottom, so lists end this far up and scroll under the bar's fade. It keeps its last value while the bar is hidden, so a
 * page under a sliding sub page does not jump.
 */
val LocalBottomBarHeight = compositionLocalOf { 0.dp }

/**
 * The app's top bar and tab bar can step aside for a page that wants the whole screen (a My sub page, a connector's detail) or
 * while the keyboard is up on the chat. Each asker has its own key; the bars come back when nobody asks any more.
 */
class ShellChrome {
    private val askers = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun hide(key: String, hidden: Boolean) {
        if (hidden) askers[key] = true else askers.remove(key)
    }

    /** The top bar steps aside for a full-screen page (not for the keyboard: the chat keeps its name on top while typing) */
    val headerHidden: Boolean get() = ChromeRules.headerHidden(askers.keys)
    /** The tab bar steps aside for both */
    val barHidden: Boolean get() = ChromeRules.barHidden(askers.keys)

    companion object {
        const val KEY_KEYBOARD = "keyboard"
    }
}

object ChromeRules {
    fun headerHidden(askers: Set<String>): Boolean = askers.any { it != ShellChrome.KEY_KEYBOARD }
    fun barHidden(askers: Set<String>): Boolean = askers.isNotEmpty()

    /**
     * The chat's keyboard hides the tab bar only while it is up for the chat's own field: a keyboard still closing from the screen
     * before (the pairing link field) or one whose field lost its focus never keeps the bar away.
     */
    fun keyboardHidesBar(imeVisible: Boolean, composerFocused: Boolean): Boolean = imeVisible && composerFocused
}

val LocalShellChrome = staticCompositionLocalOf { ShellChrome() }

/** Hides the top bar and tab bar while [hidden] is true and this is on screen. */
@Composable
fun HideShellChrome(key: String, hidden: Boolean) {
    val chrome = LocalShellChrome.current
    androidx.compose.runtime.DisposableEffect(chrome, key, hidden) {
        chrome.hide(key, hidden)
        onDispose { chrome.hide(key, false) }
    }
}

/** The tab bar's height, for the bottom of a page's content. */
@Composable
fun bottomBarHeight(): Dp = LocalBottomBarHeight.current

/** Runs [onReset] when the user taps [route]'s tab while already in it. */
@Composable
fun OnTabReselect(route: String, onReset: suspend () -> Unit) {
    val taps = LocalTabReselect.current
    val current = rememberUpdatedState(onReset)
    LaunchedEffect(taps, route) {
        taps.taps.collect { if (it == route) current.value() }
    }
}

/** The top bar's height as a Dp, for content padding. */
@Composable
fun headerHeight(): Dp = LocalHeaderHeight.current
