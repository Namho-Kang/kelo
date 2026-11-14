package chat.kelo.app.chat

import chat.kelo.app.chat.vault.SaveAnswer
import chat.kelo.app.chat.vault.SecretEntryRules
import chat.kelo.app.chat.vault.SecretRequest
import chat.kelo.app.chat.vault.SecretResult
import chat.kelo.app.chat.vault.SecretRules
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Secure entries in the chat: the secret_request event, where the entries show, how a new state lands, when Save may be pressed. */
class SecretRequestsTest {
    private var n = 0
    private val key = { "k${++n}" }

    private fun ev(json: String): TurnEvent = TurnEvent.parse(Json.parseToJsonElement(json))

    private fun req(id: String, kind: String = "key", name: String = "telegram", state: String = "open", messageId: Long? = null,
                    source: String = "agent", createdAt: Double = 1.0) =
        SecretRequest(id, kind = kind, name = name, label = "Telegram bot", hosts = listOf("api.telegram.org"), state = state,
            messageId = messageId, source = source, createdAt = createdAt)

    @Test fun parsesTheEventWithoutAnyValue() {
        val e = ev("""{"type":"secret_request","id":"c1","request":{"id":"sr_1","kind":"key","name":"telegram","label":"Telegram bot",
            "hosts":["api.telegram.org"],"reason":"to send you messages","check":true,"source":"agent","state":"open","after_id":7,
            "created_at":1.5,"future_field":1}}""")
        val r = (e as TurnEvent.SecretRequested).request
        assertEquals("sr_1", r.id); assertTrue(r.isKey && r.open && r.check && r.fromAgent)
        assertEquals(listOf("api.telegram.org"), r.hosts)
        assertTrue(ev("""{"type":"secret_request","request":{}}""") is TurnEvent.Unknown)   // no id: nothing to show
        val saved = ev("""{"type":"secret_request","request":{"id":"sr_2","kind":"card","state":"saved",
            "result":{"card":{"id":"card_1","label":"My Visa","brand":"visa","last4":"4242"}}}}""") as TurnEvent.SecretRequested
        assertEquals("4242", saved.request.result?.card?.last4)
    }

    @Test fun theEntryAppearsWhereItWasAskedAndFoldsInPlace() {
        var items = listOf<ChatItem>(ChatItem.Msg("u", mine = true, text = "토큰 지금 보낼게"))
        items = ChatReducer.apply(items, TurnEvent.Text("아래 입력창에 넣어 주세요."), key)
        items = ChatReducer.apply(items, TurnEvent.SecretRequested(req("sr_1")), key)
        assertTrue(items.last() is ChatItem.Secret)
        items = ChatReducer.apply(items, TurnEvent.SecretRequested(req("sr_1", state = "saved")), key)
        assertEquals(3, items.size); assertEquals("saved", (items[2] as ChatItem.Secret).request.state)
        // the reply going on writes below the folded entry, in a new bubble
        items = ChatReducer.apply(items, TurnEvent.Text("저장됐어요."), key)
        assertEquals("저장됐어요.", (items.last() as ChatItem.Msg).text)
        // an answered entry never opens again, and a draft never shows in the chat
        assertEquals("saved", (ChatReducer.apply(items, TurnEvent.SecretRequested(req("sr_1")), key)[2] as ChatItem.Secret).request.state)
        assertEquals(items, ChatReducer.apply(items, TurnEvent.SecretRequested(req("sr_9", source = "draft")), key))
        // the reply ending takes the answered entry's status line away (a reload would not show it either)
        assertEquals(listOf("u", "k1", "k3"), ChatReducer.endTurn(items).map { it.key })
    }

    @Test fun aReplyEndingKeepsAnOpenEntry() {
        val items = ChatReducer.apply(listOf(ChatItem.Msg("u", mine = true, text = "토큰")), TurnEvent.SecretRequested(req("sr_1")), key)
        assertEquals(items, ChatReducer.endTurn(items))   // the server keeps it open
    }

    /**
     * The owner's report (2026-11-10): during a purchase run "로그인 정보를 넣었어요: 카카오 (선물하기 체험단)" stayed above the composer
     * after the run failed, and later. The login was saved from the server's list (its asking reply was never stored, so it had no
     * message id): it went to the end of the chat, and every reload put it at the end again. Now every end of a reply, and a reload,
     * clears such lines, and an answered entry is never placed by [ChatReducer.withPending].
     */
    @Test fun anAnsweredEntryNeverSticksAboveTheComposer() {
        val login = req("sr_k", kind = "login", name = "login-kakao", messageId = null).copy(label = "카카오 (선물하기 체험단)")
        var items = listOf<ChatItem>(ChatItem.Msg("u", mine = true, text = "선물하기 체험단 신청해 줘"))
        var secrets = listOf(login)
        // the open entry from GET /api/secret-requests shows at the end; the owner saves it here
        assertEquals("secret:sr_k", ChatReducer.withPending(items, secrets).last().key)
        val saved = login.copy(state = SecretRequest.STATE_SAVED)
        items = ChatReducer.placeAnswered(items, saved)
        secrets = secrets.map { if (it.id == "sr_k") saved else it }
        assertTrue(ChatReducer.isSettledEntry(items.last()))
        // the run fails: an error row, then the reply's end
        items = ChatReducer.apply(items, TurnEvent.Error("결제 페이지를 열 수 없어요"), key)
        for (end in listOf("done", "error", "cancelled")) {
            val ended = ChatReducer.endTurn(items)
            assertTrue(end, ended.none { it is ChatItem.Secret })
            assertTrue(end, ChatReducer.withPending(ended, SecretRules.settled(secrets)).none { it is ChatItem.Secret })
        }
        // a reload (new rows from the server) with the old kept list: still nothing at the end
        val reloaded = listOf<ChatItem>(ChatItem.Msg("m1", mine = true, text = "선물하기 체험단 신청해 줘", id = 1))
        assertEquals(reloaded, ChatReducer.withPending(reloaded, secrets))
        assertEquals(emptyList<SecretRequest>(), SecretRules.settled(secrets))
        // an entry the server still has open keeps showing
        assertEquals(listOf("sr_o"), SecretRules.settled(secrets + req("sr_o")).map { it.id })
    }

    @Test fun aNewerEntryForTheSameItemReplacesTheOlderOne() {
        var items = ChatReducer.apply(emptyList(), TurnEvent.SecretRequested(req("sr_1")), key)
        items = ChatReducer.apply(items, TurnEvent.SecretRequested(req("sr_2")), key)
        assertEquals(listOf("replaced", "open"), items.map { (it as ChatItem.Secret).request.state })
        items = ChatReducer.apply(items, TurnEvent.SecretRequested(req("sr_3", kind = "login", name = "login-shop")), key)
        assertEquals("open", (items[1] as ChatItem.Secret).request.state)
    }

    @Test fun noSecondLegacyFormForALoginTheServerAlreadyShows() {
        var items = ChatReducer.apply(emptyList(), TurnEvent.SecretRequested(req("sr_1", kind = "login", name = "login-shop-example-com")), key)
        items = ChatReducer.apply(items, TurnEvent.ToolResult("c1", "request_login", "... browser_login(name='login-shop-example-com')", false, null), key)
        assertTrue(items.none { it is ChatItem.Vault })
    }

    @Test fun pendingEntriesGoUnderTheirMessageOrAtTheEnd() {
        val items = listOf<ChatItem>(
            ChatItem.Msg("m1", mine = true, text = "토큰 보낼게", id = 1),
            ChatItem.Msg("m2", mine = false, text = "아래에 넣어 주세요", id = 2),
            ChatItem.Msg("m3", mine = true, text = "잠깐만", id = 3),
        )
        val out = ChatReducer.withPending(items, listOf(req("sr_a", messageId = 2), req("sr_b", messageId = 99, name = "x"),
            req("sr_c", messageId = null, name = "y"), req("sr_d", source = "draft", name = "z")))
        assertEquals(listOf("m1", "m2", "secret:sr_a", "m3", "secret:sr_b", "secret:sr_c"), out.map { it.key })
        // one already shown live is not shown twice
        val live = items + ChatItem.Secret("k9", req("sr_a", messageId = 2))
        assertEquals(live, ChatReducer.withPending(live, listOf(req("sr_a", messageId = 2))))
        assertEquals(items, ChatReducer.withPending(items, emptyList()))
    }

    @Test fun settlingAndRefreshingKeepWhatWasAnsweredHere() {
        val items = listOf<ChatItem>(ChatItem.Secret("k1", req("sr_1")))
        val done = ChatReducer.settleSecret(items, "sr_1", SecretRequest.STATE_SAVED, SecretResult(checked = "ok"))
        assertEquals("ok", (done[0] as ChatItem.Secret).request.result?.checked)
        val kept = listOf(req("sr_1", state = "saved"), req("sr_2"))
        // the server lists only open entries: sr_1 (answered here) and sr_2 (answered elsewhere) both leave the kept list
        assertEquals(listOf("sr_3"), SecretRules.refresh(kept, listOf(req("sr_3"))).map { it.id })
        // an entry answered here that a late list still shows open stays answered, and so is dropped
        assertEquals(listOf("sr_3"), SecretRules.refresh(kept, listOf(req("sr_1"), req("sr_3"))).map { it.id })
        assertEquals(listOf("sr_2", "sr_1"), SecretRules.waiting(listOf(req("sr_1", createdAt = 1.0), req("sr_2", createdAt = 2.0), req("sr_x", state = "saved"))).map { it.id })
        assertNull(SecretRules.upsert(emptyList(), req("sr_1")).first().messageId)
    }

    @Test fun whetherAReplyHereWillClearTheLine() {
        assertTrue(SecretRules.followsHere(SaveAnswer(turnId = "t1"), busy = false))                       // a reply to follow
        assertTrue(SecretRules.followsHere(SaveAnswer(turnId = "t1", reply = "started"), busy = false))
        assertFalse(SecretRules.followsHere(SaveAnswer(turnId = "t1", reply = "none"), busy = true))      // the server says none
        assertTrue(SecretRules.followsHere(SaveAnswer(), busy = true))                                    // the reply here goes on
        assertTrue(SecretRules.followsHere(SaveAnswer(reply = "live"), busy = true))
        assertFalse(SecretRules.followsHere(SaveAnswer(), busy = false))                                  // nothing runs here
        assertFalse(SecretRules.followsHere(SaveAnswer(reply = "live"), busy = false))                    // runs, but not followed here
        assertFalse(SecretRules.followsHere(SaveAnswer(reply = "queued"), busy = false))
        assertFalse(SecretRules.followsHere(null, busy = false))                                          // cancel / gone, nothing runs
        assertTrue(SecretRules.followsHere(null, busy = true))
        // older and newer answers parse
        assertEquals("live", Json { ignoreUnknownKeys = true }.decodeFromString(SaveAnswer.serializer(), """{"ok":true,"turn_id":null,"reply":"live"}""").reply)
        assertNull(Json.decodeFromString(SaveAnswer.serializer(), """{"ok":true}""").reply)
    }

    /** Case 1 (runtime contract): the reply the save carried on fails and ends here BEFORE the save's answer arrives. */
    @Test fun anAnswerArrivingAfterTheReplyEndedDoesNotBringTheLineBack() {
        val login = req("sr_k", kind = "login", name = "login-kakao")
        var items = ChatReducer.apply(listOf(ChatItem.Msg("u", mine = true, text = "신청해 줘")), TurnEvent.SecretRequested(login), key)
        items = ChatReducer.apply(items, TurnEvent.Error("결제 페이지를 열 수 없어요"), key)
        items = ChatReducer.endTurn(items)                      // the open entry stays: the server still waits for it
        assertTrue(items.any { it is ChatItem.Secret })
        // the PUT answer comes now: no reply runs here any more
        val answer = SaveAnswer(ok = true, reply = "live")
        items = ChatReducer.afterAnswer(items, login.copy(state = SecretRequest.STATE_SAVED), SecretRules.followsHere(answer, busy = false))
        assertTrue(items.none { it is ChatItem.Secret })
        // the same when the entry was shown only from the server's list (not in the rows): it is not placed
        val rows = listOf<ChatItem>(ChatItem.Msg("u", mine = true, text = "신청해 줘"))
        assertEquals(rows, ChatReducer.afterAnswer(rows, login.copy(state = SecretRequest.STATE_SAVED), SecretRules.followsHere(answer, busy = false)))
    }

    /** Case 2: the save answers turn_id null while no reply runs on this phone. */
    @Test fun aSaveWithNoReplyClearsTheLineAtOnce() {
        val login = req("sr_k", kind = "login", name = "login-kakao")
        val rows = listOf<ChatItem>(ChatItem.Msg("u", mine = true, text = "신청해 줘"), ChatItem.Secret("s", login))
        val saved = login.copy(state = SecretRequest.STATE_SAVED)
        assertEquals(listOf("u"), ChatReducer.afterAnswer(rows, saved, SecretRules.followsHere(SaveAnswer(), busy = false)).map { it.key })
        assertEquals(listOf("u"), ChatReducer.afterAnswer(rows, saved, SecretRules.followsHere(SaveAnswer(reply = "none"), busy = false)).map { it.key })
        // with a reply to follow, the line folds in place and that reply's end clears it
        val folded = ChatReducer.afterAnswer(rows, saved, SecretRules.followsHere(SaveAnswer(turnId = "t2", reply = "started"), busy = false))
        assertEquals("saved", (folded[1] as ChatItem.Secret).request.state)
        assertEquals(listOf("u"), ChatReducer.endTurn(folded).map { it.key })
        // a list refresh with no reply running drops answered lines; open ones stay
        assertEquals(listOf("u"), ChatReducer.dropSettled(folded).map { it.key })
        assertEquals(rows, ChatReducer.dropSettled(rows))
    }

    @Test fun saveIsOnlyOfferedForAValueTheServerTakes() {
        assertFalse(SecretEntryRules.keyReady("short"))
        assertFalse(SecretEntryRules.keyReady("has a space inside"))
        assertTrue(SecretEntryRules.keyReady("  123456789:AAH-token  "))
        assertTrue(SecretEntryRules.loginReady("me@example.com", "pw"))
        assertFalse(SecretEntryRules.loginReady(" ", "pw"))
        assertFalse(SecretEntryRules.loginReady("me", "a\nb"))
    }

}
