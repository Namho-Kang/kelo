package chat.kelo.desktop

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import chat.kelo.app.agent.SkillProposals
import chat.kelo.app.notify.OpenTarget
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.pages.WorkPane
import chat.kelo.desktop.ui.KeloFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Custom skills, against tools/mock_server.py (tools/mock_parts/skills.py): 마이 → 기록's "skills to approve" (a new skill drafted
 * from a task, with the outside-text note and its full text; a change with its added and removed lines), approving one until its
 * card leaves; and "Save as skill" on a finished task's page (the message, then the drafting state on the button, then the button
 * back once the draft is written). Korean and English, light and dark, plus the review section in a 360 dp German window
 * (wrapping). Saved as skills-review-*.png and skills-save-*.png in build/parity ($KELO_SHOTS).
 */
@OptIn(ExperimentalTestApi::class)
class SkillsShotsTest {
    private val shots = parityShots().also { it.mkdirs() }

    @Test fun reviewKo() = review("ko", listOf("light", "dark"), 1000, 2600)

    @Test fun reviewEn() = review("en", listOf("light", "dark"), 1000, 2600)

    @Test fun reviewDeNarrow() = review("de", listOf("light"), 360, 4200, "narrow-")

    @Test fun saveKo() = save("ko")

    @Test fun saveEn() = save("en")

    /** A [w]×[h] dp window on a fresh mock (it speaks Korean or English) with the app in [lang]. */
    private fun run(lang: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable (AppContainer) -> Unit, steps: Steps.(AppContainer) -> Unit) {
        val server = MockServer("--lang", if (lang == "ko") "ko" else "en")
        try {
            runDesktopComposeUiTest(w, h) {
                val app = server.app(Files.createTempDirectory("kelo-skills").toFile(), lang = lang)
                mainClock.autoAdvance = false
                setContent { KeloFrame(app) { Box(Modifier.fillMaxSize()) { content(app) } } }
                Steps(this, shots).steps(app)
            }
        } finally {
            server.stop()
        }
    }

    class Steps(val t: ComposeUiTest, private val dir: File) {
        fun settle(frames: Int = 12) = repeat(frames) { t.mainClock.advanceTimeBy(64); Thread.sleep(10) }

        fun has(text: String): Boolean = t.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

        fun waitUntil(what: String, timeout: Long = 10_000, ok: () -> Boolean) {
            val end = System.currentTimeMillis() + timeout
            while (!ok()) {
                t.mainClock.advanceTimeBy(64)
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
                Thread.sleep(25)
            }
        }

        fun waitText(text: String, timeout: Long = 10_000) = waitUntil("\"$text\"", timeout) { has(text) }

        /** The [fromEnd]-th node (0: the last) whose whole text is [text]. */
        fun fromEnd(text: String, fromEnd: Int = 0): SemanticsNodeInteraction =
            t.onAllNodesWithText(text).let { all -> all[all.fetchSemanticsNodes().size - 1 - fromEnd] }

        /** Presses [node] through its click action (it need not be on screen). */
        fun click(node: SemanticsNodeInteraction) {
            node.performSemanticsAction(SemanticsActions.OnClick)
            settle(4)
        }

        /** The window and any open popup drawn one over the other, as [name].png; [rows]: only that band of it (px). */
        fun shot(name: String, rows: IntRange? = null) {
            settle(20)
            val roots = t.onAllNodes(isRoot())
            val layers = roots.fetchSemanticsNodes().indices.map { roots[it].captureToImage().toAwtImage() }
            val out = java.awt.image.BufferedImage(layers[0].getWidth(null), layers[0].getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply { layers.forEach { drawImage(it, 0, 0, null) }; dispose() }
            val band = rows?.let { r -> r.first.coerceAtLeast(0)..r.last.coerceAtMost(out.height - 1) }
            ImageIO.write(band?.let { out.getSubimage(0, it.first, out.width, it.last - it.first + 1) } ?: out, "png", File(dir, "$name.png"))
        }
    }

    private fun review(lang: String, themes: List<String>, w: Int, h: Int, tag: String = "") {
        val start = mutableStateOf<String?>(null)
        run(lang, w, h, { app -> MyPaneAt(app, start) }) { app ->
            val s = Words.of(lang)
            // the names in the owner's language as the mock sends them (SkillRules.name), never the ids
            val newName = if (lang == "ko") "도서관 책 연장" else "Renew library books"
            val changeName = if (lang == "ko") "주간 장보기 목록" else "Weekly grocery list"
            val approve = s.get(Res.string.skill_review_approve)
            try {
                waitText(s.get(Res.string.my_section_account), timeout = 15_000)
                start.value = MyView.RECORDS
                settle()
                waitText(s.get(Res.string.skill_review_title), timeout = 15_000)
                // both cards: kind, the name in words (never the raw id), where it came from, the note, the text or the changed lines
                waitText(newName)
                waitText(changeName)
                check(!has("renew-library-book") && !has("weekly-grocery-list")) { "a raw skill id is shown" }
                listOf(Res.string.skill_review_about, Res.string.skill_review_kind_new, Res.string.skill_review_kind_change, Res.string.skill_review_outside_note,
                    Res.string.skill_review_from_draft, Res.string.skill_review_from_kelo, Res.string.skill_review_show_all, Res.string.skill_review_discard)
                    .forEach { check(has(s.get(it))) { "missing: ${s.get(it)}" } }
                check(has(s.get(Res.string.skill_review_diff_counts, 2, 1))) { "the change's counts line is missing" }
                check(has(if (lang == "ko") "냉장고 메모에서" else "fridge note")) { "the change's added line is missing" }
                check(!has(s.get(Res.string.skill_review_secrets_removed))) { "no secrets were removed in the samples" }
                for (theme in themes) {
                    app.settings.setTheme(theme)
                    settle()
                    // the section alone, from its heading to its last button (the window is tall enough for the whole page)
                    val top = fromEnd(s.get(Res.string.skill_review_title)).fetchSemanticsNode().boundsInRoot.top.toInt() - 24
                    val bottom = fromEnd(approve).fetchSemanticsNode().boundsInRoot.bottom.toInt() + 24
                    shot("skills-review-$tag$lang-$theme", top..bottom)
                }
                app.settings.setTheme("light")
                settle()
                if (tag.isNotEmpty()) return@run
                // Show all / Show less on the new skill's text (the skills are the page's last part, and only that text is longer
                // than the preview: the last such link is its)
                click(fromEnd(s.get(Res.string.skill_review_show_all)))
                waitText(s.get(Res.string.skill_review_show_less))
                click(fromEnd(s.get(Res.string.skill_review_show_less)))
                waitText(s.get(Res.string.skill_review_show_all))
                // approve the new one (the first of the two cards): the outcome shows for a moment, then the card leaves and the
                // server has it no more
                click(fromEnd(approve, 1))
                waitText(s.get(Res.string.skill_review_approved))
                waitUntil("the approved card to leave", timeout = 15_000) { !has(newName) }
                assertTrue("the change is still waiting", has(changeName))
                val left = runBlocking { app.sessions.requireApi().get<SkillProposals>("/api/skills/proposals") }
                assertEquals(listOf("p-change-2"), left.items.map { it.id })
            } catch (e: Throwable) {
                runCatching { shot("skills-zz-failed-review-$tag$lang") }
                throw e
            }
        }
    }

    private fun save(lang: String) {
        run(lang, 1000, 800, { app -> WorkPane(app, topInset = 16.dp) }) { app ->
            val s = Words.of(lang)
            val saveLabel = s.get(Res.string.skill_save)
            val place = arrayOf(s.get(Res.string.tab_my), s.get(Res.string.my_records_title))
            try {
                waitText(s.get(Res.string.tab_work), timeout = 15_000)   // the page is up (답이 필요해요 may push 지금 하는 일 below)
                app.workRequests.value = OpenTarget.Task(4)   // "Weekly shopping list", done
                waitText(if (lang == "ko") "목록을 메모에 저장했어요." else "Saved the list to notes.", timeout = 15_000)
                waitText(saveLabel)
                waitText(s.get(Res.string.skill_save_about, *place))
                for (theme in listOf("light", "dark")) {
                    app.settings.setTheme(theme)
                    shot("skills-save-$lang-$theme")
                }
                app.settings.setTheme("light")
                settle()
                click(fromEnd(saveLabel))
                waitText(s.get(Res.string.skill_save_started, *place))
                waitText(s.get(Res.string.skill_review_drafting))
                check(!has(saveLabel)) { "the button still says Save as skill while drafting" }
                shot("skills-save-drafting-$lang-light")
                // the mock finishes the draft after 3 s; the page asks again and the button comes back
                waitUntil("the button back after the draft", timeout = 30_000) { has(saveLabel) && !has(s.get(Res.string.skill_review_drafting)) }
                val after = runBlocking { app.sessions.requireApi().get<SkillProposals>("/api/skills/proposals") }
                assertTrue("the draft waits on the Records page", after.items.any { it.skill == "check-parcel" && it.origin.taskId == 4L })
            } catch (e: Throwable) {
                runCatching { shot("skills-zz-failed-save-$lang") }
                throw e
            }
        }
    }
}
