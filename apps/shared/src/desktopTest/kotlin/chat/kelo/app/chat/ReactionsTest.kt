package chat.kelo.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji on the user's messages: the event, where it lands, the stored field, and what a screen reader hears. */
class ReactionsTest {
    private val golf = "🏌️"                         // 🏌️
    private val runner = "🏃‍♂️"          // 🏃‍♂️
    private var n = 0
    private fun key() = "k${++n}"

    private fun event(json: String) = TurnEvent.parse(Json.parseToJsonElement(json) as JsonElement)

    @Test fun theEventIsParsed() {
        assertEquals(TurnEvent.Reaction(42, Reactions.READ), event("""{"type":"reaction","message_id":42,"emoji":"${Reactions.READ}"}"""))
        assertEquals(TurnEvent.Reaction(7, runner), event("""{"type":"reaction","message_id":7,"emoji":"$runner"}"""))
    }

    @Test fun readThenTheFinalEmojiLandOnTheJustSentMessage() {
        // the message was sent from this phone: on screen it has no id yet
        var items: List<ChatItem> = listOf(
            ChatItem.Msg("m1", mine = true, text = "old", id = 1, reaction = "✅"),
            ChatItem.Msg("m2", mine = false, text = "ok", id = 2),
            ChatItem.Msg("k9", mine = true, text = "주말에 골프 칠까?"),
        )
        items = ChatReducer.apply(items, TurnEvent.Reaction(3, Reactions.READ), ::key)
        assertEquals(Reactions.READ, (items[2] as ChatItem.Msg).reaction)
        items = ChatReducer.apply(items, TurnEvent.Text("좋아요"), ::key)
        items = ChatReducer.apply(items, TurnEvent.Reaction(3, golf), ::key)
        assertEquals(golf, (items[2] as ChatItem.Msg).reaction)
        assertEquals("✅", (items[0] as ChatItem.Msg).reaction)            // the older message keeps its own
        assertEquals("", (items[3] as ChatItem.Msg).reaction)                  // the agent's reply never gets one
    }

    @Test fun anEarlierMessageIsFoundByItsId() {
        // a question back kept 👀 on the earlier message; the answer's reply ends both
        val items: List<ChatItem> = listOf(
            ChatItem.Msg("m5", mine = true, text = "book a table", id = 5, reaction = Reactions.READ),
            ChatItem.Msg("m6", mine = false, text = "what time?", id = 6),
            ChatItem.Msg("k1", mine = true, text = "7pm", reaction = Reactions.READ),
        )
        val out = ChatReducer.apply(items, TurnEvent.Reaction(5, runner), ::key)
        assertEquals(runner, (out[0] as ChatItem.Msg).reaction)
        assertEquals(Reactions.READ, (out[2] as ChatItem.Msg).reaction)
        assertEquals(0, Reactions.target(items, 5))
        assertEquals(2, Reactions.target(items, 99))                            // unknown id: the unsent one
    }

    @Test fun nothingToReactToLeavesTheListAsItIs() {
        val items: List<ChatItem> = listOf(ChatItem.Msg("m1", mine = false, text = "hi", id = 1))
        assertSame(items, ChatReducer.apply(items, TurnEvent.Reaction(1, golf), ::key))
        assertEquals(-1, Reactions.target(items, 1))
    }

    @Test fun storedMessagesCarryIt() {
        val json = Json { ignoreUnknownKeys = true }
        val m = json.decodeFromString<List<StoredMessage>>(
            """[{"id":1,"role":"user","content":"hi","created_at":1.0,"reaction":"$golf"},{"id":2,"role":"assistant","content":"yo","created_at":2.0}]""",
        )
        assertEquals(golf, m[0].reaction)
        assertEquals("", m[1].reaction)   // an older server sends none
    }

    @Test fun whatAScreenReaderHears() {
        assertTrue(Reactions.isRead(Reactions.READ))
        assertFalse(Reactions.isRead(golf))
        assertEquals("읽음", Reactions.spoken(Reactions.READ, "읽음") { "반응: $it" })
        assertEquals("Reaction: $runner", Reactions.spoken(runner, "Read") { "Reaction: $it" })
    }

    @Test fun theChipSitsNearTheBubblesEndCorner() {
        // in dp: chip box 26 wide (22 plus its 2 dp halo), 10 in from the end edge (12 to what shows)
        assertEquals(264, Reactions.chipStart(300, 26, 10))          // a long bubble: 10 from the end, far from the start
        assertEquals(300 - 10, Reactions.chipStart(300, 26, 10) + 26)
        assertEquals(34, Reactions.chipStart(70, 26, 10))            // still room for the inset on both sides
    }

    @Test fun aShortBubbleGetsTheChipCentredUnderIt() {
        // "ㅇㅇ" or one emoji: 15 + text + 15; the chip would come closer to the start edge than to the end edge
        assertEquals(10, Reactions.chipStart(46, 26, 10))            // exactly where both rules meet
        assertEquals(7, Reactions.chipStart(40, 26, 10))             // centred: 7 on each side
        assertEquals(-2, Reactions.chipStart(22, 26, 10))            // narrower than the chip: centred, never start-aligned past it
        for (w in 0..400) {
            val x = Reactions.chipStart(w, 26, 10)
            assertTrue("never past the end inset ($w)", x + 26 <= maxOf(w - 10, (w + 26) / 2 + 1))
            assertTrue("never nearer the start than the end ($w)", x >= w - 26 - x - 1)
        }
    }
}
