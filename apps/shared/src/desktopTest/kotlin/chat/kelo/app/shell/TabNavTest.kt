package chat.kelo.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavTest {
    @Test
    fun anotherTabSwitches() {
        assertEquals(TabNav.Tap.SWITCH, TabNav.onTap(Routes.CHAT, Routes.CONNECTORS))
        assertEquals(TabNav.Tap.SWITCH, TabNav.onTap("connectors/open/github", Routes.MY))
        assertEquals(TabNav.Tap.SWITCH, TabNav.onTap(null, Routes.CHAT))
    }

    @Test
    fun theSameTabResetsToItsStart() {
        assertEquals(TabNav.Tap.RESET, TabNav.onTap(Routes.CONNECTORS, Routes.CONNECTORS))
        assertEquals(TabNav.Tap.RESET, TabNav.onTap(Routes.MY, Routes.MY))
        assertEquals(TabNav.Tap.RESET, TabNav.onTap(Routes.CHAT, Routes.CHAT))
    }

    @Test
    fun aConnectorDetailRouteGoesBackToTheList() {
        assertEquals(TabNav.Tap.TO_ROOT, TabNav.onTap(Routes.CONNECTOR_OPEN, Routes.CONNECTORS))
        assertEquals(TabNav.Tap.TO_ROOT, TabNav.onTap("connectors/open/notion", Routes.CONNECTORS))
    }

    @Test
    fun subRoutesKeepTheirTabLitButNotLookAlikes() {
        assertTrue(TabNav.isIn("connectors/open/{id}", Routes.CONNECTORS))
        assertTrue(TabNav.isIn(Routes.GOALS, Routes.GOALS))
        assertFalse(TabNav.isIn("connectorsx", Routes.CONNECTORS))
        assertFalse(TabNav.isIn(null, Routes.CHAT))
    }

    @Test
    fun theKeyboardHidesTheBarOnlyForTheComposer() {
        assertTrue(ChromeRules.keyboardHidesBar(imeVisible = true, composerFocused = true))
        // a keyboard still closing from the pairing screen, or one whose field let go of its focus
        assertFalse(ChromeRules.keyboardHidesBar(imeVisible = true, composerFocused = false))
        assertFalse(ChromeRules.keyboardHidesBar(imeVisible = false, composerFocused = true))
        assertFalse(ChromeRules.keyboardHidesBar(imeVisible = false, composerFocused = false))
    }
}
