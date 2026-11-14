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
import org.junit.Test

/** A scripted server: each poll takes the next answer (a TurnPoll, or a Throwable to throw). */
private class FakeTurnApi(vararg answers: Any) : TurnApi {
    val queue = ArrayDeque(answers.toList())
    val polls = mutableListOf<Int>()
    val stopped = mutableListOf<String>()
    var started: Pair<String, String>? = null
    var hangWhenEmpty: CompletableDeferred<Unit>? = null

    override suspend fun start(text: String, timezone: String): String {
        started = text to timezone
        return "t1"
    }

    override suspend fun poll(id: String, after: Int): TurnPoll {
        polls += after
        if (queue.isEmpty()) {
            hangWhenEmpty?.complete(Unit)
            awaitCancellation()
        }
        return when (val a = queue.removeFirst()) {
            is TurnPoll -> a
            is Throwable -> throw a
            else -> error("bad script")
        }
    }

    override suspend fun stop(id: String) {
        stopped += id
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TurnFollowerTest {
    private val sleeps = mutableListOf<Long>()
    private fun follower(api: TurnApi) = TurnFollower(api) { sleeps += it }

    @Test fun followsFromNextUntilDone() = runTest {
        val api = FakeTurnApi(
            TurnPoll(listOf(TurnEvent.Thinking, TurnEvent.Text("안")), next = 2, done = false),
            TurnPoll(emptyList(), next = 2, done = false),
            TurnPoll(listOf(TurnEvent.Text("녕"), TurnEvent.Done(1, 2, 1)), next = 4, done = true),
        )
        val seen = mutableListOf<TurnEvent>()
        follower(api).talk("hi", "Asia/Seoul") { seen += it }
        assertEquals("hi" to "Asia/Seoul", api.started)
        assertEquals(listOf(0, 2, 2), api.polls)
        assertEquals(4, seen.size)
        assertTrue(api.stopped.isEmpty())
    }

    @Test fun retriesTransientFailuresAndSaysReconnectingAfterTwo() = runTest {
        val api = FakeTurnApi(
            TurnPoll(listOf(TurnEvent.Text("a")), next = 1, done = false),
            ApiError(ApiError.NO_RESPONSE, "offline"),
            ApiError(502, "bad gateway"),
            ApiError(429, "slow down"),
            IllegalStateException("unreadable body"),
            TurnPoll(listOf(TurnEvent.Text("b")), next = 2, done = true),
        )
        val seen = mutableListOf<TurnEvent>()
        follower(api).talk("x", "UTC") { seen += it }
        assertEquals(listOf(TurnEvent.Text("a"), TurnEvent.Reconnecting, TurnEvent.Text("b")), seen)
        assertEquals(listOf(0, 1, 1, 1, 1, 1), api.polls)   // a failed request is made again from the same place
        assertEquals(listOf(500L, 1000L, 1500L, 2000L), sleeps)
    }

    @Test fun failureCountResetsAfterAnAnswer() = runTest {
        val api = FakeTurnApi(
            ApiError(500, "x"),
            TurnPoll(emptyList(), next = 0, done = false),
            ApiError(500, "x"),
            TurnPoll(emptyList(), next = 0, done = true),
        )
        val seen = mutableListOf<TurnEvent>()
        follower(api).talk("x", "UTC") { seen += it }
        assertTrue(seen.isEmpty())   // never two failures in a row: no reconnecting notice
        assertEquals(listOf(500L, 500L), sleeps)
    }

    @Test fun backoffIsCappedAtFourSeconds() {
        assertEquals(500L, TurnFollower.backoff(1))
        assertEquals(4000L, TurnFollower.backoff(8))
        assertEquals(4000L, TurnFollower.backoff(29))
    }

    @Test fun givesUpAfterThirtyFailuresInARow() = runTest {
        val api = FakeTurnApi(*Array<Any>(40) { ApiError(503, "down") })
        try {
            follower(api).talk("x", "UTC") {}
            fail()
        } catch (e: ApiError) {
            assertEquals(503, e.status)
        }
        assertEquals(TurnFollower.GIVE_UP_AFTER, api.polls.size)
        assertEquals(TurnFollower.GIVE_UP_AFTER - 1, sleeps.size)
    }

    @Test fun permanentErrorsAreNotRetried() = runTest {
        val api = FakeTurnApi(ApiError(404, "that reply is gone"))
        try {
            follower(api).talk("x", "UTC") {}
            fail()
        } catch (e: ApiError) {
            assertEquals("that reply is gone", e.detail)
        }
        assertEquals(1, api.polls.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun transientRule() {
        assertTrue(TurnFollower.isTransient(ApiError(0, "")))
        assertTrue(TurnFollower.isTransient(ApiError(408, "")))
        assertTrue(TurnFollower.isTransient(ApiError(429, "")))
        assertTrue(TurnFollower.isTransient(ApiError(500, "")))
        assertTrue(TurnFollower.isTransient(RuntimeException()))
        assertTrue(!TurnFollower.isTransient(ApiError(400, "")))
        assertTrue(!TurnFollower.isTransient(ApiError(401, "")))
        assertTrue(!TurnFollower.isTransient(ApiError(404, "")))
    }

    @Test fun cancellingStopsTheTurnOnTheServer() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val api = FakeTurnApi(TurnPoll(listOf(TurnEvent.Thinking), next = 1, done = false)).apply { hangWhenEmpty = waiting }
        val job = launch { follower(api).talk("x", "UTC") {} }
        waiting.await()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf("t1"), api.stopped)
    }
}
