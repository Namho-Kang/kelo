package chat.kelo.desktop

import chat.kelo.app.chat.AttachState
import chat.kelo.desktop.chat.DesktopChat
import chat.kelo.desktop.chat.attachFile
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import chat.kelo.app.chat.AttachError
import chat.kelo.app.chat.AttachmentWords

import chat.kelo.app.chat.MessageFileRules
import chat.kelo.desktop.chat.AttachmentKinds

import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.ToolState
import chat.kelo.app.core.Session
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.FileKeyValueStore
import chat.kelo.desktop.core.NotePoller
import chat.kelo.desktop.core.StoreKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

/** The desktop chat against a scripted server: a streamed reply with a tool chip and an approval, uploads and their limit. */
class ChatModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var approved: Boolean? = null
    @Volatile private var turnBody: String = ""
    @Volatile private var pending = """{"items":[]}"""
    private lateinit var app: AppContainer

    private val events = listOf(
        """{"type":"thinking"}""",
        """{"type":"tool_started","id":"c1","name":"browser_open","arguments":{},"label":"Opening a site"}""",
        """{"type":"tool_result","id":"c1","name":"browser_open","output":"ok","is_error":false,"approved":null,"label":"Opening a site"}""",
        """{"type":"text","text":"Your dentist is on "}""",
        """{"type":"text","text":"Wednesday."}""",
        """{"type":"approval_request","approval_id":"ap1","id":"c2","name":"calendar_add","arguments":{},"label":"Adding an event","lines":[{"label":"Title","value":"Dentist"}]}""",
    )

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1))   // the view model works off the UI thread here
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path"
                if (request.getHeader("Authorization") != "Bearer tok-123456") return MockResponse().setResponseCode(401)
                return when {
                    request.method == "POST" && path == "/api/turns" -> { turnBody = request.body.readUtf8(); json("""{"id":"t1"}""") }
                    path.startsWith("/api/turns/t1") -> {
                        val after = Regex("after=(\\d+)").find(path)!!.groupValues[1].toInt()
                        val all = if (approved == true) events + """{"type":"tool_result","id":"c2","name":"calendar_add","output":"ok","is_error":false,"approved":true}""" +
                            """{"type":"done","input_tokens":10,"output_tokens":5,"steps":2}""" else events
                        if (after >= all.size && approved == null) Thread.sleep(200)
                        val list = all.drop(after.coerceAtMost(all.size))
                        json("""{"events":[${list.joinToString(",")}],"next":${after + list.size},"done":${approved == true && after + list.size >= all.size}}""")
                    }
                    request.method == "POST" && path == "/api/approvals/ap1" -> { approved = request.body.readUtf8().contains("true"); json("""{"ok":true}""") }
                    request.method == "PUT" && path.startsWith("/api/uploads/") -> json("""{"path":"uploads/${path.substringAfterLast('/')}"}""")
                    path.startsWith("/api/approvals/pending") -> json(pending)
                    path.startsWith("/api/messages") -> json("[]")
                    path.startsWith("/api/notifications") -> json(
                        """{"items":[{"id":5,"type":"proactive","title":"News","body":"A parcel arrived","seen":false}],"last_id":5}""",
                    )
                    else -> json("{}")
                }
            }
        }
        server.start()
        val dir = tmp.newFolder()
        app = AppContainer(dir, FileKeyValueStore(File(dir, "kelo.properties")), StoreKey.cipher(dir), requireE2e = false)
        app.sessions.signIn(Session(server.url("/").toString().trimEnd('/'), "tok-123456"))
    }

    @After fun tearDown() { server.shutdown(); Dispatchers.resetMain() }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun waitFor(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; requests: $requests")
            Thread.sleep(20)
        }
    }

    @Test fun aReplyStreamsInWithItsChipsAndWaitsForTheApproval() {
        val handle = DesktopChat(app)
        val chat = handle.vm
        chat.setInput("When is my dentist?")
        chat.send(words = AttachmentWords({ it }, { it }, ", "))
        waitFor("the approval card") { chat.state.value.items.any { it is ChatItem.Approval } }
        val s = chat.state.value
        assertTrue(s.busy)
        assertEquals("", s.input)
        val mine = s.items.first() as ChatItem.Msg
        assertTrue(mine.mine && mine.text == "When is my dentist?")
        assertEquals(ToolState.OK, (s.items.first { it is ChatItem.Tool } as ChatItem.Tool).state)
        assertEquals("Your dentist is on Wednesday.", s.items.filterIsInstance<ChatItem.Msg>().last().text)
        assertTrue(app.activity.following.value)

        chat.approve("ap1", true)
        waitFor("the end of the reply") { !chat.state.value.busy }
        val a = chat.state.value.items.first { it is ChatItem.Approval } as ChatItem.Approval
        assertEquals(true, a.answer)
        assertTrue(requests.any { it == "POST /api/approvals/ap1" })
        assertTrue(!app.activity.following.value)
        handle.close()
    }

    @Test fun filesGoUpAndAreNamedInTheMessage() {
        val handle = DesktopChat(app)
        val chat = handle.vm
        val f = tmp.newFile("report.pdf").apply { writeBytes(ByteArray(300_000) { 7 }) }
        chat.attachFile(f)
        waitFor("the upload") { chat.state.value.attachments.firstOrNull()?.state == AttachState.DONE }
        assertEquals("report.pdf", chat.state.value.attachments.first().stored)
        assertTrue(requests.any { it.startsWith("PUT /api/uploads/report.pdf") })
        chat.send(words = AttachmentWords({ "📎 $it" }, { "Read $it" }, ", "))
        waitFor("the message") { chat.state.value.items.any { it is ChatItem.Msg && it.mine } }
        val mine = chat.state.value.items.first() as ChatItem.Msg
        assertEquals("Read report.pdf", mine.text)
        // the files go with the message, and nothing typed means the app's own sentence: the bubble shows only the file
        assertEquals(listOf("report.pdf"), mine.files.map { it.path })
        assertEquals("document", mine.files.single().kind)
        assertTrue(mine.autoText)
        assertEquals("", MessageFileRules.shownText(mine.text, mine.files, mine.autoText) { "📎 $it" })
        waitFor("the turn") { turnBody.isNotEmpty() }
        val body = kotlinx.serialization.json.Json.parseToJsonElement(turnBody) as kotlinx.serialization.json.JsonObject
        assertEquals("""[{"path":"report.pdf"}]""", body["attachments"].toString())
        assertEquals("true", body["auto_text"].toString())
        assertTrue(chat.state.value.attachments.isEmpty())
        chat.stop()
        handle.close()
    }

    @Test fun aFileOver99MbOnAServerWithoutLimitsIsRefusedBeforeAnythingIsSent() {
        val handle = DesktopChat(app)   // this server has no GET /api/uploads/limits (404): an old server, one request up to 99 MB
        val chat = handle.vm
        val big = tmp.newFile("video.mp4")
        RandomAccessFile(big, "rw").use { it.setLength(99_000_001L) }   // sparse: nothing is written
        chat.attachFile(big)
        waitFor("the refusal") { chat.state.value.attachments.firstOrNull()?.state == AttachState.FAILED }
        assertEquals(AttachError.TooLarge(99_000_000L), chat.state.value.attachments.first().error)
        assertTrue(requests.none { it.startsWith("PUT /api/uploads/") })
        handle.close()
    }

    @Test fun notificationsAreNotReplayedOnTheFirstCheck() = runBlocking {
        val posted = mutableListOf<String>()
        val poller = NotePoller(app.settings, shown = { false }) { posted += it.body }
        poller.check(app.sessions.requireApi())
        assertTrue("the first check only notes where the list stands", posted.isEmpty())
        app.settings.setLastNote(app.sessions.requireApi().baseUrl, 4)
        poller.check(app.sessions.requireApi())
        assertEquals(listOf("A parcel arrived"), posted)
    }

    @Test fun theBubbleShowsFilesAndHidesTheAppsOwnLines() {
        val line = { p: String -> "📎 $p" }
        val files = listOf(chat.kelo.app.chat.MessageFile(name = "a.jpg", path = "uploads/a.jpg", kind = "image"),
            chat.kelo.app.chat.MessageFile(name = "b.pdf", path = "uploads/b.pdf"))
        assertEquals("", MessageFileRules.shownText("a.jpg, b.pdf 내용을 읽고 요약해줘.", files, autoText = true, line = line))
        assertEquals("이거 봐줘", MessageFileRules.shownText("이거 봐줘\n\n📎 uploads/a.jpg\n📎 uploads/b.pdf", files, autoText = false, line = line))
        assertEquals("plain", MessageFileRules.shownText("plain", emptyList(), autoText = true, line = line))
        assertEquals(listOf(1), MessageFileRules.rows(1))
        assertEquals(listOf(2, 1), MessageFileRules.rows(3))
        assertEquals(listOf(2, 2), MessageFileRules.rows(4))
        assertEquals(emptyList<Int>(), MessageFileRules.rows(0))
        assertTrue(AttachmentKinds.isImage("Photo.JPG"))
        assertFalse(AttachmentKinds.isImage("clip.mov"))
        assertFalse(AttachmentKinds.isImage("notes"))
    }

    @Test fun notNowAnswersALocationRequest() {
        val handle = DesktopChat(app)
        val model = handle.vm
        pending = """{"items":[{"type":"location_request","approval_id":"L1","state":"waiting","reason":"For the weather","settings":false}]}"""
        model.refreshAsks(force = true)
        waitFor("the card") { model.state.value.items.any { it is ChatItem.LocationAsk && it.open } }
        model.answerLocation("L1", null)
        waitFor("the answer") { (model.state.value.items.first { it is ChatItem.LocationAsk } as ChatItem.LocationAsk).state == ChatItem.LocationAsk.REFUSED }
        assertTrue(requests.any { it == "POST /api/location-requests/L1" })
        handle.close()
    }
}
