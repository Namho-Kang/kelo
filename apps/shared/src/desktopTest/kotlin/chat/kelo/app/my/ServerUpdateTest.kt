package chat.kelo.app.my

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUpdateTest {
    private fun st(s: String) = KeloApi.json.decodeFromString<ServerUpdateStatus>(s)

    private val now = 1_731_100_000.0

    private val available = st(
        """{"state":"ok","message":"","current":"0.4.0","latest":{"version":"0.5.0","date":"2026-11-01T00:00:00Z","notes":"Faster replies\nNew connectors"},
           "update_available":true,"checked_at":1731099000.0,"auto_update":false,"auto_time":null,"job":null,
           "rollback":{"available":false,"version":null,"created_at":null},"something_new":{"x":1}}""",
    )

    private fun job(state: String, action: String = "update", finished: Double? = now - 60, extra: String = "") = st(
        """{"state":"ok","current":"0.4.0","latest":{"version":"0.5.0"},"update_available":true,
           "job":{"id":"20261109-035000","action":"$action","auto":false,"state":"$state","step":"health","progress":80,"from":"0.4.0","to":"0.5.0",
                  "error":${if (state == "running" || state == "done") "null" else "\"health check failed: /healthz 503\""},
                  "started_at":${now - 300},"finished_at":${finished ?: "null"}$extra},
           "rollback":{"available":true,"version":"0.4.0","created_at":${now - 300}}}""",
    )

    @Test fun `the status reads with unknown and missing fields`() {
        assertEquals("0.5.0", available.latest?.version)
        assertEquals("Faster replies\nNew connectors", available.latest?.notes)
        assertTrue(available.updateAvailable)
        assertEquals(1731099000.0, available.checkedAt)
        val bare = st("""{}""")
        assertEquals("unavailable", bare.state)
        assertFalse(ServerUpdateRules.shown(bare))
        val nulls = st("""{"state":"ok","current":null,"latest":null,"update_available":null,"auto_update":null,"job":null,"rollback":null}""")
        assertTrue(ServerUpdateRules.shown(nulls))
        assertFalse(nulls.updateAvailable)
        assertNull(ServerUpdateRules.card(nulls, now))
        // a fractional progress is fine too
        assertEquals(42, ServerUpdateRules.percent(job("running", extra = "").job!!.copy(progress = 42.7)))
        assertEquals(100, ServerUpdateRules.percent(ServerUpdateJob(progress = 250.0)))
        assertEquals(0, ServerUpdateRules.percent(null))
    }

    @Test fun `only ok and local show anything`() {
        assertTrue(ServerUpdateRules.shown(available))
        assertTrue(ServerUpdateRules.shown(st("""{"state":"local","message":"Built from source","current":"0.4.0"}""")))
        assertFalse(ServerUpdateRules.shown(st("""{"state":"unavailable"}""")))
        assertFalse(ServerUpdateRules.shown(null))
    }

    @Test fun `a server built from source shows its message and nothing to press`() {
        val local = st("""{"state":"local","message":"Built from source on the server machine: update it there with kelo update","current":"0.4.0",
                           "update_available":true,"latest":{"version":"0.5.0"},"auto_update":true,"auto_time":"03:50","rollback":{"available":true,"version":"0.3.0"}}""")
        assertFalse(ServerUpdateRules.canUpdate(local))
        assertFalse(ServerUpdateRules.canRollback(local))
        assertFalse(ServerUpdateRules.showAuto(local))
        assertNull(ServerUpdateRules.tonight(local))
        assertNull(ServerUpdateRules.card(local, now))
    }

    @Test fun `the button shows when a newer release is out and nothing runs`() {
        assertTrue(ServerUpdateRules.canUpdate(available))
        assertFalse(ServerUpdateRules.canUpdate(available.copy(updateAvailable = false)))
        assertFalse(ServerUpdateRules.canUpdate(available.copy(latest = null)))
        assertFalse(ServerUpdateRules.canUpdate(available.copy(latest = ServerRelease(version = " "))))
        assertFalse(ServerUpdateRules.canUpdate(job("running")))
        assertTrue(ServerUpdateRules.canUpdate(job("failed")))   // a failed update changed nothing: it can be tried again
    }

    @Test fun `going back is offered only with a rollback point and nothing running`() {
        assertFalse(ServerUpdateRules.canRollback(available))
        assertTrue(ServerUpdateRules.canRollback(job("done")))
        assertFalse(ServerUpdateRules.canRollback(job("running")))
        assertFalse(ServerUpdateRules.canRollback(job("done").copy(rollback = ServerRollbackPoint(available = true, version = null))))
    }

    @Test fun `tonight's time shows only while automatic updates are on`() {
        assertNull(ServerUpdateRules.tonight(available))
        assertEquals("03:50", ServerUpdateRules.tonight(available.copy(autoUpdate = true, autoTime = "03:50")))
        assertNull(ServerUpdateRules.tonight(available.copy(autoUpdate = true, autoTime = null)))
        assertNull(ServerUpdateRules.tonight(available.copy(autoUpdate = false, autoTime = "03:50")))
        assertTrue(ServerUpdateRules.showAuto(available))
    }

    @Test fun `the card on My home`() {
        assertEquals(ServerUpdateRules.Card.Available("0.5.0"), ServerUpdateRules.card(available, now))
        assertNull(ServerUpdateRules.card(available.copy(updateAvailable = false), now))
        assertEquals(ServerUpdateRules.Card.Running(false, 80, ServerUpdateRules.Step.HEALTH), ServerUpdateRules.card(job("running", finished = null), now))
        assertEquals(ServerUpdateRules.Card.Running(true, 80, ServerUpdateRules.Step.HEALTH),
            ServerUpdateRules.card(job("running", action = "rollback", finished = null), now))
        assertEquals(ServerUpdateRules.Card.Failed, ServerUpdateRules.card(job("failed"), now))
        assertEquals(ServerUpdateRules.Card.RolledBack, ServerUpdateRules.card(job("rolled_back"), now))
        assertEquals(ServerUpdateRules.Card.RollbackFailed, ServerUpdateRules.card(job("rollback_failed"), now))
        // a day later a failure no longer has a card: the newer release does again
        assertEquals(ServerUpdateRules.Card.Available("0.5.0"), ServerUpdateRules.card(job("failed", finished = now - 25 * 3600), now))
        // a finished update has no card of its own
        assertNull(ServerUpdateRules.card(job("done").copy(updateAvailable = false), now))
        assertNull(ServerUpdateRules.card(null, now))
    }

    @Test fun `step names`() {
        val names = mapOf(
            "preflight" to ServerUpdateRules.Step.PREFLIGHT, "download" to ServerUpdateRules.Step.DOWNLOAD, "verify" to ServerUpdateRules.Step.VERIFY,
            "backup" to ServerUpdateRules.Step.BACKUP, "restart" to ServerUpdateRules.Step.RESTART, "health" to ServerUpdateRules.Step.HEALTH,
            "rollback" to ServerUpdateRules.Step.ROLLBACK, "self_update" to ServerUpdateRules.Step.SELF_UPDATE, "done" to ServerUpdateRules.Step.DONE,
        )
        names.forEach { (k, v) -> assertEquals(v, ServerUpdateRules.step(ServerUpdateJob(step = k))) }
        assertEquals(ServerUpdateRules.Step.WORKING, ServerUpdateRules.step(ServerUpdateJob(step = "something_later")))
        assertEquals(ServerUpdateRules.Step.WORKING, ServerUpdateRules.step(null))
    }

    @Test fun `the result of the last job`() {
        assertNull(ServerUpdateRules.result(available, now))
        assertNull(ServerUpdateRules.result(job("running", finished = null), now))
        assertEquals(ServerUpdateRules.Result.Updated("0.5.0", false), ServerUpdateRules.result(job("done"), now))
        val failed = ServerUpdateRules.result(job("failed"), now) as ServerUpdateRules.Result.Failed
        assertFalse(failed.goBack)
        assertEquals("health check failed: /healthz 503", failed.error)
        val back = ServerUpdateRules.result(job("rolled_back", extra = ""","data_restored":true"""), now) as ServerUpdateRules.Result.RolledBack
        assertEquals("0.4.0", back.version)   // the version it runs again
        assertTrue(back.dataRestored)
        assertTrue(ServerUpdateRules.result(job("rollback_failed"), now) is ServerUpdateRules.Result.RollbackFailed)
        // the owner's own go-back
        val went = job("done", action = "rollback").let { it.copy(job = it.job!!.copy(from = "0.5.0", to = "0.4.0", dataRestored = true)) }
        assertEquals(ServerUpdateRules.Result.WentBack("0.4.0", true, false), ServerUpdateRules.result(went, now))
        assertEquals(true, (ServerUpdateRules.result(job("failed", action = "rollback"), now) as ServerUpdateRules.Result.Failed).goBack)
        // an automatic one says so
        val auto = job("done").let { it.copy(job = it.job!!.copy(auto = true)) }
        assertTrue(ServerUpdateRules.result(auto, now)!!.auto)
        // a week later it is no longer shown
        assertNull(ServerUpdateRules.result(job("done", finished = now - 8 * 24 * 3600), now))
    }

    @Test fun `release dates`() {
        assertEquals(1_793_491_200.0, ServerUpdateRules.releaseSeconds("2026-11-01T00:00:00Z")!!, 0.0)
        assertEquals(1_793_491_200.0, ServerUpdateRules.releaseSeconds("2026-11-01T09:00:00+09:00")!!, 0.0)
        assertEquals(1_793_491_200.0, ServerUpdateRules.releaseSeconds("2026-11-01")!!, 0.0)
        assertNull(ServerUpdateRules.releaseSeconds("soon"))
        assertNull(ServerUpdateRules.releaseSeconds(null))
    }

    /** A clock that moves only when the follower sleeps. */
    private class Clock { var t = 0L }

    private fun follow(answers: List<() -> ServerUpdateStatus>, start: ServerUpdateStatus, clock: Clock = Clock(),
                       seen: MutableList<ServerUpdateRules.Follow> = mutableListOf()): ServerUpdateRules.Follow = runBlocking {
        var i = 0
        ServerUpdateRules.follow(
            start,
            fetch = { answers[minOf(i++, answers.size - 1)]() },
            now = { clock.t },
            sleep = { clock.t += it },
            onUpdate = { seen += it },
        )
    }

    @Test fun `following rides out the restart and ends with the result`() {
        val running = job("running", finished = null)
        val down: () -> ServerUpdateStatus = { throw ApiError(ApiError.NO_RESPONSE, "connection refused") }
        val bad: () -> ServerUpdateStatus = { throw ApiError(502, "Bad Gateway") }
        val missing: () -> ServerUpdateStatus = { throw ApiError(404, "Not Found") }
        // two minutes without an answer (60 misses at 2 s): far more than the CLI update tolerates
        val answers = listOf({ running }) + List(40) { down } + List(15) { bad } + List(5) { missing } + listOf({ job("done") })
        val seen = mutableListOf<ServerUpdateRules.Follow>()
        val end = follow(answers, running, seen = seen)
        assertEquals("done", end.status.job?.state)
        assertFalse(end.waiting)
        assertFalse(end.gaveUp)
        assertTrue(seen.any { it.waiting && !it.gaveUp })
        assertEquals(running, seen.first { it.waiting }.status)   // while waiting, the last known status stays
    }

    @Test fun `following gives up after fifteen minutes without an answer and keeps asking slowly`() {
        val running = job("running", finished = null)
        val clock = Clock()
        val seen = mutableListOf<ServerUpdateRules.Follow>()
        var calls = 0
        val end = runBlocking {
            ServerUpdateRules.follow(
                running,
                fetch = {
                    calls++
                    if (clock.t < 20 * 60_000L) throw ApiError(503, "Service Unavailable") else job("rolled_back")
                },
                now = { clock.t }, sleep = { clock.t += it }, onUpdate = { seen += it },
            )
        }
        val firstGaveUp = seen.indexOfFirst { it.gaveUp }
        assertTrue(firstGaveUp > 0)
        // given up only after 15 minutes of no answer (at 2 s a try)
        assertTrue(firstGaveUp >= (ServerUpdateRules.GIVE_UP_MS / ServerUpdateRules.POLL_MS).toInt() - 1)
        // then slower: far fewer calls than 20 minutes at 2 s
        assertTrue(calls < (20 * 60_000L / ServerUpdateRules.POLL_MS).toInt())
        assertEquals("rolled_back", end.status.job?.state)
        assertFalse(end.gaveUp)   // the server came back: the result shows
    }

    @Test fun `following stops at a refusal and at the overall limit`() {
        val running = job("running", finished = null)
        val refused = follow(listOf({ throw ApiError(403, "Forbidden") }), running)
        assertTrue(refused.gaveUp)
        assertEquals(running, refused.status)
        // the server keeps answering "running" for over an hour
        val forever = follow(listOf({ running }), running)
        assertTrue(forever.gaveUp)
        assertNotNull(forever.status.job)
    }

    @Test fun `nothing to follow when no job runs`() {
        val end = follow(listOf({ error("not asked") }), available)
        assertEquals(available, end.status)
        assertFalse(end.gaveUp)
    }

    @Test fun `while following, unavailable, no job or another job count as the server coming back`() {
        val running = job("running", finished = null)
        val other = running.copy(job = running.job!!.copy(id = "20261110-035000", state = "done"))
        val unavailable = st("""{"state":"unavailable"}""")
        val seen = mutableListOf<ServerUpdateRules.Follow>()
        val end = follow(listOf({ unavailable }, { available }, { other }, { running }, { job("done") }), running, seen = seen)
        assertEquals("done", end.status.job?.state)
        assertEquals("20261109-035000", end.status.job?.id)
        assertEquals(listOf(true, true, true, false, false), seen.map { it.waiting })
        assertTrue(seen.take(3).all { it.status == running })   // the last real answer stays shown
        // a server that never names the job again: given up after 15 minutes, still asking now and then until the hour is over
        val lost = follow(listOf({ available }), running)
        assertTrue(lost.gaveUp)
        assertEquals(running, lost.status)
        assertFalse(ServerUpdateRules.answers(unavailable, "x"))
        assertTrue(ServerUpdateRules.answers(running, ""))
        assertTrue(ServerUpdateRules.answers(running, "20261109-035000"))
        assertFalse(ServerUpdateRules.answers(other, "20261109-035000"))
    }

    @Test fun `passing failures are the restart's`() {
        assertTrue(ServerUpdateRules.passing(ApiError(ApiError.NO_RESPONSE, "x")))
        assertTrue(ServerUpdateRules.passing(ApiError(502, "x")))
        assertTrue(ServerUpdateRules.passing(ApiError(503, "x")))
        assertTrue(ServerUpdateRules.passing(ApiError(404, "x")))
        assertFalse(ServerUpdateRules.passing(ApiError(401, "x")))
        assertFalse(ServerUpdateRules.passing(ApiError(403, "x")))
        assertFalse(ServerUpdateRules.passing(ApiError(409, "x")))
    }
}
