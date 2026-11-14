package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.pages.ConnectorsPane
import chat.kelo.desktop.pages.GoalsPane
import chat.kelo.desktop.pages.WorkPane
import chat.kelo.desktop.ui.HeaderHeight
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The window's floating header (the agent's picture and name, 104 dp, centred) sits on top of every page. In a narrow window (the
 * smallest is 520 dp) a page that starts at the very top had its title and back arrow under the picture. Checked at 520 dp wide
 * against tools/mock_server.py: the work page's title and sub pages, and a connector's page all start below it, and a goal opened beside the list is not pushed down a second time, and
 * the work tab has no back arrow that goes nowhere.
 */
class HeaderInsetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null
    private val ko = Words.of("ko")
    private val ui by lazy { UiSteps(compose, parityShots()) }
    private val back get() = hasContentDescription(ko.get(Res.string.back))

    @After fun stop() { mock?.stop() }

    private fun start(lang: String = "ko") = MockServer("--lang", lang).also { mock = it }.app(tmp.newFolder(), lang = lang).also { compose.mainClock.autoAdvance = false }

    private fun assertBelowHeader(what: String, top: androidx.compose.ui.unit.Dp) =
        assertTrue("$what starts at $top, under the header (${HeaderHeight})", top >= HeaderHeight)

    @Test fun theWorkTabHasItsTitleBelowTheHeaderAndNoBackArrow() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.size(520.dp, 560.dp)) { WorkPane(app, topInset = HeaderHeight) } } }
        ui.waitText(ko.get(Res.string.work_asks))
        assertEquals("no back arrow on the tab's first page", 0, compose.onAllNodes(back).fetchSemanticsNodes().size)
        assertBelowHeader("the title", compose.onAllNodesWithText(ko.get(Res.string.tab_work)).onFirst().getUnclippedBoundsInRoot().top)
    }

    @Test fun aWorkPageStartsBelowTheHeader() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.size(520.dp, 560.dp)) { WorkPane(app, topInset = HeaderHeight) } } }
        ui.waitText(ko.get(Res.string.work_asks))
        app.workRequests.value = OpenTarget.Task(1)
        ui.waitText("다음 분기 계획")
        assertBelowHeader("the task page's back arrow", compose.onAllNodes(back).onFirst().getUnclippedBoundsInRoot().top)
    }

    @Test fun aConnectorsPageStartsBelowTheHeaderInANarrowWindow() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.size(520.dp, 560.dp)) { ConnectorsPane(app, topInset = HeaderHeight, openId = "github") } } }
        ui.waitUntil("the connector's page") { compose.onAllNodes(back).fetchSemanticsNodes().isNotEmpty() }
        assertBelowHeader("the connector page's back arrow", compose.onAllNodes(back).onFirst().getUnclippedBoundsInRoot().top)
    }

    @Test fun aGoalOpenedBesideTheListIsNotPushedDownTwice() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.size(1100.dp, 700.dp)) { chat.kelo.desktop.pages.GoalsPane(app, topInset = HeaderHeight) } } }
        ui.waitText("매일 30분 걷기")
        compose.onAllNodesWithText("매일 30분 걷기").onFirst().performClick()
        ui.waitUntil("the goal's page") { compose.onAllNodes(back).fetchSemanticsNodes().isNotEmpty() }
        val top = compose.onAllNodes(back).onFirst().getUnclippedBoundsInRoot().top
        assertBelowHeader("the goal page's back arrow", top)
        assertTrue("the goal page's back arrow is at $top, a second header's height too low", top < HeaderHeight + 40.dp)
    }

    /** The texts whose layout is narrower than the whole text needs (cut, or ellipsized): the chip should wrap to a line of its own instead. */
    private fun narrowerThanItsText(text: String): Boolean {
        val node = compose.onNode(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        val r = out.first()
        return r.size.width + 1 < r.multiParagraph.maxIntrinsicWidth
    }

    /** A goal's chips ("Kelo's waiting for your answer", plan count, tag) wrap or end with an ellipsis: none is cut off mid-phrase. */
    @Test fun aGoalRowsChipsAreNeverCutOffInANarrowWindow() {
        val app = start("en")
        compose.setContent { KeloFrame(app) { Box(Modifier.size(330.dp, 700.dp)) { GoalsPane(app, topInset = HeaderHeight) } } }
        ui.waitText("Conversational Spanish")
        ui.waitText(Words.of("en").get(Res.string.goals_asking))
        ui.settle()
        assertEquals("the chip is cut", false, narrowerThanItsText(Words.of("en").get(Res.string.goals_asking)))
    }

    /** The last goal row (and its bin) scrolls clear of the floating add button. */
    @Test fun theLastGoalRowScrollsClearOfTheAddButton() {
        val app = start()
        compose.setContent { KeloFrame(app) { Box(Modifier.size(520.dp, 420.dp)) { GoalsPane(app, topInset = HeaderHeight) } } }
        ui.waitText("영어 회화 3개월")
        compose.onNode(androidx.compose.ui.test.hasScrollToKeyAction()).performScrollToKey("h:done")
        ui.settle()
        val fab = compose.onNode(androidx.compose.ui.test.hasText(ko.get(Res.string.goals_add)), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bin = compose.onAllNodes(hasContentDescription(ko.get(Res.string.delete))).fetchSemanticsNodes().last().boundsInRoot
        val density = compose.density.density
        assertTrue("last bin bottom ${bin.bottom} under the add button top ${fab.top.value * density}", bin.bottom <= fab.top.value * density)
    }
}
