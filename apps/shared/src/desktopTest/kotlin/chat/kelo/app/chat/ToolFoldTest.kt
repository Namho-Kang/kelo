package chat.kelo.app.chat

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool chips of one kind in a row fold into one chip; one live browser card per reply and tab; a list on a permission card. */
class ToolFoldTest {
    private var n = 0
    private val key = { "k${++n}" }
    private fun run(vararg evs: TurnEvent, start: List<ChatItem> = emptyList()) = evs.fold(start) { items, ev -> ChatReducer.apply(items, ev, key) }
    private fun args(kv: List<Pair<String, String>>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
    private fun started(id: String, name: String, group: String = name, done: String = "", kv: List<Pair<String, String>> = emptyList()) =
        TurnEvent.ToolStarted(id, name, args(kv), label = "$name…", group = group, done = done)
    private fun ok(id: String, name: String) = TurnEvent.ToolResult(id, name, "ok", false, null)
    private fun failed(id: String, name: String) = TurnEvent.ToolResult(id, name, "Error", true, null)
    private fun user(text: String) = ChatItem.Msg(key(), mine = true, text = text)

    @Test fun consecutiveCallsOfOneKindFoldIntoOneChipThatGrowsInPlace() {
        val reads = (1..6).flatMap { i -> listOf(started("r$i", "email_read", done = "메일 {n}개 읽음", kv = listOf("uid" to "$i"))) }
        var items = run(started("l1", "email_list", done = "메일 {n}번 확인"), ok("l1", "email_list"), *reads.toTypedArray())
        var rows = ChatReducer.foldTools(items)
        assertEquals(2, rows.size)                                        // "메일 확인" + one chip for the six reads
        val chip = rows[1] as ChatItem.Tool
        assertTrue(chip.folded)
        assertEquals(6, chip.members.size)
        val firstKey = chip.key
        items = run(ok("r1", "email_read"), ok("r2", "email_read"), ok("r3", "email_read"), start = items)
        rows = ChatReducer.foldTools(items)
        val half = rows[1] as ChatItem.Tool
        assertEquals(firstKey, half.key)                                  // the same row, updated in place
        assertEquals(3, half.members.count { it.state == ToolState.OK })   // "메일 읽는 중 · 3/6"
        items = run(ok("r4", "email_read"), ok("r5", "email_read"), ok("r6", "email_read"), start = items)
        val done = ChatReducer.foldTools(items)[1] as ChatItem.Tool
        assertEquals("메일 6개 읽음", ChatReducer.foldedText(done, 6))
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), done.members.map { it.detail })   // the list behind the tap
        assertEquals(7, items.size)                                       // the items themselves stay one per call
    }

    @Test fun everyKindFoldsAndSomethingInBetweenStartsANewChip() {
        val items = run(
            started("s1", "web_search", done = "Searched the web {n} times", kv = listOf("query" to "a")), ok("s1", "web_search"),
            started("s2", "web_search", kv = listOf("query" to "b")), failed("s2", "web_search"),
            started("f1", "web_fetch", kv = listOf("url" to "https://www.example.org/x")), ok("f1", "web_fetch"),
            started("f2", "web_fetch"), ok("f2", "web_fetch"),
            TurnEvent.Text("중간 설명"),
            started("s3", "web_search"), ok("s3", "web_search"),
        )
        val rows = ChatReducer.foldTools(items)
        val chips = rows.filterIsInstance<ChatItem.Tool>()
        assertEquals(3, chips.size)
        assertEquals(listOf(2, 2, 0), chips.map { it.members.size })
        assertEquals("example.org", chips[1].members[0].detail)
        assertEquals("Searched the web 1 times", ChatReducer.foldedText(chips[0], chips[0].members.count { it.state == ToolState.OK }))
        // an older server sends no words for the folded chip: the label and the count
        assertEquals("web_fetch… · 2", ChatReducer.foldedText(chips[1], 2))
        assertFalse(chips[2].folded)
    }

    @Test fun theSameToolAskedTwiceIsOneChipWithBothCardsUnderIt() {
        val ask = { id: String, title: String -> TurnEvent.ApprovalRequest(id, "gcal_create_event", args(listOf("title" to title)), null, label = "일정 넣는 중") }
        val items = run(
            started("c1", "gcal_create_event", done = "일정 {n}개 넣음", kv = listOf("title" to "출국")), ask("a1", "출국"), ok("c1", "gcal_create_event"),
            started("c2", "gcal_create_event", done = "일정 {n}개 넣음", kv = listOf("title" to "귀국")), ask("a2", "귀국"), ok("c2", "gcal_create_event"),
        )
        val rows = ChatReducer.foldTools(items)
        assertEquals(3, rows.size)                                         // one chip, then both cards in their order
        val chip = rows[0] as ChatItem.Tool
        assertEquals(2, chip.members.size)
        assertEquals(listOf("a1", "a2"), rows.drop(1).map { (it as ChatItem.Approval).id })
        assertEquals("일정 2개 넣음", ChatReducer.foldedText(chip, ChatReducer.doneCount(chip)))
        // a message in between still starts a new chip
        val split = ChatReducer.foldTools(run(started("c1", "gcal_create_event"), ok("c1", "gcal_create_event"), TurnEvent.Text("하나 더"),
            started("c2", "gcal_create_event"), ok("c2", "gcal_create_event")))
        assertEquals(2, split.filterIsInstance<ChatItem.Tool>().size)
    }

    @Test fun onePhotoLookedAtTwiceCountsOnce() {
        val items = run(
            started("v1", "view_image", done = "사진 {n}장 봄", kv = listOf("name" to "IMG_1.jpg")), ok("v1", "view_image"),
            started("v2", "view_image", done = "사진 {n}장 봄", kv = listOf("name" to "IMG_1.jpg")), ok("v2", "view_image"),
        )
        val chip = ChatReducer.foldTools(items).single() as ChatItem.Tool
        assertEquals("사진 1장 봄", ChatReducer.foldedText(chip, ChatReducer.doneCount(chip)))
        val two = run(started("v3", "view_image", done = "사진 {n}장 봄", kv = listOf("name" to "IMG_2.jpg")), ok("v3", "view_image"), start = items)
        val both = ChatReducer.foldTools(two).single() as ChatItem.Tool
        assertEquals(2, ChatReducer.doneCount(both))
    }

    @Test fun aScreenshotShowsUnderTheReplyAndComesBackWithTheStoredMessage() {
        val ev = TurnEvent.parse(Json.parseToJsonElement(
            """{"type":"image","image_id":"img_1","url":"/api/chat-images/img_1","width":800,"height":500,"caption":"예치금"}""") as JsonObject)
        assertTrue(ev is TurnEvent.Image)
        // before any text: the reply starts with the picture; text that follows joins the same row
        var items = run(ev, TurnEvent.Text("이 화면이에요."))
        val m = items.single() as ChatItem.Msg
        assertEquals("이 화면이에요.", m.text)
        assertEquals("img_1", m.images.single().id)
        // after text: under it
        items = run(TurnEvent.Text("보세요"), ev)
        assertEquals(1, (items.single() as ChatItem.Msg).images.size)
        // a url that is not the server's own is left out
        val bad = TurnEvent.parse(Json.parseToJsonElement("""{"type":"image","url":"https://x.example/a.png"}""") as JsonObject)
        assertFalse(bad is TurnEvent.Image)
        val stored = Json { ignoreUnknownKeys = true }.decodeFromString<StoredMessage>(
            """{"id": 9, "role": "assistant", "content": "x", "images": [{"id": "img_1", "url": "/api/chat-images/img_1", "width": 8, "height": 5}]}""")
        assertEquals(8, stored.images.single().width)
    }

    @Test fun browserStepsShowAsTheCardNotAsChips() {
        val items = run(
            started("b1", "browser_open", group = "browser"), TurnEvent.Browser("b1", "A", "https://a.example/", "a.example", "사이트 여는 중", null, tab = "owner"),
            ok("b1", "browser_open"),
            started("b2", "browser_click", group = "browser"), TurnEvent.Browser("b2", "A", "u", "a.example", "페이지 누르는 중", null, tab = "owner"),
            ok("b2", "browser_click"),
            started("b3", "browser_open", group = "browser"), TurnEvent.Browser("b3", "B", "https://b.example/", "b.example", "사이트 여는 중", null, tab = "owner"),
            ok("b3", "browser_open"),
        )
        val rows = ChatReducer.foldTools(items)
        assertEquals(1, rows.size)                                        // one live card, no chips
        val card = rows.single() as ChatItem.Browser
        assertEquals("b.example", card.host)
        assertEquals(2, card.hosts.size)
        // without a card (an older server, no browser events) the steps fold into one chip
        val bare = ChatReducer.foldTools(run(started("x1", "browser_open", group = ""), ok("x1", "browser_open"),
            started("x2", "browser_read", group = ""), ok("x2", "browser_read")))
        assertEquals(1, bare.size)
        assertEquals(2, (bare.single() as ChatItem.Tool).members.size)
    }

    @Test fun aNewTabOrANewReplyGetsItsOwnCard() {
        var items = run(
            TurnEvent.Browser("b1", "A", "u", "a.example", "s", null, tab = "owner"),
            TurnEvent.Browser("b2", "J", "u", "j.example", "s", null, tab = "task:w1"),   // a job's tab: its own card
            TurnEvent.Browser("b3", "A", "u", "a.example", "s2", null, tab = "owner"),
        )
        assertEquals(2, items.filterIsInstance<ChatItem.Browser>().size)
        items = run(TurnEvent.Browser("b4", "A", "u", "a.example", "s3", null, tab = "owner"), start = items + user("또 해줘"))
        val cards = items.filterIsInstance<ChatItem.Browser>()
        assertEquals(3, cards.size)                                       // the next reply starts its own card
        assertEquals("s2", cards[0].status)
    }

    @Test fun aPermissionCardForManyCarriesItsListAndAStepLimitFromANewServerShowsNoErrorBubble() {
        val ev = TurnEvent.parse(Json.parseToJsonElement("""{"type":"approval_request","approval_id":"a1","id":"c1","name":"request_task_permission",
            "arguments":{},"detail":"d","label":"x","ask":"홍보 메일 보낸 곳 37개 모두: 구독 취소. 진행할까요?","items":["보낸 곳 1","보낸 곳 2",""]}""")) as TurnEvent.ApprovalRequest
        assertEquals(listOf("보낸 곳 1", "보낸 곳 2"), ev.items)
        val card = run(ev).single() as ChatItem.Approval
        assertEquals(2, card.items.size)
        val limit = TurnEvent.parse(Json.parseToJsonElement("""{"type":"step_limit","steps":30,"progress":12,"next":"background","job_id":"w1"}""")) as TurnEvent.StepLimit
        assertEquals("background", limit.next)
        assertTrue(run(limit).isEmpty())                                   // the server's own line says what follows
        assertEquals(ChatError.StepLimit, (run(TurnEvent.StepLimit(30)).single() as ChatItem.Error).error)   // an older server
        val started = TurnEvent.parse(Json.parseToJsonElement("""{"type":"tool_started","id":"t","name":"email_read","arguments":{},"label":"메일 읽는 중","group":"email_read","done":"메일 {n}개 읽음"}""")) as TurnEvent.ToolStarted
        assertEquals("메일 {n}개 읽음", started.done)
        assertEquals("email_read", started.group)
    }
}
