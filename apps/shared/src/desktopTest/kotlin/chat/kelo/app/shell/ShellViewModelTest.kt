package chat.kelo.app.shell

import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.chat.AgentActivity
import chat.kelo.app.my.MyFakeServer
import chat.kelo.app.my.MyFakeServer.Companion.ok
import chat.kelo.app.my.MyFakeServer.Companion.until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The shell's agent status: asked only while collected, and turned into the header's badge. */
class ShellViewModelTest {
    private val vms = chat.kelo.app.TestViewModels()
    private var server: MyFakeServer? = null

    @BeforeTest fun main() { Dispatchers.setMain(Dispatchers.Default) }   // real time: the poll waits with delay()
    @AfterTest fun stop() { vms.clear(); server?.shutdown(); Dispatchers.resetMain() }

    private fun count(s: MyFakeServer) = s.requests.count { it.startsWith("GET /api/agent/status") }

    @Test fun theStatusIsAskedOnlyWhileCollectedAndShowsTheBadge() { runBlocking {
        val s = MyFakeServer { m, p, _ -> if (m == "GET" && p == "/api/agent/status") ok("""{"busy":false,"pending_approvals":2}""") else null }.also { server = it }
        val sessions = s.sessions()
        val vm = vms.make { ShellViewModel(sessions, AgentActivity(), AgentAvatar(sessions), pollMs = 30) }
        Thread.sleep(200)
        assertEquals(0, count(s), "nothing is asked before a screen shows it")

        val job = launch(Dispatchers.Default) { vm.state.collect { } }
        until("two polls") { count(s) >= 2 }
        until("the badge") { vm.state.value.badge != null }
        assertEquals(2, vm.state.value.badge!!.count)
        assertTrue(vm.state.value.news)
        job.cancelAndJoin()   // the window goes to the background: nobody collects
        Thread.sleep(150)
        val after = count(s)
        Thread.sleep(400)
        assertEquals(after, count(s), "no request while nobody collects")

        val again = launch(Dispatchers.Default) { vm.state.collect { } }
        until("polling again") { count(s) > after }
        again.cancelAndJoin()
        assertNotNull(vm.state.value.status)
    } }
}
