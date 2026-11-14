package chat.kelo.app.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private var n = 0
    private val key = { "k${++n}" }
    private fun run(vararg evs: TurnEvent, start: List<ChatItem> = emptyList()) =
        evs.fold(start) { items, ev -> ChatReducer.apply(items, ev, key) }

    private fun started(id: String, name: String, service: String? = null) =
        TurnEvent.ToolStarted(id, name, if (service == null) JsonObject(emptyMap()) else buildJsonObject { put("service", service) })

    private fun result(id: String, name: String, output: String = "", isError: Boolean = false, approved: Boolean? = null) =
        TurnEvent.ToolResult(id, name, output, isError, approved)

    @Test fun textJoinsTheLastAssistantMessage() {
        val items = run(TurnEvent.Text("안녕"), TurnEvent.Text("하세요"))
        assertEquals(listOf("안녕하세요"), items.map { (it as ChatItem.Msg).text })
        val afterUser = run(TurnEvent.Text("b"), start = listOf(ChatItem.Msg("u", mine = true, text = "a")))
        assertEquals(2, afterUser.size)
    }

    @Test fun toolChipStates() {
        assertEquals(ToolState.OK, ChatReducer.toolState(result("1", "x")))
        assertEquals(ToolState.OK, ChatReducer.toolState(result("1", "x", approved = true)))
        assertEquals(ToolState.ERROR, ChatReducer.toolState(result("1", "x", isError = true)))
        assertEquals(ToolState.ERROR, ChatReducer.toolState(result("1", "x", isError = true, approved = null)))
        // denied only when approved is exactly false
        assertEquals(ToolState.DENIED, ChatReducer.toolState(result("1", "x", isError = true, approved = false)))
        assertEquals(ToolState.DENIED, ChatReducer.toolState(result("1", "x", approved = false)))
    }

    @Test fun resultUpdatesTheNewestRunningChipWithThatId() {
        val items = run(
            started("a", "web"), result("a", "web"),       // the first "a" finished
            started("a", "web"),                          // a CLI reused the id
            result("a", "web", isError = true),
        )
        val chips = items.filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf(ToolState.OK, ToolState.ERROR), chips.map { it.state })
    }

    @Test fun resultWithoutChipChangesNothing() {
        val items = run(started("a", "web"), result("zzz", "web"))
        assertEquals(ToolState.RUNNING, (items.single() as ChatItem.Tool).state)
    }

    @Test fun connectServiceAddsACard() {
        val items = run(started("c", "connect_service", service = "github"), result("c", "connect_service", output = "the card is shown"))
        assertEquals("github", (items.last() as ChatItem.Connect).service)
        val already = run(started("c", "connect_service", "github"), result("c", "connect_service", output = "github is already connected"))
        assertTrue(already.none { it is ChatItem.Connect })
        val failed = run(started("c", "connect_service", "github"), result("c", "connect_service", isError = true))
        assertTrue(failed.none { it is ChatItem.Connect })
        val noService = run(started("c", "connect_service"), result("c", "connect_service"))
        assertTrue(noService.none { it is ChatItem.Connect })
    }

    @Test fun vaultCardUnlessRefused() {
        val out = "asked the user to enter the login for 'login-example-com' in the app"
        val ok = run(started("v", "request_login"), result("v", "request_login", output = out, approved = true))
        assertEquals("login-example-com", (ok.last() as ChatItem.Vault).name)
        val api = run(started("v", "request_api_access"), result("v", "request_api_access", output = "'weather-api' created", approved = true))
        assertEquals("weather-api", (api.last() as ChatItem.Vault).name)
        // the server no longer asks before a login form: no answer at all still shows the form
        assertEquals("login-example-com", (run(started("v", "request_login"), result("v", "request_login", output = out, approved = null)).last() as ChatItem.Vault).name)
        assertTrue(run(started("v", "request_login"), result("v", "request_login", output = out, approved = false)).none { it is ChatItem.Vault })
        assertTrue(run(started("v", "request_login"), result("v", "request_login", output = out, isError = true, approved = true)).none { it is ChatItem.Vault })
        assertNull(ChatReducer.vaultName("no quoted name here"))
    }

    @Test fun approvalAndNotices() {
        val items = run(
            TurnEvent.ApprovalRequest("ap1", "send_email", buildJsonObject { put("to", "a@b.c") }, "detail"),
            TurnEvent.Fallback("p", "m2", "rate limited"),
            TurnEvent.Retry("files only"),
            TurnEvent.Reconnecting,
            TurnEvent.StepLimit(40),
            TurnEvent.Error("boom"),
            TurnEvent.Done(1, 1, 1),
            TurnEvent.Thinking,
        )
        val ap = items[0] as ChatItem.Approval
        assertEquals("{\n  \"to\": \"a@b.c\"\n}", ap.args)
        assertEquals(ChatNotice.Fallback("m2", "rate limited"), (items[1] as ChatItem.Notice).notice)
        assertEquals(ChatNotice.Retry, (items[2] as ChatItem.Notice).notice)
        assertEquals(ChatNotice.Reconnecting, (items[3] as ChatItem.Notice).notice)
        assertEquals(ChatError.StepLimit, (items[4] as ChatItem.Error).error)
        assertEquals(ChatError.Message("boom"), (items[5] as ChatItem.Error).error)
        assertEquals(6, items.size)   // done and thinking add nothing
    }

    @Test fun phases() {
        val p0 = Phase(PhaseKind.WAITING, since = 0)
        val thinking = ChatReducer.phase(p0, TurnEvent.Thinking, 10)!!
        assertEquals(PhaseKind.THINKING, thinking.kind)
        assertSame(thinking, ChatReducer.phase(thinking, TurnEvent.Retry("x"), 20))   // same phase keeps its start time
        val tool = ChatReducer.phase(thinking, started("1", "web_search"), 30)!!
        assertEquals(PhaseKind.TOOL to "web_search", tool.kind to tool.label)
        assertEquals(30L, ChatReducer.phase(tool, TurnEvent.ToolStarted("2", "fetch", JsonObject(mapOf("a" to JsonPrimitive(1)))), 30)!!.since)
        assertEquals(PhaseKind.WAITING, ChatReducer.phase(tool, result("1", "web_search"), 40)!!.kind)
        assertEquals(PhaseKind.WRITING, ChatReducer.phase(tool, TurnEvent.Text("x"), 40)!!.kind)
        assertEquals(PhaseKind.APPROVAL, ChatReducer.phase(tool, TurnEvent.ApprovalRequest("a", "n", JsonObject(emptyMap()), null), 40)!!.kind)
        val sw = ChatReducer.phase(tool, TurnEvent.Fallback("p", "m", "r"), 40)!!
        assertEquals(PhaseKind.SWITCHING to "m", sw.kind to sw.label)
        assertSame(tool, ChatReducer.phase(tool, TurnEvent.Done(0, 0, 0), 50))
    }

    private fun ask(id: String, name: String) = TurnEvent.ApprovalRequest(id, name, JsonObject(emptyMap()), null)

    @Test fun answeringAQuestionFoldsItAndOnlyOnce() {
        val items = run(ask("a1", "send_email"))
        val allowed = ChatReducer.answer(items, "a1", true)
        assertEquals(true, (allowed.single() as ChatItem.Approval).answer)
        // a second tap (or the other button) changes nothing
        assertEquals(allowed, ChatReducer.answer(allowed, "a1", false))
        assertEquals(false, (ChatReducer.answer(items, "a1", false).single() as ChatItem.Approval).answer)
    }

    @Test fun theEndOfTheReplyExpiresUnansweredQuestions() {
        val items = ChatReducer.answer(run(ask("a1", "send_email"), ask("a2", "delete_file")), "a1", false)
        val ended = ChatReducer.endTurn(items)
        val (first, second) = ended.map { it as ChatItem.Approval }
        assertEquals(false, first.answer)
        assertTrue(!first.expired)
        assertTrue(second.expired)
        assertTrue(!second.open)
        assertSame(ended, ChatReducer.endTurn(ended))   // nothing left to expire: the same list
        assertTrue(ChatReducer.answer(ended, "a2", true) == ended)   // an expired question cannot be answered
    }

    @Test fun aToolResultSettlesAQuestionAnsweredElsewhere() {
        val yes = run(ask("a1", "send_email"), result("t", "send_email", approved = true))
        assertEquals(true, (yes[0] as ChatItem.Approval).answer)
        val timedOut = run(ask("a1", "send_email"), result("t", "send_email", isError = true, approved = false))
        assertTrue((timedOut[0] as ChatItem.Approval).expired)
        // this phone's own answer is kept
        val mine = run(result("t", "send_email", approved = false), start = ChatReducer.answer(run(ask("a1", "send_email")), "a1", true))
        assertEquals(true, (mine[0] as ChatItem.Approval).answer)
        assertTrue(!(mine[0] as ChatItem.Approval).expired)
    }

    @Test fun aNewCardForTheSameThingExpiresTheOlderOne() {
        val out = "enter the login for 'login-example-com'"
        val items = run(
            started("v", "request_login"), result("v", "request_login", output = out, approved = true),
            started("c", "connect_service", "github"), result("c", "connect_service", output = "shown"),
            started("v2", "request_login"), result("v2", "request_login", output = out, approved = true),
            started("c2", "connect_service", "notion"), result("c2", "connect_service", output = "shown"),
        )
        val vaults = items.filterIsInstance<ChatItem.Vault>()
        assertEquals(listOf(CardState.EXPIRED, CardState.OPEN), vaults.map { it.state })
        // another service's card leaves the github one open
        assertEquals(listOf(CardState.OPEN, CardState.OPEN), items.filterIsInstance<ChatItem.Connect>().map { it.state })
    }

    @Test fun settlingACardOnlyChangesAnOpenOne() {
        val items = run(started("c", "connect_service", "github"), result("c", "connect_service", output = "shown"))
        val card = items.last() as ChatItem.Connect
        val done = ChatReducer.settleCard(items, card.key, CardState.DONE)
        assertEquals(CardState.DONE, (done.last() as ChatItem.Connect).state)
        assertEquals(done, ChatReducer.settleCard(done, card.key, CardState.CANCELLED))
    }

    @Test fun aLongThinkIsSaidAfterAMinute() {
        val t = Phase(PhaseKind.THINKING, since = 1_000)
        assertFalse(ChatReducer.longThinking(t, 1_000 + ChatReducer.LONG_THINKING_MS - 1))
        assertTrue(ChatReducer.longThinking(t, 1_000 + ChatReducer.LONG_THINKING_MS))
        assertFalse(ChatReducer.longThinking(Phase(PhaseKind.WRITING, since = 0), 600_000))   // writing, not thinking
        // more reasoning keeps the same phase and its start, so the clock is the whole think
        assertEquals(t, ChatReducer.phase(t, TurnEvent.Thinking, 90_000))
    }
}
