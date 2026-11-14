package chat.kelo.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageActionsTest {
    private fun user(k: String, t: String) = ChatItem.Msg(k, mine = true, text = t)
    private fun reply(k: String, t: String) = ChatItem.Msg(k, mine = false, text = t)
    private fun error(k: String) = ChatItem.Error(k, ChatError.Network)
    private fun tool(k: String) = ChatItem.Tool(k, "t$k", "web_search", ToolState.OK)

    @Test fun retryResendsTheQuestionOfTheNewestReply() {
        val items = listOf(user("u1", "첫 질문"), reply("r1", "첫 답"), user("u2", "두 번째"), tool("t"), reply("r2", "두 번째 답"))
        assertEquals("두 번째", MessageActions.retryText(items, "r2"))
        assertNull(MessageActions.retryText(items, "r1"))   // an older reply
        assertNull(MessageActions.retryText(items, "u2"))   // the user's own message
        assertNull(MessageActions.retryText(items, "t"))
        assertNull(MessageActions.retryText(items, "nope"))
    }

    @Test fun aReplySplitByToolsCountsOnlyItsLastPart() {
        val items = listOf(user("u", "q"), reply("r1", "part one"), tool("t"), reply("r2", "part two"))
        assertNull(MessageActions.retryText(items, "r1"))
        assertEquals("q", MessageActions.retryText(items, "r2"))
    }

    @Test fun retryOnAnErrorAfterTheLastQuestion() {
        val items = listOf(user("u1", "a"), error("e1"), user("u2", "b"), reply("r", "x"), error("e2"))
        assertEquals("b", MessageActions.retryText(items, "e2"))
        assertNull(MessageActions.retryText(items, "e1"))   // the user asked again after it
        // the reply before a newer error can still be tried again (no message of the user after it)
        assertEquals("b", MessageActions.retryText(items, "r"))
    }

    @Test fun nothingToRetryWithoutAQuestionBefore() {
        val items = listOf(reply("r", "hello"), error("e"))
        assertNull(MessageActions.retryText(items, "r"))
        assertNull(MessageActions.retryText(items, "e"))
    }

    @Test fun theMenuForEachKindOfRow() {
        val items = listOf(user("u", "q"), reply("r", "a"), error("e"))
        assertEquals(listOf(MsgAction.COPY, MsgAction.SHARE, MsgAction.SELECT, MsgAction.EDIT), MessageActions.actions(items, "u", busy = false))
        assertEquals(listOf(MsgAction.COPY, MsgAction.SHARE, MsgAction.SELECT, MsgAction.RETRY, MsgAction.REPORT), MessageActions.actions(items, "r", busy = false))
        assertEquals(listOf(MsgAction.COPY, MsgAction.RETRY), MessageActions.actions(items, "e", busy = false))
        // while a reply is being written nothing is tried again
        assertEquals(listOf(MsgAction.COPY, MsgAction.SHARE, MsgAction.SELECT, MsgAction.REPORT), MessageActions.actions(items, "r", busy = true))
        assertEquals(listOf(MsgAction.COPY), MessageActions.actions(items, "e", busy = true))
        assertEquals(emptyList<MsgAction>(), MessageActions.actions(items + tool("t"), "t", busy = false))
    }
}
