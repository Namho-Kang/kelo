package chat.kelo.desktop

import chat.kelo.app.chat.QuickReplies

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.ReplyQuestion
import chat.kelo.app.chat.ReplySet
import chat.kelo.app.ui.theme.Kelo
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.desktop.core.StoreKey
import chat.kelo.desktop.ui.KeloFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An answer to write under an agent message (QuickReplyRules): picking "직접 입력할게요" (marked by the server) or "있어요 (직접 입력)"
 * (an older server: only its words say so) opens a text box with the question's hint; Send stays off while a picked box is empty,
 * and what is sent carries the typed words in place of the labels, with the other answers kept. Screenshots (ko, light and dark):
 * $KELO_SHOTS or build/parity as typed-answer-*.png.
 */
class TypedAnswerTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val ko = Words.of("ko")

    private val form = ReplySet(listOf(
        ReplyQuestion("체중 (현재 → 목표)", listOf("직접 입력할게요", "말하고 싶지 않아요"), typed = listOf("직접 입력할게요"), hint = "현재 체중과 목표 체중"),
        ReplyQuestion("기간", listOf("1개월", "3개월", "6개월", "정해두지 않았어요")),
        ReplyQuestion("식단 제한", listOf("없어요", "있어요 (직접 입력)", "말하고 싶지 않아요")),
    ), goalId = 1)

    @Test fun typedAnswersOpenABoxAndAreSent() {
        val dir = tmp.newFolder()
        val app = AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir), requireE2e = false)
        app.settings.setLanguage("ko")
        app.applyLanguage()
        app.settings.setTheme("light")
        val sent = mutableListOf<String>()
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(640.dp, 620.dp).background(Kelo.colors.bg).padding(16.dp)) { QuickReplies(form, onSend = { text, _ -> sent += text }) }
            }
        }
        val send = ko.get(Res.string.reply_send)
        fun sendButton() = compose.onAllNodes(hasText(send) and hasClickAction()).onFirst()
        fun boxes() = compose.onAllNodes(hasSetTextAction())

        ui.waitText("직접 입력할게요")
        assertEquals(0, boxes().fetchSemanticsNodes().size)
        compose.onAllNodes(hasText("직접 입력할게요") and hasClickAction()).onFirst().performClick()
        ui.settle()
        ui.waitText("현재 체중과 목표 체중")                      // the server's hint in the box
        sendButton().assertIsNotEnabled()                        // a picked box that is still empty
        boxes().onFirst().performTextInput("현재 80 목표 70")
        ui.settle()
        sendButton().assertIsEnabled()
        compose.onAllNodes(hasText("3개월") and hasClickAction()).onFirst().performClick()
        compose.onAllNodes(hasText("있어요 (직접 입력)") and hasClickAction()).onFirst().performClick()
        ui.settle()
        ui.waitText("식단 제한")
        assertEquals(2, boxes().fetchSemanticsNodes().size)       // the older server's words opened one too
        sendButton().assertIsNotEnabled()
        boxes().onLast().performTextInput("유당불내증")
        ui.settle()
        ui.shot("typed-answer-light")
        app.settings.setTheme("dark")
        ui.shot("typed-answer-dark")
        sendButton().assertIsEnabled().performClick()
        ui.settle()
        assertEquals(listOf("체중 (현재 → 목표): 현재 80 목표 70\n기간: 3개월\n식단 제한: 유당불내증"), sent)
        assertTrue(ko.get(Res.string.reply_type_hint).isNotBlank())
    }
}
