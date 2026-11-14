package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import chat.kelo.desktop.ui.KeloFrame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Learned message hours against tools/mock_server.py (a night owl: 10:00 – 01:00 next day, set 08:00 – 22:00), in Korean and English,
 * light and dark: the review row on 마이 → 기록 with "지금대로 두기" / "이 시간으로 바꾸기", the row after accepting, and 마이 → 알림
 * saying the hours were learned, with the "adjust automatically" switch. Saved as hours-*.png in build/parity ($KELO_SHOTS).
 */
class HoursShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val mocks = mutableListOf<MockServer>()
    private val start = androidx.compose.runtime.mutableStateOf<String?>(null)

    @After fun stop() { mocks.forEach { it.stop() } }

    @Test fun ko() = shots("ko")

    @Test fun en() = shots("en")

    /**
     * Scrolls the page until [text] sits in the middle of the test window (768 high), one ScrollBy at a time with the clock moved on
     * (performScrollTo waits for its own scroll animation with the clock held, which never ends on this page).
     */
    private fun bringIntoView(ui: UiSteps, text: String) {
        repeat(10) {
            val box = compose.onAllNodesWithText(text, substring = true).onFirst().fetchSemanticsNode().boundsInRoot
            if (box.top in 150f..620f) return
            // the page's own scroll area (the one holding the text), not the My page underneath it
            val area = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(text, substring = true)))
            if (area.fetchSemanticsNodes().isEmpty()) return
            // a row kept by the list but not laid out (below the window) has no size: move on down until it is placed
            val dy = if (box.height <= 0f) 400f else box.top - 380f
            area.onLast().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
            ui.settle()
        }
    }

    private fun shots(lang: String) {
        val server = MockServer("--lang", lang).also { mocks += it }
        val app = server.app(tmp.newFolder(), lang = lang, theme = "light")
        val s = Words.of(lang)
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(1000.dp, 760.dp)) {   // within the test window (768 high), so the page scrolls
                KeloFrame(app) { MyPaneAt(app, start) }
            }
        }
        val heading = s.get(Res.string.hours_suggest_heading)
        val accept = s.get(Res.string.hours_suggest_accept)
        fun back() {
            compose.onAllNodesWithContentDescription(s.get(Res.string.back))[0].performClick()
            ui.waitText(s.get(Res.string.my_section_account))
        }
        try {
            for (theme in listOf("light", "dark")) {
                app.settings.setTheme(theme)
                ui.waitText(s.get(Res.string.my_section_account))
                start.value = MyView.RECORDS
                ui.settle()
                ui.waitText(heading, timeout = 15_000)
                bringIntoView(ui, accept)
                check(ui.has(s.get(Res.string.hours_suggest_keep))) { "keep current is offered" }
                ui.shot("hours-suggest-$lang-$theme")
                back()
            }
            // accept: the row says what changed, then goes; the notifications page says the hours were learned
            app.settings.setTheme("light")
            start.value = MyView.RECORDS
            ui.settle()
            ui.waitText(heading, timeout = 15_000)
            bringIntoView(ui, accept)
            compose.onNodeWithText(accept).performClick()
            val changed = s.get(Res.string.hours_suggest_accepted, "").trimEnd(' ', '·').trim()
            ui.waitText(changed)
            ui.shot("hours-accepted-$lang")
            ui.waitUntil("the row to go", timeout = 15_000) { !ui.has(heading) }
            back()
            for (theme in listOf("light", "dark")) {
                app.settings.setTheme(theme)
                start.value = MyView.NOTIFY
                ui.settle()
                ui.waitText(s.get(Res.string.hours_learned), timeout = 15_000)
                ui.waitText(s.get(Res.string.hours_auto_switch))
                bringIntoView(ui, s.get(Res.string.hours_learned))
                ui.shot("hours-learned-$lang-$theme")
                back()
            }
            check(compose.onAllNodesWithText(heading).fetchSemanticsNodes().isEmpty())
        } catch (e: Throwable) {
            runCatching { ui.shot("hours-zz-failed-$lang") }
            throw e
        }
    }
}
