package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollTest {
    @Test fun bottomIsTheNewestRowWithinTheSlop() {
        assertTrue(ChatScroll.atBottom(0, 0, 24))
        assertTrue(ChatScroll.atBottom(0, 24, 24))
        assertFalse(ChatScroll.atBottom(0, 25, 24))
        assertFalse(ChatScroll.atBottom(3, 0, 24))
    }

    @Test fun followsOnlyNearTheBottomOrAfterSending() {
        assertTrue(ChatScroll.follow(0, lastIsMine = false))
        assertTrue(ChatScroll.follow(1, lastIsMine = false))
        assertFalse(ChatScroll.follow(5, lastIsMine = false))
        assertTrue(ChatScroll.follow(5, lastIsMine = true))
    }

    @Test fun theDotLightsWhileUpAndClearsAtTheBottom() {
        assertTrue(ChatScroll.unread(was = false, atBottom = false, replyGrew = true))
        assertTrue(ChatScroll.unread(was = true, atBottom = false, replyGrew = false))
        assertFalse(ChatScroll.unread(was = true, atBottom = true, replyGrew = true))
        assertFalse(ChatScroll.unread(was = false, atBottom = false, replyGrew = false))
    }

    @Test fun theSignatureMovesWithTheAssistantOnly() {
        val a = listOf(ChatItem.Msg("m1", mine = false, text = "안녕"))
        val grown = listOf(ChatItem.Msg("m1", mine = false, text = "안녕하세요"))
        val withMine = a + ChatItem.Msg("k1", mine = true, text = "질문")
        val withOlder = listOf(ChatItem.Msg("m0", mine = false, text = "옛날")) + a
        assertEquals(-1L, ChatScroll.replySignature(emptyList()))
        assertNotEquals(ChatScroll.replySignature(a), ChatScroll.replySignature(grown))
        assertEquals(ChatScroll.replySignature(a), ChatScroll.replySignature(withMine))
        assertEquals(ChatScroll.replySignature(a), ChatScroll.replySignature(withOlder))
    }
}

class DraftTextTest {
    @Test fun sharedTextGoesAfterWhatIsTyped() {
        assertEquals("링크", DraftText.append("", "링크"))
        assertEquals("요약해 줘\n링크", DraftText.append("요약해 줘  ", "링크"))
        assertEquals("요약해 줘", DraftText.append("요약해 줘", "  "))
    }
}
