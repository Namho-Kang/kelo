package chat.kelo.app.my

import chat.kelo.app.my.MyFakeServer.Companion.until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The view models that poll do it only while their state is collected (the screen in view, the app in front). */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingViewModelsTest {
    private val vms = chat.kelo.app.TestViewModels()
    private var server: MyFakeServer? = null

    @Before fun main() { Dispatchers.setMain(Dispatchers.Default) }   // real time: the poll waits with delay()

    @After fun stop() { vms.clear(); server?.shutdown(); Dispatchers.resetMain() }

    private fun count(s: MyFakeServer, prefix: String) = s.requests.count { it.startsWith(prefix) }

    @Test fun theResourcesPollStopsWhenNobodyCollectsAndStartsAgain() = runBlocking {
        val s = MyFakeServer { _, _, _ -> null }.also { server = it }
        val vm = vms.make { ResourcesViewModel(s.sessions(), pollMs = 30) }
        Thread.sleep(200)
        assertEquals("nothing is asked before a screen shows it", 0, count(s, "GET /api/resources"))

        val job = launch(Dispatchers.Default) { vm.state.collect { } }
        until("two polls") { count(s, "GET /api/resources") >= 2 }
        job.cancelAndJoin()   // the app goes to the background: the collector is gone
        Thread.sleep(150)
        val after = count(s, "GET /api/resources")
        Thread.sleep(400)
        assertEquals("no request while nobody collects", after, count(s, "GET /api/resources"))

        val again = launch(Dispatchers.Default) { vm.state.collect { } }
        until("polling again") { count(s, "GET /api/resources") > after }
        again.cancelAndJoin()
        assertTrue(count(s, "GET /api/resources") > after)
    }
}
