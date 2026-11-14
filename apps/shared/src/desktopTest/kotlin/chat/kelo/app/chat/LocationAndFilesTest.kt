package chat.kelo.app.chat

import chat.kelo.app.res.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backlog 24 (the location card) and 26 (the user's files in their bubble): events, the list, and the pure rules of the screens. */
class LocationAndFilesTest {
    private var n = 0
    private val key = { "k${++n}" }
    private fun ev(json: String) = TurnEvent.parse(Json.parseToJsonElement(json))

    @Test fun theLocationCardComesWaitsAndEnds() {
        val waiting = ev("""{"type":"location_request","approval_id":"a1","state":"waiting","reason":"To find parking near you","settings":true,"expires_at":99}""")
        assertEquals(TurnEvent.LocationRequest("a1", "waiting", "To find parking near you", true, 99), waiting)
        var items = ChatReducer.apply(listOf(ChatItem.Msg("u", mine = true, text = "주차장 찾아줘")), waiting, key)
        val card = items.last() as ChatItem.LocationAsk
        assertTrue(card.open && card.settings); assertEquals("To find parking near you", card.reason)
        // this phone is reading and sending it; a repeated "waiting" does not undo that
        items = ChatReducer.locationState(items, "a1", ChatItem.LocationAsk.SENDING)
        items = ChatReducer.apply(items, waiting, key)
        assertEquals(ChatItem.LocationAsk.SENDING, (items.last() as ChatItem.LocationAsk).state)
        // the server's end state wins
        items = ChatReducer.apply(items, ev("""{"type":"location_request","approval_id":"a1","state":"allowed"}"""), key)
        assertEquals(ChatItem.LocationAsk.ALLOWED, (items.last() as ChatItem.LocationAsk).state)
        assertEquals(2, items.size)
        // an end event for a card this phone never showed adds nothing
        assertEquals(items, ChatReducer.apply(items, ev("""{"type":"location_request","approval_id":"zz","state":"expired"}"""), key))
    }

    @Test fun aCardNobodyAnsweredEndsWithTheReply() {
        val items = ChatReducer.apply(emptyList(), TurnEvent.LocationRequest("a1", "waiting", "", false, 0), key)
        assertEquals(ChatItem.LocationAsk.EXPIRED, (ChatReducer.endTurn(items).single() as ChatItem.LocationAsk).state)
        val done = ChatReducer.locationState(items, "a1", ChatItem.LocationAsk.ALLOWED)
        assertEquals(done, ChatReducer.endTurn(done))
    }

    @Test fun aWaitingCardComesBackFromThePendingListAfterAReload() {
        val pending = kotlinx.serialization.json.Json.parseToJsonElement("""{"items":[
            {"type":"location_request","kind":"location","approval_id":"a1","state":"waiting","reason":"For the weather","settings":false,"name":"my_location","asked_at":5},
            {"type":"approval_request","approval_id":"b1","name":"browser_click","asked_at":4}]}""")
        val locations = (pending as kotlinx.serialization.json.JsonObject)["items"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .mapNotNull { TurnEvent.parse(it) as? TurnEvent.LocationRequest }
        assertEquals(1, locations.size)
        // the location item is not read as an approval card
        assertTrue(chat.kelo.app.agent.PendingApproval.parseList(pending).none { it.id == "a1" })
        val reloaded = listOf<ChatItem>(ChatItem.Msg("m1", mine = true, text = "날씨 알려줘", id = 1))
        val shown = ChatReducer.withPendingLocations(reloaded, locations, busy = false)
        assertEquals("loc:a1", shown.last().key)
        assertEquals("For the weather", (shown.last() as ChatItem.LocationAsk).reason)
        assertEquals(shown, ChatReducer.withPendingLocations(shown, locations, busy = false))   // not twice
        // answered on another phone (or run out): no longer listed -> it ends here; a reply followed here says that itself
        assertEquals(ChatItem.LocationAsk.EXPIRED, (ChatReducer.withPendingLocations(shown, emptyList(), busy = false).last() as ChatItem.LocationAsk).state)
        assertEquals(shown, ChatReducer.withPendingLocations(shown, emptyList(), busy = true))
        // one this phone is sending stays as it is
        val sending = ChatReducer.locationState(shown, "a1", ChatItem.LocationAsk.SENDING)
        assertEquals(sending, ChatReducer.withPendingLocations(sending, emptyList(), busy = false))
    }

    @Test fun theCardsTapsAndEndLines() {
        assertEquals(LocationAskRules.Step.SEND, LocationAskRules.onShare(granted = true, settings = true, blocked = true))
        assertEquals(LocationAskRules.Step.ASK_SYSTEM, LocationAskRules.onShare(granted = false, settings = false, blocked = false))
        assertEquals(LocationAskRules.Step.OPEN_SETTINGS, LocationAskRules.onShare(granted = false, settings = true, blocked = false))
        assertEquals(LocationAskRules.Step.OPEN_SETTINGS, LocationAskRules.onShare(granted = false, settings = false, blocked = true))
        assertEquals(LocationAskRules.Step.TURN_ON_LOCATION, LocationAskRules.onShare(granted = true, settings = false, blocked = false, servicesOn = false))
        assertEquals(LocationAskRules.Step.TURN_ON_LOCATION, LocationAskRules.onShare(granted = false, settings = true, blocked = true, servicesOn = false))
        assertNull(LocationAskRules.endLine(ChatItem.LocationAsk.WAITING))
        assertNull(LocationAskRules.endLine(ChatItem.LocationAsk.SENDING))
        assertEquals(Res.string.location_ask_shared, LocationAskRules.endLine("allowed"))
        assertEquals(Res.string.location_ask_refused, LocationAskRules.endLine("refused"))
        assertEquals(Res.string.location_ask_expired, LocationAskRules.endLine("cancelled"))
    }

    @Test fun theUserMessageEventPutsTheFilesOnTheBubble() {
        val e = ev("""{"type":"user_message","message_id":41,"auto_text":true,"attachments":[
            {"name":"photo.jpg","path":"uploads/photo.jpg","kind":"image","mime":"image/jpeg","size":120,"url":"/api/uploads/photo.jpg/file","thumb":"/api/uploads/photo.jpg/thumb"},
            {"name":"report.pdf","path":"uploads/report.pdf","kind":"document","url":"/api/uploads/report.pdf/file","thumb":null},
            {"name":""}]}""") as TurnEvent.UserMessage
        assertEquals(41L, e.messageId); assertTrue(e.autoText)
        assertEquals(listOf("photo.jpg", "report.pdf"), e.attachments.map { it.name })
        assertTrue(e.attachments[0].visual); assertNull(e.attachments[1].thumb)
        val sent = ChatItem.Msg("u", mine = true, text = "photo.jpg 내용을 읽고 요약해줘.", files = listOf(MessageFile("photo.jpg", "uploads/photo.jpg", "image")))
        val items = ChatReducer.apply(listOf(sent), e, key)
        val m = items.single() as ChatItem.Msg
        assertEquals(41L, m.id); assertTrue(m.autoText); assertEquals("/api/uploads/photo.jpg/thumb", m.files[0].thumb)
    }

    @Test fun storedMessagesCarryTheirFiles() {
        val m = chat.kelo.app.core.KeloApi.json.decodeFromString(StoredMessage.serializer(),
            """{"id":3,"role":"user","content":"x","attachments":[{"name":"a.png","path":"uploads/a.png","kind":"image","thumb":"/t"}],"auto_text":true}""")
        assertEquals("a.png", m.attachments.single().name); assertTrue(m.autoText)
        assertTrue(chat.kelo.app.core.KeloApi.json.decodeFromString(StoredMessage.serializer(), """{"id":4}""").attachments.isEmpty())
    }

    @Test fun theBubbleHidesTheAppsOwnWords() {
        val files = listOf(MessageFile("a.jpg", "uploads/a.jpg", "image"), MessageFile("b.pdf", "uploads/b.pdf"))
        val line = { s: String -> "📎 $s" }
        assertEquals("", MessageFileRules.shownText("a.jpg, b.pdf 내용을 읽고 요약해줘.", files, autoText = true, line = line))
        assertEquals("이거 봐줘", MessageFileRules.shownText("이거 봐줘\n\n📎 uploads/a.jpg\n📎 uploads/b.pdf", files, autoText = false, line = line))
        assertEquals("plain", MessageFileRules.shownText("plain", emptyList(), autoText = true, line = line))
        assertEquals(listOf(1), MessageFileRules.rows(1))
        assertEquals(listOf(2), MessageFileRules.rows(2))
        assertEquals(listOf(2, 1), MessageFileRules.rows(3))
        assertEquals(listOf(2, 2), MessageFileRules.rows(4))
        assertEquals(emptyList<Int>(), MessageFileRules.rows(0))
        assertEquals("notes.docx", MessageFileRules.chipName("notes.docx"))
        val cut = MessageFileRules.chipName("family-trip-budget-third-quarter-2026-final-revised-v3.pdf")
        assertTrue(cut, cut.endsWith("v3.pdf") && cut.contains("…") && cut.length <= 26)
        // a Korean name counts its Hangul as 2 columns: cut once, within 26 columns, the ending kept
        val ko = MessageFileRules.chipName("2026년-3분기-가족여행-예산-정리-최종본-수정3.pdf")
        assertTrue(ko, ko.endsWith(".pdf") && ko.count { it == '…' } == 1 && MessageFileRules.columns(ko) <= 26)
        assertEquals(2, MessageFileRules.columns("가")); assertEquals(1, MessageFileRules.columns("a"))
    }

    @Test fun theTurnCarriesThePathsAndAutoText() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"id":"t1"}"""))
            val id = KeloTurnApi(chat.kelo.app.core.KeloApi(server.url("/").toString(), "t"))
                .start("x", "Asia/Seoul", emptyList(), null, listOf("uploads/a.jpg"), autoText = true)
            assertEquals("t1", id)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).toString()
            assertTrue(body, body.contains(""""attachments":[{"path":"uploads/a.jpg"}]""") && body.contains(""""auto_text":true"""))
        } finally {
            server.shutdown()
        }
    }
}
