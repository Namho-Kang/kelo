package chat.kelo.app.my

import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.core.Profile
import chat.kelo.app.my.MyFakeServer.Companion.fail
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import chat.kelo.app.platform.FilePicker
import chat.kelo.app.platform.PickedFile
import chat.kelo.app.platform.PickedImage
import chat.kelo.app.platform.SaveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The agent page's and the records page's view models against a local fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRecordsViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }

    @After fun stop() { vms.clear(); server?.shutdown(); Dispatchers.resetMain() }

    private fun serve(answer: (String, String, String) -> okhttp3.mockwebserver.MockResponse?) = MyFakeServer(answer).also { server = it }

    private class Picker(private val image: PickedImage?) : FilePicker {
        override suspend fun pickImage(maxBytes: Long) = image
        override suspend fun pickFile(types: List<String>): PickedFile? = null
        override suspend fun saveFile(name: String, type: String): SaveTarget? = null
    }

    @Test fun aToneIsSavedAndReportedOrAFailureIsToldOnce() {
        val s = serve { m, p, _ -> if (m == "PUT" && p == "/api/profile") ok("""{"user_name":"a","agent_name":"b","tone":"polite"}""") else null }
        val sessions = s.sessions()
        val vm = vms.make { AgentPageViewModel(sessions, AgentAvatar(sessions), Picker(null)) }
        var saved: Profile? = null
        vm.pickTone(Profile(), ToneRules.FRIENDLY) { saved = it }
        until("the tone") { saved != null }
        assertEquals("b", saved!!.agentName)
        assertEquals(AgentNotice.Done, runBlocking { vm.notices.first() })
        assertNull(vm.state.value.savingTone)

        val bad = serve { _, _, _ -> fail(500, "no") }
        val vm2 = vms.make { AgentPageViewModel(bad.sessions(), AgentAvatar(bad.sessions()), Picker(null)) }
        vm2.pickTone(Profile(), ToneRules.FRIENDLY) { }
        assertEquals(AgentNotice.ToneFailed, runBlocking { vm2.notices.first() })
    }

    @Test fun anOlderProfileAnswerNeverOverwritesANewerOne() {
        // the first load is answered slowly with the old tone, the second quickly with the new one: the state ends on the new one
        var calls = 0
        val s = serve { m, p, _ ->
            if (m == "GET" && p == "/api/profile") {
                val n = synchronized(this) { ++calls }
                if (n == 1) ok("""{"user_name":"a","agent_name":"b","tone":"polite"}""").setBodyDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                else ok("""{"user_name":"a","agent_name":"b","tone":"friendly"}""")
            } else null
        }
        val sessions = s.sessions()
        val vm = vms.make { AgentPageViewModel(sessions, AgentAvatar(sessions), Picker(null)) }
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        vm.reload { seen += it.tone }
        until("the first request") { calls >= 1 }
        vm.reload { seen += it.tone }
        until("the new answer") { "friendly" in seen }
        Thread.sleep(900)   // the old answer would have landed by now
        assertEquals(listOf("friendly"), seen.toList())
    }

    @Test fun aLoadThatStartedBeforeAPickDoesNotUndoIt() {
        var calls = 0
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/profile" -> { synchronized(this) { calls++ }; ok("""{"user_name":"a","agent_name":"b","tone":"polite"}""").setBodyDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS) }
                m == "PUT" && p == "/api/profile" -> ok("""{"user_name":"a","agent_name":"b","tone":"friendly"}""")
                else -> null
            }
        }
        val sessions = s.sessions()
        val vm = vms.make { AgentPageViewModel(sessions, AgentAvatar(sessions), Picker(null)) }
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        vm.reload { seen += it.tone }
        until("the load") { calls >= 1 }
        vm.pickTone(Profile(), ToneRules.FRIENDLY) { seen += it.tone }
        until("the pick") { "friendly" in seen }
        Thread.sleep(900)
        assertEquals(listOf("friendly"), seen.toList())
    }

    @Test fun aPictureTooBigForTheServerIsToldAsTooLarge() {
        val s = serve { _, p, _ -> if (p.startsWith("/api/agent/avatar")) fail(413, "big") else null }
        val sessions = s.sessions()
        val vm = vms.make { AgentPageViewModel(sessions, AgentAvatar(sessions), Picker(PickedImage(ByteArray(10), "image/jpeg"))) }
        vm.pickPicture()
        assertEquals(AgentNotice.AvatarTooLarge, runBlocking { vm.notices.first() })
        until("not working") { !vm.state.value.working }
    }

    @Test fun theLearnedHoursAreOfferedAndAnAcceptShowsDoneThenClears() {
        val offer = """{"wake_start":"07:00","wake_end":"22:00","hours_suggestion":{"id":"h1","wake_start":"09:00","wake_end":"23:00","current_start":"07:00","current_end":"22:00"}}"""
        val s = serve { m, p, _ ->
            when {
                m == "GET" && p == "/api/proactive" -> ok(offer)
                m == "POST" && p == "/api/proactive/hours/h1/accept" -> ok(offer)
                else -> null
            }
        }
        val vm = vms.make { HoursSuggestionViewModel(s.sessions()) }
        until("the offer") { vm.state.value.hours != null }
        val h = vm.state.value.hours!!
        vm.answer(h, accept = true) { }
        until("done") { s.saw("POST /api/proactive/hours/h1/accept") }
        assertTrue(vm.state.value.done == true || vm.state.value.hours == null)
    }

    @Test fun aRefusedAnswerGivesTheErrorBackAndKeepsTheOffer() {
        val offer = """{"hours_suggestion":{"id":"h1","wake_start":"09:00","wake_end":"23:00","current_start":"07:00","current_end":"22:00"}}"""
        val s = serve { m, _, _ -> if (m == "GET") ok(offer) else fail(500, "broke") }
        val vm = vms.make { HoursSuggestionViewModel(s.sessions()) }
        until("the offer") { vm.state.value.hours != null }
        var error: String? = null
        vm.answer(vm.state.value.hours!!, accept = false) { error = it.detail }
        until("the error") { error != null }
        assertEquals("broke", error)
        assertNotNull(vm.state.value.hours)
        assertNull(vm.state.value.done)
    }
}
