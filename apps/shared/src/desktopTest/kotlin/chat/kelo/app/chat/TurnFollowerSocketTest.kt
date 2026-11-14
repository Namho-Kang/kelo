package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** One scripted socket call: the (seq, event) pairs it gives, then how it ends (null = hangs until cancelled). */
private class Sock(val events: List<Pair<Int, TurnEvent>>, val end: SocketEnd?)

private class SocketFakeApi(vararg script: Sock) : TurnApi {
    val socks = ArrayDeque(script.toList())
    val socketAfters = mutableListOf<Int>()
    val polls = mutableListOf<Int>()
    val stopped = mutableListOf<String>()
    var hanging: CompletableDeferred<Unit>? = null
    var pollAnswer = TurnPoll(emptyList(), next = 0, done = true)

    override suspend fun start(text: String, timezone: String) = "t1"
    override suspend fun poll(id: String, after: Int): TurnPoll { polls += after; return pollAnswer }
    override suspend fun stop(id: String) { stopped += id }
    override suspend fun socket(id: String, after: Int, onEvent: (Int, TurnEvent) -> Unit): SocketEnd {
        socketAfters += after
        val s = socks.removeFirstOrNull() ?: return SocketEnd.Broken
        s.events.forEach { (n, e) -> onEvent(n, e) }
        if (s.end == null) { hanging?.complete(Unit); awaitCancellation() }
        return s.end
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TurnFollowerSocketTest {
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()
    private fun follower(api: TurnApi) = TurnFollower(api, { now }) { sleeps += it }
    private fun ev(n: Int) = n to TurnEvent.Text("e$n")

    @Before fun setup() = SocketGate.reset()

    @Test fun socketGivesAllEventsAndNeverPolls() = runTest {
        val api = SocketFakeApi(Sock(listOf(ev(0), ev(1), ev(2)), SocketEnd.Done(3)))
        val seen = mutableListOf<TurnEvent>()
        follower(api).follow("t1") { seen += it }
        assertEquals(listOf(TurnEvent.Text("e0"), TurnEvent.Text("e1"), TurnEvent.Text("e2")), seen)
        assertTrue(api.polls.isEmpty())
    }

    @Test fun reconnectsFromTheLastEvent() = runTest {
        val api = SocketFakeApi(Sock(listOf(ev(0), ev(1)), SocketEnd.Broken), Sock(listOf(ev(2)), SocketEnd.Done(3)))
        val seen = mutableListOf<TurnEvent>()
        follower(api).follow("t1") { seen += it }
        assertEquals(listOf(0, 2), api.socketAfters)
        assertEquals(listOf(TurnEvent.Text("e0"), TurnEvent.Text("e1"), TurnEvent.Text("e2")), seen)
        assertTrue(api.polls.isEmpty())
    }

    @Test fun threeBreaksWithoutEventsFallBackToPollAndBlockSockets() = runTest {
        val api = SocketFakeApi(Sock(emptyList(), SocketEnd.Broken), Sock(emptyList(), SocketEnd.Broken), Sock(emptyList(), SocketEnd.Broken))
        follower(api).follow("t1") {}
        assertEquals(listOf(0, 0, 0), api.socketAfters)
        assertEquals(listOf(0), api.polls)
        follower(api).follow("t1") {}   // sockets are skipped now
        assertEquals(3, api.socketAfters.size)
        assertEquals(listOf(0, 0), api.polls)
        now += SocketGate.BLOCK_MS   // and tried again later
        follower(api).follow("t1") {}
        assertEquals(6, api.socketAfters.size)
    }

    @Test fun unavailablePollsAtOnceWithoutBlocking() = runTest {
        val api = SocketFakeApi(Sock(emptyList(), SocketEnd.Unavailable))
        follower(api).follow("t1") {}
        assertEquals(listOf(0), api.polls)
        assertTrue(SocketGate.open(now))
    }

    @Test fun aGapCountsAsABreak() = runTest {
        val api = SocketFakeApi(Sock(listOf(ev(0), ev(2)), SocketEnd.Done(3)), Sock(listOf(ev(1), ev(2)), SocketEnd.Done(3)))
        val seen = mutableListOf<TurnEvent>()
        follower(api).follow("t1") { seen += it }
        assertEquals(listOf(0, 1), api.socketAfters)
        assertEquals(listOf(TurnEvent.Text("e0"), TurnEvent.Text("e1"), TurnEvent.Text("e2")), seen)
    }

    @Test fun goneIsA404() = runTest {
        val api = SocketFakeApi(Sock(emptyList(), SocketEnd.Gone))
        try {
            follower(api).follow("t1") {}
            fail()
        } catch (e: ApiError) {
            assertEquals(404, e.status)
        }
        assertTrue(api.polls.isEmpty())
    }

    @Test fun cancellingDuringTheSocketStopsTheTurn() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val api = SocketFakeApi(Sock(listOf(ev(0)), null)).apply { hanging = waiting }
        val job = launch { follower(api).follow("t1") {} }
        waiting.await()
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf("t1"), api.stopped)
    }
}
