package chat.kelo.app.chat

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * The chat's view model against a local fake server (MockWebServer): 다시 시도 and attachments go through the same calls as the
 * composer. Nothing here reaches a real server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val vms = chat.kelo.app.TestViewModels()
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val bodies = Collections.synchronizedMap(mutableMapOf<RecordedRequest, String>())
    /** answers for PUT /api/uploads/{name}, by name */
    private val uploadAnswers = Collections.synchronizedMap(mutableMapOf<String, MockResponse>())
    private var history = """[]"""
    /** GET /api/approvals/pending */
    @Volatile private var pending = """{"items":[]}"""
    private val release = CountDownLatch(1)
    /** POST /api/turns without "secrets": "allow" answers the 409 secret_in_message (null: a normal start) */
    @Volatile private var secretAnswer: String? = null
    /** POST /api/turns answers 428 ai_consent_required until PUT /api/ai-consent came (AiConsent) */
    @Volatile private var consentNeeded = false
    /** GET /api/uploads/limits (null: an old server, 404); parts of 10 bytes are cut by the stand-in below */
    @Volatile private var limitsAnswer: String? = null

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Before fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bodies[request] = request.body.readUtf8()
                requests += request
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/api/messages") -> MockResponse().setBody(history)
                    request.method == "GET" && path.startsWith("/api/providers") -> MockResponse().setBody("[]")
                    request.method == "PUT" && path == "/api/ai-consent" -> { consentNeeded = false; MockResponse().setBody("""{"accepted":true}""") }
                    request.method == "POST" && path == "/api/turns" && consentNeeded ->
                        MockResponse().setResponseCode(428).setBody(
                            """{"detail":{"code":"ai_consent_required","company":"Anthropic","privacy_url":"https://kelo.chat/privacy","version":1}}""")
                    request.method == "POST" && path == "/api/turns" && secretAnswer != null && !bodies[request]!!.contains("\"secrets\":\"allow\"") ->
                        MockResponse().setResponseCode(409).setBody(secretAnswer!!)
                    request.method == "POST" && path == "/api/turns" -> MockResponse().setBody("""{"id":"t1"}""")
                    request.method == "GET" && path.startsWith("/api/turns/t1") ->
                        MockResponse().setBody("""{"events":[{"type":"text","text":"새 답"}],"next":1,"done":true}""")
                    request.method == "GET" && path == "/api/uploads/limits" ->
                        limitsAnswer?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}""")
                    request.method == "POST" && path.startsWith("/api/uploads/") && path.endsWith("/parts") -> MockResponse().setBody("""{"upload_id":"u1","part_size":10,"parts":3}""")
                    request.method == "PUT" && path.startsWith("/api/uploads/parts/") -> MockResponse().setBody("""{"ok":true,"received":1}""")
                    request.method == "POST" && path == "/api/uploads/parts/u1/finish" -> MockResponse().setBody("""{"path":"uploads/big.mp4","ok":true}""")
                    request.method == "PUT" && path.startsWith("/api/uploads/") -> {
                        val name = java.net.URLDecoder.decode(path.removePrefix("/api/uploads/"), "UTF-8")
                        if (name == "slow.pdf") release.await(5, TimeUnit.SECONDS)
                        uploadAnswers[name] ?: MockResponse().setBody("""{"path":"uploads/$name","ok":true}""")
                    }
                    request.method == "DELETE" && path.startsWith("/api/uploads/") -> MockResponse().setBody("""{"ok":true}""")
                    request.method == "GET" && path == "/api/approvals/pending" -> MockResponse().setBody(pending)
                    request.method == "POST" && path.startsWith("/api/approvals/") ->
                        MockResponse().setBody("""{"ok":true,"decision":"allowed_always","remembered":true,"rule":null}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"detail":"no"}""")
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        release.countDown()
        server.shutdown()
        vms.clear(); Dispatchers.resetMain()
    }

    private fun vm(): ChatViewModel {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        return vms.make { ChatViewModel(Sessions(store, requireE2e = false)) }
    }

    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun sent(method: String, prefix: String) = synchronized(requests) { requests.filter { it.method == method && it.path.orEmpty().startsWith(prefix) } }

    private fun turnTexts() = sent("POST", "/api/turns").map { Json.parseToJsonElement(bodies[it]!!).jsonObject["text"]!!.jsonPrimitive.content }

    private val words = AttachmentWords(line = { "📎 $it" }, ask = { "$it 읽어줘" }, separator = ", ")

    @Test fun retrySendsTheQuestionAgainLikeTheComposer() {
        history = """[{"id":1,"role":"user","content":"오늘 날씨?"},{"id":2,"role":"assistant","content":"맑아요"}]"""
        val vm = vm()
        vm.reload()
        until("history") { vm.state.value.loaded && vm.state.value.items.size == 2 }

        vm.retry("m1")   // the user's own message: not something to try again
        vm.retry("m2")
        until("the reply") { !vm.state.value.busy && vm.state.value.items.size == 4 }

        assertEquals(listOf("오늘 날씨?"), turnTexts())
        val items = vm.state.value.items
        assertEquals(ChatItem.Msg::class, items[2]::class)
        assertTrue((items[2] as ChatItem.Msg).mine)
        assertEquals("오늘 날씨?", (items[2] as ChatItem.Msg).text)
        assertEquals("새 답", (items[3] as ChatItem.Msg).text)
    }

    @Test fun clearingTheChatHistoryEmptiesTheRowsOnScreen() {
        history = """[{"id":1,"role":"user","content":"오늘 날씨?"},{"id":2,"role":"assistant","content":"맑아요"}]"""
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sessions = Sessions(SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }, requireE2e = false)
        val vm = vms.make { ChatViewModel(sessions) }
        vm.reload()
        until("history") { vm.state.value.loaded && vm.state.value.items.size == 2 }
        history = "[]"   // the server cleared it (마이 → 대화 기록 삭제)
        sessions.historyWasCleared()
        until("emptied") { vm.state.value.items.isEmpty() && !vm.state.value.hasOlder }
        until("asked again") { sent("GET", "/api/messages").size >= 2 }
    }

    @Test fun theFollowedReplyIsReportedForNotifications() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val watch = object : chat.kelo.app.chat.TurnWatch {
            override fun started() { calls += "started" }
            override fun event(ev: TurnEvent) { if (ev is TurnEvent.Text) calls += "text:" + ev.text }
            override fun finished(ok: Boolean) { calls += "finished:$ok" }
            override fun cancelled() { calls += "cancelled" }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        val vm = vms.make { ChatViewModel(Sessions(store, requireE2e = false), watch = watch) }
        vm.setInput("안녕")
        vm.send()
        until("the reply") { !vm.state.value.busy && calls.any { it.startsWith("finished") } }
        assertEquals(listOf("started", "text:새 답", "finished:true"), calls.toList())
    }

    @Test fun attachmentsUploadAtOnceAndGoWithTheNextMessage() {
        val vm = vm()
        vm.attach(image = false, label = "보고서") { Prepared("report.pdf", ByteArray(100_000) { 1 }, "application/pdf") }
        until("the upload") { vm.state.value.attachments.singleOrNull()?.state == AttachState.DONE }
        val a = vm.state.value.attachments.single()
        assertEquals("report.pdf", a.name)
        assertEquals("report.pdf", a.stored)
        assertEquals(1f, a.progress)
        val put = sent("PUT", "/api/uploads/").single()
        assertEquals("application/pdf", put.getHeader("Content-Type"))
        assertEquals("Bearer tok", put.getHeader("Authorization"))

        vm.setInput("요약해줘")
        vm.send(words = words)
        until("the reply") { !vm.state.value.busy && turnTexts().isNotEmpty() }
        assertEquals(listOf("요약해줘\n\n📎 report.pdf"), turnTexts())
        assertTrue(vm.state.value.attachments.isEmpty())
        assertEquals("", vm.state.value.input)
    }

    @Test fun onlyAttachmentsAskToReadThem() {
        val vm = vm()
        vm.attach(image = false, label = "a") { Prepared("a.pdf", byteArrayOf(1), "application/pdf") }
        vm.attach(image = false, label = "b") { Prepared("b.docx", byteArrayOf(1), null) }
        until("both uploads") { vm.state.value.ready.size == 2 }
        vm.send(words = words)
        until("the reply") { turnTexts().isNotEmpty() }
        assertEquals("a.pdf, b.docx 읽어줘", turnTexts().single())
    }

    @Test fun aRejectedPhotoShowsTheServersWordsAndStaysOutOfTheMessage() {
        uploadAnswers["IMG_1.jpg"] = MockResponse().setResponseCode(422).setBody("""{"detail":"only .docx, .pdf, .xlsm, .xlsx files can be uploaded"}""")
        val vm = vm()
        vm.attach(image = true, label = "사진") { Prepared("IMG_1.jpg", byteArrayOf(1, 2, 3), "image/jpeg") }
        until("the failure") { vm.state.value.attachments.singleOrNull()?.state == AttachState.FAILED }
        assertEquals(AttachError.Server("only .docx, .pdf, .xlsm, .xlsx files can be uploaded"), vm.state.value.attachments.single().error)
        assertEquals("image/jpeg", sent("PUT", "/api/uploads/").single().getHeader("Content-Type"))

        vm.setInput("이 사진 봐줘")
        vm.send(words = words)
        until("the reply") { turnTexts().isNotEmpty() }
        assertEquals(listOf("이 사진 봐줘"), turnTexts())
    }

    @Test fun sendingWaitsForAnUploadAndCancellingStopsIt() {
        val vm = vm()
        vm.attach(image = false, label = "slow") { Prepared("slow.pdf", byteArrayOf(1), "application/pdf") }
        until("the upload to start") { sent("PUT", "/api/uploads/slow.pdf").isNotEmpty() }
        assertTrue(vm.state.value.uploading)
        vm.setInput("hi")
        vm.send(words = words)
        assertFalse(vm.state.value.busy)
        assertTrue(turnTexts().isEmpty())

        vm.removeAttachment(vm.state.value.attachments.single().id)
        assertTrue(vm.state.value.attachments.isEmpty())
        assertFalse(vm.state.value.uploading)
        assertTrue(sent("DELETE", "/api/uploads/").isEmpty())   // nothing was stored yet
    }

    @Test fun removingAnUploadedFileTakesItOffTheServer() {
        val vm = vm()
        vm.attach(image = false, label = "x") { Prepared("x y.pdf", byteArrayOf(1), "application/pdf") }
        until("the upload") { vm.state.value.ready.isNotEmpty() }
        vm.removeAttachment(vm.state.value.attachments.single().id)
        until("the delete") { sent("DELETE", "/api/uploads/").isNotEmpty() }
        assertEquals("/api/uploads/x%20y.pdf", sent("DELETE", "/api/uploads/").single().path)
    }

    @Test fun aVideoGoesUpAsAStreamAndItsCopyIsDeletedAfterwards() {
        val vm = vm()
        var cleaned = false
        vm.attach(image = false, label = "clip") {
            Prepared("clip.mp4", null, "video/mp4", length = 5, open = { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) }, done = { cleaned = true })
        }
        until("the upload") { vm.state.value.attachments.singleOrNull()?.state == AttachState.DONE }
        val put = sent("PUT", "/api/uploads/").single()
        assertEquals(5L, put.bodySize)
        assertEquals("video/mp4", put.getHeader("Content-Type"))
        until("the cleanup") { cleaned }
    }

    @Test fun anOldServerRefusesAFileOver99MbBeforeAnythingIsSent() {
        val vm = vm()   // limitsAnswer null: GET /api/uploads/limits is a 404
        vm.attach(image = false, label = "huge") {
            Prepared("huge.mp4", null, "video/mp4", length = 99_000_001L, open = { error("must not be read") })
        }
        until("the failure") { vm.state.value.attachments.singleOrNull()?.state == AttachState.FAILED }
        assertEquals(AttachError.TooLarge(99_000_000L), vm.state.value.attachments.single().error)
        assertTrue(sent("PUT", "/api/uploads/").isEmpty())
    }

    @Test fun aFileTooBigNeverLeavesThePhone() {
        val vm = vm()
        vm.attach(image = false, label = "big") { throw AttachmentTooLarge(32_000_000L) }
        until("the failure") { vm.state.value.attachments.singleOrNull()?.state == AttachState.FAILED }
        assertEquals(AttachError.TooLarge(32_000_000L), vm.state.value.attachments.single().error)
        assertTrue(sent("PUT", "/api/uploads/").isEmpty())
    }

    @Test fun aFileOverTheRoutesOneRequestLimitGoesUpInPartsAndTheChipFinishes() {
        limitsAnswer = """{"single_max":10,"part_size":10,"image_max":15000000}"""
        val vm = vm()
        vm.attach(image = false, label = "big") {
            Prepared("big.mp4", null, "video/mp4", length = 25, open = { java.io.ByteArrayInputStream(ByteArray(25)) })
        }
        until("the upload") { vm.state.value.attachments.singleOrNull()?.state == AttachState.DONE }
        assertEquals(3, sent("PUT", "/api/uploads/parts/u1/").size)
        assertEquals("big.mp4", vm.state.value.attachments.single().stored)
        assertTrue(sent("PUT", "/api/uploads/big.mp4").isEmpty())
    }

    @Test fun theServersWordsShowOnTheChipWhenItHasNoRoom() {
        uploadAnswers["full.pdf"] = MockResponse().setResponseCode(507).setBody("""{"detail":"The server is out of space."}""")
        val vm = vm()
        vm.attach(image = false, label = "f") { Prepared("full.pdf", null, "application/pdf", length = 3, open = { java.io.ByteArrayInputStream(ByteArray(3)) }) }
        until("the failure") { vm.state.value.attachments.singleOrNull()?.state == AttachState.FAILED }
        assertEquals(AttachError.Server("The server is out of space."), vm.state.value.attachments.single().error)
    }

    private val jobAsk = """{"items":[{"type":"approval_request","approval_id":"ap1","id":"c1","name":"browser_click","label":"Click",
        "detail":"Book the ticket","site":"tickets.example.com","can_remember":true,"task_id":7,"turn_id":"tw","asked_at":100.0,
        "unattended":true,"source":"worker","worker_id":"w1","title":"Find the cheapest ticket"}]}"""

    @Test fun aBackgroundJobsQuestionShowsInTheChatAndIsAnsweredThere() {
        val vm = vm()
        pending = jobAsk
        vm.refreshAsks(force = true)
        until("the waiting card") { vm.state.value.asks.size == 1 }
        val ask = vm.state.value.asks.single()
        assertEquals(AskOrigin.JOB, ChatAsks.origin(ask.approval))
        assertEquals("Find the cheapest ticket", ask.approval.title)

        vm.answerAsk("ap1", approve = true, rememberSite = true)
        until("the answer") { vm.state.value.asks.single().end == AskEnd.ALWAYS }
        val post = sent("POST", "/api/approvals/ap1").single()
        val body = Json.parseToJsonElement(bodies[post]!!).jsonObject
        assertEquals("true", body["approve"]!!.jsonPrimitive.content)
        assertEquals("site", body["remember"]!!.jsonPrimitive.content)

        // the server no longer lists it: the short line stays until the chat moves on
        pending = """{"items":[]}"""
        vm.refreshAsks(force = true)
        Thread.sleep(100)
        assertEquals(AskEnd.ALWAYS, vm.state.value.asks.single().end)
        vm.answerAsk("ap1", approve = false)   // already answered: nothing is sent again
        Thread.sleep(50)
        assertEquals(1, sent("POST", "/api/approvals/").size)
    }

    @Test fun aQuestionAnsweredOnTheWorkPageLeavesTheChat() {
        val vm = vm()
        pending = jobAsk
        vm.refreshAsks(force = true)
        until("the waiting card") { vm.state.value.asks.size == 1 }
        pending = """{"items":[]}"""
        vm.refreshAsks(force = true)
        until("the card to leave") { vm.state.value.asks.isEmpty() }
        assertTrue(sent("POST", "/api/approvals/").isEmpty())
    }

    // ---- asking once before chats go to the AI service (AiConsent)
    @Test fun everyMessageAsksTheServerToCheckConsent() {
        val vm = vm()
        vm.setInput("안녕")
        vm.send()
        until("the reply") { !vm.state.value.busy && turnBodies().size == 1 }
        assertEquals("check", turnBodies()[0]["ai_consent"]!!.jsonPrimitive.content)
    }

    @Test fun notNowSendsNothingAndKeepsTheText() {
        consentNeeded = true
        val vm = vm()
        vm.setInput("첫 메시지")
        vm.send()
        until("the question") { vm.state.value.consentAsk != null && !vm.state.value.busy }
        val s = vm.state.value
        assertEquals("Anthropic", s.consentAsk!!.need.company)
        assertEquals("https://kelo.chat/privacy", s.consentAsk!!.need.privacyUrl)
        assertEquals("첫 메시지", s.input)                                      // back in the composer
        assertTrue(s.items.none { it is ChatItem.Msg && it.mine })              // no bubble
        assertTrue(s.items.none { it is ChatItem.Error })                       // no error row
        vm.consentNotNow()
        assertEquals(null, vm.state.value.consentAsk)
        assertEquals("첫 메시지", vm.state.value.input)
        assertEquals(0, sent("PUT", "/api/ai-consent").size)
        vm.send()                                                               // still not agreed: asked again, nothing sent
        until("the question again") { vm.state.value.consentAsk != null && !vm.state.value.busy }
    }

    @Test fun agreeRecordsItOnTheServerThenSendsTheSameMessage() {
        consentNeeded = true
        val vm = vm()
        vm.setInput("첫 메시지")
        vm.send()
        until("the question") { vm.state.value.consentAsk != null && !vm.state.value.busy }
        vm.consentAgree()
        until("the reply") { !vm.state.value.busy && turnBodies().size == 2 && vm.state.value.items.size == 2 }
        assertEquals(1, sent("PUT", "/api/ai-consent").size)
        assertEquals("""{"accepted":true}""", bodies[sent("PUT", "/api/ai-consent")[0]])
        assertEquals("첫 메시지", turnBodies()[1]["text"]!!.jsonPrimitive.content)
        assertEquals("", vm.state.value.input)
        assertEquals(null, vm.state.value.consentAsk)
    }

    // ---- a message held back for a key or password (SecretGuard)
    private val key = "sk-ant-api03-" + "Q".repeat(40)

    private fun heldBack(text: String): String {
        val at = text.indexOf(key)
        return """{"detail":{"code":"secret_in_message","spans":[{"start":$at,"end":${at + key.length},"kind":"api_key"}],""" +
            """"placeholder":"[hidden key]","target":{"type":"provider","id":"anthropic"},"label":"Anthropic"}}"""
    }

    private fun turnBodies() = sent("POST", "/api/turns").map { Json.parseToJsonElement(bodies[it]!!).jsonObject }

    @Test fun aHeldBackMessageLeavesNoBubbleAndComesBackToTheComposer() {
        val typed = "  내 키 🙂 $key 저장해줘 "
        secretAnswer = heldBack(typed.trim())
        val vm = vm()
        vm.setInput(typed)
        vm.send()
        until("the sheet") { vm.state.value.secretAsk != null && !vm.state.value.busy }
        val s = vm.state.value
        assertEquals(typed, s.input)                                          // the composer as it was
        assertTrue(s.items.none { it is ChatItem.Msg && it.mine })            // no sent bubble left behind
        assertTrue(s.items.none { it is ChatItem.Error })                     // and no error row
        assertEquals(listOf(key), s.secretAsk!!.values)
        assertFalse(s.secretAsk.toString().contains(key))                     // never printed

        vm.secretEdit()                                                       // "Edit": back to the composer, text intact
        assertEquals(null, vm.state.value.secretAsk)
        assertEquals(typed, vm.state.value.input)
        assertEquals(1, turnBodies().size)
    }

    @Test fun sendAsIsSendsTheSameTextAgainWithSecretsAllow() {
        val typed = "키 $key 써줘"
        secretAnswer = heldBack(typed)
        val vm = vm()
        vm.setInput(typed)
        vm.send()
        until("the sheet") { vm.state.value.secretAsk != null && !vm.state.value.busy }
        vm.secretSendAsIs()
        until("the reply") { !vm.state.value.busy && turnBodies().size == 2 && vm.state.value.items.size == 2 }
        val (first, second) = turnBodies()
        assertEquals(typed, first["text"]!!.jsonPrimitive.content)
        assertEquals(null, first["secrets"])
        assertEquals(typed, second["text"]!!.jsonPrimitive.content)            // identical text, value included
        assertEquals("allow", second["secrets"]!!.jsonPrimitive.content)
        assertEquals("", vm.state.value.input)
        assertEquals(null, vm.state.value.secretAsk)
    }

    @Test fun savedInTheKeychainPutsAReferenceInTheComposerAndDropsTheValue() {
        val typed = "👩‍💻 키는 $key 이고 이걸로 정리해줘"
        secretAnswer = heldBack(typed)
        val vm = vm()
        vm.setInput(typed)
        vm.send()
        until("the sheet") { vm.state.value.secretAsk != null && !vm.state.value.busy }
        vm.secretSaved(key, "(보관함에 저장함: Anthropic)")
        assertEquals("👩‍💻 키는 (보관함에 저장함: Anthropic) 이고 이걸로 정리해줘", vm.state.value.input)
        assertEquals(null, vm.state.value.secretAsk)                          // the sheet (and the value with it) is gone
        assertFalse(vm.state.value.toString().contains(key))
        assertEquals(1, turnBodies().size)                                    // nothing was sent again
    }

    @Test fun aCardGoesToTheCardFormThroughTheHandOff() {
        val card = "4111 1111 1111 1111"
        val typed = "카드 $card 로 결제해"
        val at = typed.indexOf(card)
        secretAnswer = """{"detail":{"code":"secret_in_message","spans":[{"start":$at,"end":${at + card.length},"kind":"card"}],"placeholder":"[card]","target":{"type":"wallet","id":""}}}"""
        val vm = vm()
        vm.setInput(typed)
        vm.send()
        until("the sheet") { vm.state.value.secretAsk != null && !vm.state.value.busy }
        val handoff = SecretHandoff()
        vm.secretToCard(handoff)
        assertEquals(null, vm.state.value.secretAsk)
        assertEquals(card, handoff.peek())
        handoff.clear()                                                        // the card form took it
        vm.secretCardSaved("(saved to Keychain: Payment card)")
        assertEquals("카드 (saved to Keychain: Payment card) 로 결제해", vm.state.value.input)
    }
}
