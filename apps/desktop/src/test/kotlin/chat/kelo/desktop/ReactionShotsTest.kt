package chat.kelo.desktop

import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.app.chat.ChatViewModel

import chat.kelo.app.res.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.Reactions
import chat.kelo.app.chat.ReactionEndInset
import chat.kelo.app.chat.ReactionOverhang
import chat.kelo.desktop.ui.KeloFrame
import chat.kelo.desktop.ui.Page
import chat.kelo.desktop.ui.Shell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The emoji on the user's messages, in the real chat against tools/mock_server.py --reactions --final-reaction 🏃‍♂️: the history's
 * messages carry theirs, a new message gets 👀 as soon as the reply starts (a screen reader hears "읽음" / "Read") and 🏃‍♂️ (a ZWJ
 * sequence) when the reply is over; first a long message over several lines, then a very short one ("ㅇㅇ" / "ok"). Each time the
 * chip is checked against its bubble (near the end corner, or centred under a short bubble; hanging below the bottom edge).
 * Screenshots (ko and en, light and dark, wide and narrow) go to $KELO_SHOTS or build/parity as reactions-*.png.
 */
class ReactionShotsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var mock: MockServer? = null

    @After fun stop() { mock?.stop() }

    @Test fun korean() = flow("ko", wide = true)

    @Test fun english() = flow("en", wide = true)

    @Test fun koreanNarrow() = flow("ko", wide = false)

    @Test fun englishNarrow() = flow("en", wide = false)

    /**
     * Scrolls the chat until a node matching [target] is in view: below the floating header (the agent and its name, taller while
     * a status line shows under the name, and with Windows' fonts) and above the composer. One ScrollBy at a time, the clock moved
     * on; it fails when the node cannot be brought into view (it used to give up silently, scrolling the wrong way).
     */
    private fun showNode(ui: UiSteps, target: SemanticsMatcher) {
        var sign = 1f
        var blind = 0
        repeat(16) {
            val found = compose.onAllNodes(target).fetchSemanticsNodes()
            val box = found.firstOrNull()?.boundsInRoot
            val lists = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            val i = lists.indices.maxByOrNull { lists[it].boundsInRoot.height } ?: return
            val area = lists[i].boundsInRoot
            // the agent's name tag is the header's lowest part; the composer's text field the top of what covers the bottom
            val header = compose.onAllNodes(hasText(AGENT)).fetchSemanticsNodes().map { it.boundsInRoot }
                .filter { it.top < area.top + 200f }.maxOfOrNull { it.bottom }
            val composer = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.boundsInRoot.top }
                .filter { it > area.top + 200f }.minOrNull()
            val top = maxOf(area.top + 96f, (header ?: 0f) + 12f)
            val bottom = minOf(area.bottom, 760f, (composer ?: Float.MAX_VALUE) - 12f)
            // the chat is a reverseLayout list: ScrollBy with a positive y moves its rows down (the older ones above come into view)
            val dy = when {
                // not laid out (or clipped away): look further, older rows first, then the other way
                box == null || box.width <= 0f || box.height <= 0f -> { if (++blind % 3 == 0) sign = -sign; sign * area.height / 2 }
                box.top < top -> top - box.top + 40f
                box.bottom > bottom -> -(box.bottom - bottom + 40f)
                else -> return
            }
            compose.onAllNodes(hasScrollAction())[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
            ui.settle()
        }
        throw AssertionError("could not scroll $target into view")
    }

    /**
     * Scrolls the message [text] and its chip (spoken label [label]) into view and checks the chip's place ([checkPlacement]). While
     * the reply still comes in, the chat follows it down to the newest rows when the reader is near them (ChatScroll.follow), which
     * can pull the message away again between the scroll and the check (on Windows' CI the message was gone from the tree by the
     * time it was measured): then it lets that scroll finish, scrolls back and checks again, up to [ATTEMPTS] times.
     */
    private fun checkInView(ui: UiSteps, text: String, label: String) {
        repeat(ATTEMPTS) { n ->
            try {
                showNode(ui, hasText(text))
                showNode(ui, hasContentDescription(label))
                checkPlacement(text, label)
                return
            } catch (e: AssertionError) {
                if (n == ATTEMPTS - 1) throw e
                println("check of \"$label\" on \"${text.take(20)}\", attempt ${n + 1}: ${e.message}")
                ui.settle(12)
            }
        }
    }

    /**
     * The chip of the message whose text is [text] (its spoken label [label]) against its bubble: near the bubble's end corner (or
     * centred under a short bubble), over its bottom edge and hanging below it, never past the bubble's start edge.
     */
    private fun checkPlacement(text: String, label: String) {
        val texts = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        // scrolled away again since it was brought into view (the chat followed the reply down): an AssertionError, so it is retried
        val words = texts.lastOrNull() ?: throw AssertionError("\"${text.take(20)}\" is not on screen")
        val dp = compose.density.density
        // the chip with that label just under this text (an older message may carry the same emoji)
        val chips = compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().map { it.boundsInRoot }
        val chip = chips.minByOrNull { kotlin.math.abs(it.bottom - words.bottom) } ?: throw AssertionError("no chip \"$label\"")
        println("placement of \"$label\" on \"${text.take(20)}\": texts $texts, chips $chips")
        // the bubble is the text with 15 dp on each side and 11 dp above and below
        val left = words.left - 15 * dp
        val right = words.right + 15 * dp
        val bottom = words.bottom + 11 * dp
        val endAligned = right - left >= chip.width + 2 * ReactionEndInset.value * dp
        if (endAligned) assertEquals("10 dp in from the bubble's end edge", right - ReactionEndInset.value * dp, chip.right, 1.5f * dp)
        else assertEquals("centred under a short bubble", (left + right) / 2, chip.center.x, 1.5f * dp)
        assertTrue("never past the bubble's start edge", chip.left >= left - 0.5f)
        assertEquals("hangs ${ReactionOverhang} below the bubble", bottom + ReactionOverhang.value * dp, chip.bottom, 1.5f * dp)
        assertTrue("over the bubble's bottom edge", chip.top < bottom)
    }

    private fun flow(lang: String, wide: Boolean) {
        val words = Words.of(lang)
        val m = MockServer("--lang", lang, "--reactions", "--final-reaction", RUNNER).also { mock = it }
        val app = m.app(tmp.newFolder(), lang = lang)
        val chatHandle = DesktopChat(app)
        val chat = chatHandle.vm
        val ui = UiSteps(compose, parityShots())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeloFrame(app) {
                Box(Modifier.size(if (wide) 1024.dp else 480.dp, 760.dp)) {
                    Shell(app, chat, Page.CHAT, onPage = {}, onSignedOut = {}, connectorToOpen = null, onConnectorOpened = {}, side = chatHandle.side)
                }
            }
        }
        val size = if (wide) "wide" else "narrow"
        fun shots(name: String) {
            ui.shot("reactions-$lang-$size-$name-light")
            app.settings.setTheme("dark")
            ui.shot("reactions-$lang-$size-$name-dark")
            app.settings.setTheme("light")
            ui.settle()
        }
        fun mine() = chat.state.value.items.filterIsInstance<ChatItem.Msg>().filter { it.mine }
        val read = words.get(Res.string.chat_reaction_read)
        val ran = words.get(Res.string.chat_reaction_done, RUNNER)
        /** Sends [text]; 👀 on it while the reply runs; the reply's approval allowed; 🏃‍♂️ once it is over. Shots of both. */
        fun exchange(text: String, name: String) {
            val approvals = chat.state.value.items.count { it is ChatItem.Approval }
            chat.setInput(text)
            chat.send()
            ui.waitUntil("👀 on \"$text\"") { mine().last().reaction == Reactions.READ }
            ui.settle(12)
            // what the reply shows below the message (its steps, a browser card, the background job's cards) can push it out of
            // view, more so with Windows' taller fonts: scroll to it; its chip says "Read"
            checkInView(ui, text, read)
            shots("$name-read")
            // the reply asks to approve a step, then ends: 👀 gives way to the reply's own emoji
            ui.waitUntil("the approval", timeout = 20_000) { chat.state.value.items.count { it is ChatItem.Approval } > approvals }
            chat.approve(chat.state.value.items.filterIsInstance<ChatItem.Approval>().last().id, true)
            ui.waitUntil("the final emoji", timeout = 20_000) { mine().last().reaction == RUNNER }
            ui.settle(20)
            checkInView(ui, text, ran)   // the reply is long: the message is above
            shots("$name-final")
        }
        try {
            // the history: each of the user's messages carries the emoji the server put on it
            ui.waitUntil("the history") { mine().size >= 2 }
            assertEquals(listOf("🗓️", "📝"), mine().map { it.reaction })
            // the newest one with its chip, in its place: the cards below it (an approval, a human check while it is open) can fill
            // the window, so scroll to it rather than count on it being in view
            checkInView(ui, mine().last().text, words.get(Res.string.chat_reaction_done, "📝"))
            shots("history")
            // a long message over several lines, then a very short answer
            exchange(if (lang == "ko") LONG_KO else LONG_EN, "long")
            exchange(if (lang == "ko") "ㅇㅇ" else "ok", "short")
        } catch (e: Throwable) {
            println("the user's messages: " + mine().map { "#${it.id}/${it.key} ${it.text.take(20)} [${it.reaction}]" })
            runCatching { ui.shot("reactions-$lang-$size-zz-failed") }
            throw e
        }
    }

    private companion object {
        const val ATTEMPTS = 10   // checkInView's tries: each one scrolls at most 32 steps, so it always ends
        const val AGENT = "Kelo"   // the mock server's agent name, in both languages
        const val RUNNER = "\uD83C\uDFC3\u200D\u2642\uFE0F"   // 🏃‍♂️: a ZWJ sequence, one picture
        const val LONG_KO = "다음 주 치과 예약 언제였는지 확인해 줘. 끝나고 바로 한강에서 10km 달리기 하려고 하니까, 시간이 정해져 있으면 캘린더에 넣고 앞뒤로 30분씩 비워 둬"
        const val LONG_EN = "Can you check when my dentist appointment is next week? I want to go for a 10 km run by the river right after, so put it in my calendar and keep 30 minutes free on either side"
    }
}
