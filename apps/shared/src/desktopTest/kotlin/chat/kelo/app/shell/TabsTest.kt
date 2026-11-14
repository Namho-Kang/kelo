package chat.kelo.app.shell

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which tabs the shell shows: the built-in ones, the owner's layout, and Nest's tab entries. */
class TabsTest {
    private val none: @Composable () -> Unit = {}
    private val builtIn = builtInTabs(withWork = false) { none }
    private fun ids(tabs: List<TabSpec>) = tabs.map { it.id }
    private fun nest(vararg keys: String, shown: Boolean = true) = keys.map { NestEntry(key = it, title = it, place = NestPlace.TAB, shown = shown) }

    @Test fun todaysTabsAreTheDefault() {
        assertEquals(listOf("chat", "goals", "connectors", "my"), ids(tabsFor(builtIn, emptyList())))
        assertEquals(listOf("chat", "goals", "work", "connectors", "my"), ids(builtInTabs(withWork = true) { none }))
    }

    @Test fun goalsAndConnectorsCanBeHiddenAndStayReachableFromMy() {
        val layout = TabLayout(hidden = setOf("goals", "connectors"))
        assertEquals(listOf("chat", "my"), ids(tabsFor(builtIn, emptyList(), layout)))
        assertEquals(listOf("goals", "connectors"), ids(hiddenTabs(builtIn, layout)))
    }

    @Test fun chatAndMyCanNeverBeRemoved() {
        val layout = TabLayout(hidden = setOf("chat", "my", "goals", "connectors"))
        assertEquals(listOf("chat", "my"), ids(tabsFor(builtIn, emptyList(), layout)))
        assertTrue(hiddenTabs(builtIn, layout).none { it.id == "chat" || it.id == "my" })
    }

    @Test fun nestTabsGoAfterConnectorsAndBeforeMyInTheServersOrder() {
        val tabs = tabsFor(builtIn, nest("b", "a"), maxTabs = 5)
        assertEquals(listOf("chat", "goals", "connectors", "nest/b", "nest/a", "my"), ids(tabs))
        assertTrue(tabs.single { it.id == "nest/a" }.fromNest)
    }

    @Test fun nestTabsAreCutAtMaxTabsAndOnlyShownOnesCount() {
        assertEquals(listOf("nest/a", "nest/b"), ids(tabsFor(builtIn, nest("a", "b", "c"), maxTabs = 2)).filter { it.startsWith("nest/") })
        assertEquals(emptyList(), ids(tabsFor(builtIn, nest("a"), maxTabs = 0)).filter { it.startsWith("nest/") })
        assertEquals(emptyList(), ids(tabsFor(builtIn, nest("a", shown = false), maxTabs = 3)).filter { it.startsWith("nest/") })
    }

    @Test fun nestTabsStayBeforeMyWhenConnectorsAreHidden() {
        val tabs = tabsFor(builtIn, nest("a"), TabLayout(hidden = setOf("connectors")), maxTabs = 1)
        assertEquals(listOf("chat", "goals", "nest/a", "my"), ids(tabs))
    }
}
