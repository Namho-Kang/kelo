package chat.kelo.app.agent

import chat.kelo.app.res.*

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Collections
import javax.crypto.KeyGenerator

/** The agent screen's calls against a local fake server (nothing reaches a real one). */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {
    private val vms = chat.kelo.app.TestViewModels()
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** The fake server's chat history was cleared: it answers with empty lists */
    @Volatile private var cleared = false

    /** When set, the fake server holds every archive DELETE until it is released */
    @Volatile private var deleteGate: java.util.concurrent.CountDownLatch? = null

    /** The fake server's 보관한 예약 */
    private val archived = Collections.synchronizedList(mutableListOf(
        """{"id":7,"name":"로또","prompt":"p","kind":"cron","cron":"0 12 * * 1","enabled":false}""",
        """{"id":8,"name":"산책","prompt":"p","kind":"cron","cron":"0 7 * * *","enabled":false}""",
    ))

    private val schedule = """{"id":3,"name":"아침","prompt":"날씨","kind":"cron","cron":"0 9 * * *","enabled":true}"""

    @Before fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val path = request.path.orEmpty()
                return when {
                    cleared && path.startsWith("/api/tasks?") -> MockResponse().setBody("""{"items":[],"unseen":0,"next_before":null}""")
                    cleared && path.startsWith("/api/journal?") -> MockResponse().setBody("""{"month":"2026-10","items":[],"pending":[]}""")
                    path.startsWith("/api/tasks?") && path.contains("before=") ->
                        MockResponse().setBody("""{"items":[{"id":1,"title":"옛 일","status":"done"}],"unseen":0,"next_before":null}""")
                    path.startsWith("/api/tasks?") ->
                        MockResponse().setBody("""{"items":[{"id":3,"title":"a","status":"running"},{"id":2,"title":"b","status":"done","seen":false}],"unseen":1,"next_before":2}""")
                    path == "/api/tasks/seen" -> MockResponse().setBody("""{"ok":true,"marked":1,"unseen":0}""")
                    path == "/api/schedules/archive" && request.method == "GET" ->
                        MockResponse().setBody("""{"items":[${archived.joinToString(",")}],"keep_days":30}""")
                    path.startsWith("/api/schedules/archive/") && request.method == "DELETE" -> {
                        val id = path.substringAfterLast('/').toLong()
                        deleteGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        archived.removeAll { it.contains("\"id\":$id,") }
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    path == "/api/schedules" && request.method == "GET" -> MockResponse().setBody("""{"items":[$schedule],"timezone":"Asia/Seoul"}""")
                    path.startsWith("/api/schedules") -> MockResponse().setBody(schedule)
                    path == "/api/approvals/pending" -> MockResponse().setBody("""{"items":[{"type":"approval_request","approval_id":"a1","name":"browser_click","site":"x.example","can_remember":true}]}""")
                    path == "/api/approvals/rules" -> MockResponse().setBody("""{"items":[{"id":5,"kind":"login","site":"x.example","uses":2}]}""")
                    path.startsWith("/api/approvals/history") -> MockResponse().setBody("""{"items":[],"next_before":null}""")
                    path == "/api/approvals/a1" -> MockResponse().setBody("""{"ok":true,"decision":"allowed_always","remembered":true}""")
                    path == "/api/browser/sessions/task%3Aab" && request.method == "DELETE" -> MockResponse().setResponseCode(409).setBody("""{"detail":"busy"}""")
                    path.startsWith("/api/journal/rebuild") -> MockResponse().setResponseCode(409).setBody("""{"detail":"openai"}""")
                    path.startsWith("/api/journal?") -> MockResponse().setBody("""{"month":"2026-10","items":[{"id":1,"date":"2026-10-03","title":"t","summary":"s"}],"pending":[]}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"detail":"no"}""")
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        server.shutdown()
        vms.clear(); Dispatchers.resetMain()
    }

    private fun vm(sessions: Sessions = sessions()): AgentViewModel = vms.make { AgentViewModel(sessions) }

    private fun sessions(): Sessions {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        return Sessions(store, requireE2e = false)
    }

    @Test fun clearingTheChatHistoryDropsTheTaskLogAndDaySummariesAndAsksAgain() {
        val sessions = sessions()
        val vm = vm(sessions)
        vm.loadTasks()
        vm.loadJournal(java.time.YearMonth.of(2026, 10))
        until("loaded") { vm.tasks.value.data?.items?.size == 2 && vm.journal.value.data?.entries?.size == 1 }
        val asked = synchronized(requests) { requests.count { it.first.path.orEmpty().startsWith("/api/tasks?") } }
        cleared = true
        sessions.historyWasCleared()
        until("emptied") { vm.tasks.value.data?.items?.isEmpty() == true && vm.journal.value.data?.entries?.isEmpty() == true }
        until("asked again") { synchronized(requests) { requests.count { it.first.path.orEmpty().startsWith("/api/tasks?") } } > asked }
        assertEquals(null, vm.workers.value.data)   // a list that was never shown is not fetched
    }

    // 보관한 예약: swipe one away and leave the page within a second (the page's scope is gone, the undo message never ends);
    // on coming back it must be gone on the server, not shown again
    @Test fun aSwipedArchivedScheduleIsDeletedOnTheServerEvenWhenThePageIsLeftAtOnce() {
        val vm = vm()
        vm.loadArchive()
        until("archive") { vm.archive.value.data?.items?.size == 2 }
        val page = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        page.launch { kotlinx.coroutines.delay(4_000); vm.eraseArchived(7) }   // what the page would do when the message ends
        vm.eraseArchivedSoon(7)
        assertEquals(listOf(8L), vm.archive.value.data?.items?.map { it.id })   // hidden at once
        Thread.sleep(300)
        page.coroutineContext[kotlinx.coroutines.Job]!!.cancel()   // the page is left: its scope is cancelled ...
        vm.commitArchivedErases()                                  // ... and its DisposableEffect commits
        // the fake server logs a request before it handles it: wait for the delete itself, not for the log line
        until("deleted on the server") { archived.none { it.contains("\"id\":7,") } }
        assertEquals(1, synchronized(requests) { requests.count { it.first.method == "DELETE" && it.first.path == "/api/schedules/archive/7" } })
        // back on the page: a list fetched after the delete, not the one still hidden on the phone
        until("idle") { !vm.archive.value.loading }
        val gets = archiveGets()
        vm.loadArchive()
        until("asked again") { archiveGets() > gets && !vm.archive.value.loading }
        assertEquals(listOf(8L), vm.archive.value.data?.items?.map { it.id })
        assertEquals(1, archived.size)
    }

    // a list loaded while the delete is still on its way must not bring the schedule back
    @Test fun aScheduleBeingDeletedStaysHiddenFromAListLoadedMeanwhile() {
        val gate = java.util.concurrent.CountDownLatch(1).also { deleteGate = it }
        val vm = vm()
        vm.loadArchive()
        until("archive") { vm.archive.value.data?.items?.size == 2 }
        vm.eraseArchived(7)
        until("delete sent") { synchronized(requests) { requests.any { it.first.method == "DELETE" && it.first.path == "/api/schedules/archive/7" } } }
        val gets = archiveGets()
        vm.loadArchive()   // the server still lists 7: its delete is held
        until("asked again") { archiveGets() > gets && !vm.archive.value.loading }
        assertEquals(listOf(8L), vm.archive.value.data?.items?.map { it.id })
        gate.countDown()
        until("deleted on the server") { archived.none { it.contains("\"id\":7,") } }
        until("idle") { !vm.archive.value.loading }
        assertEquals(listOf(8L), vm.archive.value.data?.items?.map { it.id })
    }

    @Test fun undoKeepsAnArchivedScheduleAndSendsNoDelete() {
        val vm = vm()
        vm.loadArchive()
        until("archive") { vm.archive.value.data?.items?.size == 2 }
        vm.eraseArchivedSoon(8)
        vm.undoEraseArchived(8)
        vm.commitArchivedErases()   // nothing waits any more
        until("shown again") { vm.archive.value.data?.items?.size == 2 }
        Thread.sleep(200)
        assertTrue(synchronized(requests) { requests.none { it.first.method == "DELETE" } })
    }

    private fun archiveGets() = synchronized(requests) { requests.count { it.first.method == "GET" && it.first.path == "/api/schedules/archive" } }

    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun body(method: String, path: String) = synchronized(requests) { requests.last { it.first.method == method && it.first.path == path }.second }

    @Test fun tasksPageOnWithNextBefore() {
        val vm = vm()
        vm.loadTasks()
        until("tasks") { vm.tasks.value.data != null }
        assertEquals(2L, vm.tasks.value.data!!.nextBefore)
        vm.loadMoreTasks()
        until("more") { vm.tasks.value.data!!.items.size == 3 }
        assertEquals(listOf(3L, 2L, 1L), vm.tasks.value.data!!.items.map { it.id })
        assertEquals(null, vm.tasks.value.data!!.nextBefore)
        vm.markSeen(2)
        vm.markSeen(1)   // older than what was marked: nothing sent
        until("seen") { requests.any { it.first.path == "/api/tasks/seen" } }
        assertEquals("2", Json.parseToJsonElement(body("POST", "/api/tasks/seen")).jsonObject["up_to"]!!.jsonPrimitive.content)
        assertEquals(1, requests.count { it.first.path == "/api/tasks/seen" })
    }

    @Test fun savingAScheduleSendsCronOrRunAt() {
        val vm = vm()
        var saved: Schedule? = null
        val now = java.time.LocalDateTime.of(2026, 11, 7, 8, 0)
        vm.saveSchedule(null, " 아침 ", "날씨", SchedulePlan(LocalDate.of(2026, 11, 9), 7, 30, Freq.WEEKDAYS, until = LocalDate.of(2026, 12, 31)),
            "Asia/Seoul", now = now) { saved = it }
        until("created") { saved != null }
        val created = Json.parseToJsonElement(body("POST", "/api/schedules")).jsonObject
        assertEquals("아침", created["name"]!!.jsonPrimitive.content)
        assertEquals("30 7 * * 1-5", created["cron"]!!.jsonPrimitive.content)
        assertEquals("Asia/Seoul", created["timezone"]!!.jsonPrimitive.content)
        assertEquals("0", created["window_minutes"]!!.jsonPrimitive.content)
        assertEquals("", created["starts_on"]!!.jsonPrimitive.content)
        assertEquals("2026-12-31", created["ends_on"]!!.jsonPrimitive.content)

        saved = null
        vm.saveSchedule(3, "아침", "날씨", SchedulePlan(LocalDate.of(2030, 1, 2), 8, 0), null) { saved = it }
        until("changed") { saved != null }
        val changed = Json.parseToJsonElement(body("PATCH", "/api/schedules/3")).jsonObject
        assertEquals("2030-01-02T08:00", changed["run_at"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, changed["cron"])

        saved = null   // an unusual schedule left as it is: only the words change
        vm.saveSchedule(3, "아침 2", "날씨", null, null) { saved = it }
        until("renamed") { saved != null }
        val renamed = Json.parseToJsonElement(body("PATCH", "/api/schedules/3")).jsonObject
        assertEquals(setOf("name", "prompt"), renamed.keys)
    }

    @Test fun theSwitchPatchesEnabled() {
        val vm = vm()
        vm.loadSchedules()
        until("schedules") { vm.schedules.value.data != null }
        vm.setEnabled(vm.schedules.value.data!!.items.single(), false)
        until("patch") { requests.any { it.first.method == "PATCH" } }
        assertEquals("false", Json.parseToJsonElement(body("PATCH", "/api/schedules/3")).jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test fun answeringRemembersTheSiteOnlyWhenAsked() {
        val vm = vm()
        vm.loadPerms()
        until("perms") { vm.perms.value.data != null }
        val p = vm.perms.value.data!!
        assertEquals(1, p.pending.size)
        assertEquals("login", p.rules.single().kind)
        vm.answer(p.pending.single(), approve = true, remember = true)
        until("answer") { requests.any { it.first.path == "/api/approvals/a1" } }
        val sent = Json.parseToJsonElement(body("POST", "/api/approvals/a1")).jsonObject
        assertEquals("true", sent["approve"]!!.jsonPrimitive.content)
        assertEquals("site", sent["remember"]!!.jsonPrimitive.content)
    }

    @Test fun pageSizeIsSentAsANumber() {
        val vm = vm()
        vm.loadTasks()
        vm.loadWorkers()
        vm.loadPerms()
        until("lists") { vm.tasks.value.data != null && vm.workers.value.data != null && vm.perms.value.data != null }
        val paths = synchronized(requests) { requests.map { it.first.path.orEmpty() } }
        assertTrue(paths.toString(), "/api/tasks?limit=50" in paths)
        assertTrue(paths.toString(), "/api/workers?limit=50" in paths)
        assertTrue(paths.toString(), "/api/approvals/history?limit=50" in paths)
        assertTrue(paths.toString(), paths.none { "Companion" in it || ".PAGE" in it })
    }

    @Test fun refusalsBecomeMessages() {
        val vm = vm()
        val got = Collections.synchronizedList(mutableListOf<AgentMessage>())
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) { vm.messages.collect { got += it } }
        vm.closeTab(BrowserTab("task:ab")) {}
        vm.rebuild("2026-10-03")
        until("messages") { got.size >= 2 }
        job.cancel()
        assertTrue(got.any { it.text == chat.kelo.app.res.Res.string.agent_tab_busy })
        assertTrue(got.any { it.text == chat.kelo.app.res.Res.string.agent_journal_openai })
    }

    @Test fun journalMonthChanges() {
        val vm = vm()
        vm.loadJournal(java.time.YearMonth.of(2026, 10))
        until("journal") { vm.journal.value.data != null }
        assertEquals(java.time.YearMonth.of(2026, 10), vm.month.value)
        assertTrue(requests.any { it.first.path == "/api/journal?month=2026-10" })
    }
}

