package chat.kelo.app.my

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import chat.kelo.app.ui.theme.KeloTheme
import org.junit.After
import org.junit.Before
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The version line at the bottom of My: the short commit is a link to its source on GitHub for a clean build (all three apps). */
@OptIn(ExperimentalTestApi::class)
class AppVersionLineTest {
    private val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
    private val saved = Locale.getDefault()
    @Before fun before() { Locale.setDefault(Locale.ENGLISH) }
    @After fun after() { Locale.setDefault(saved) }

    @Test fun theShortHashIsALinkThatOpensTheCommitInTheBrowser() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { KeloTheme(dark = false) { AppVersionLine("0.9.3", "0.9.2", sha, sourceDirty = false, openUrl = { opened += it }) } }
        onNodeWithText("App version: 0.9.3 ·", substring = true).assertExists()
        onNodeWithText("Server version: 0.9.2").assertExists()
        onNodeWithContentDescription("Source code of this version on GitHub").assertExists()
        onNodeWithText("a1b2c3d").performClick()
        assertEquals(listOf("https://github.com/kelo-chat/kelo/tree/$sha"), opened)
    }

    @Test fun aModifiedBuildShowsTheHashWithoutALink() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { KeloTheme(dark = false) { AppVersionLine("0.9.3", null, sha, sourceDirty = true, openUrl = { opened += it }) } }
        onNodeWithText("a1b2c3d (modified)").performClick()
        assertEquals(emptyList<String>(), opened)
        onAllNodesWithText("a1b2c3d (modified)").assertCountEquals(1)
        onAllNodes(hasContentDescription("Source code of this version on GitHub")).assertCountEquals(0)
    }

    @Test fun aBuildThatDoesNotKnowItsCommitAddsNothing() = runComposeUiTest {
        setContent { KeloTheme(dark = false) { AppVersionLine("0.9.3", "0.9.2", "", sourceDirty = false, openUrl = {}) } }
        onNodeWithText("App version: 0.9.3").assertExists()
        onAllNodesWithText("·", substring = true).assertCountEquals(0)
    }
}
