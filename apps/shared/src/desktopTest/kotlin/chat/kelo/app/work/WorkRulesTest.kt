package chat.kelo.app.work

import chat.kelo.app.agent.AgentTask
import chat.kelo.app.agent.BrowserTab
import chat.kelo.app.agent.DayGroups
import chat.kelo.app.agent.PendingApproval
import chat.kelo.app.agent.Schedule
import chat.kelo.app.agent.Watcher
import chat.kelo.app.agent.Worker
import chat.kelo.app.chat.AgentStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The 작업 timeline: nesting, the four sections, the agenda and the badge. */
class WorkRulesTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 7)
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 11, day).atTime(hour, 0).toEpochSecond(utc).toDouble()

    private val parent = AgentTask(10, title = "여행 준비", status = AgentTask.STATUS_DONE, day = "2026-11-07", startedAt = at(7, 9))
    private val run1 = AgentTask(11, title = "숙소 찾기 1", status = AgentTask.STATUS_DONE, parentId = 10, workerId = "w1", startedAt = at(7, 10))
    private val run2 = AgentTask(12, title = "숙소 찾기 2", status = AgentTask.STATUS_RUNNING, parentId = 10, workerId = "w1", startedAt = at(7, 11))
    private val child = AgentTask(13, title = "요약", status = AgentTask.STATUS_DONE, parentId = 10, startedAt = at(7, 12))
    private val plain = AgentTask(5, title = "날씨", status = AgentTask.STATUS_FAILED, day = "2026-11-06", source = "schedule:3", startedAt = at(6, 8))
    private val job = Worker("w1", kind = "browser", title = "숙소 찾기", status = Worker.RUNNING, parentTaskId = 10, tab = "task:w1", steps = 30, maxSteps = 200)
    private val orphan = Worker("w9", kind = "research", status = Worker.RUNNING, parentTaskId = 999)

    @Test fun jobsAndTheirRunsNestUnderTheTaskThatHandedThemOff() {
        val n = Timeline.nest(listOf(child, run2, run1, parent, plain), listOf(job, orphan))
        assertEquals(listOf(10L, 5L), n.roots.map { it.task.id })
        val root = n.roots.first()
        assertEquals(listOf("w1"), root.jobs.map { it.worker.id })
        assertEquals(listOf(11L, 12L), root.jobs.single().runs.map { it.id })   // oldest run first
        assertEquals(listOf(13L), root.children.map { it.id })
        assertEquals(listOf("w9"), n.orphanJobs.map { it.worker.id })
    }

    @Test fun aFinishedTaskWithARunningJobIsStillNow() {
        val n = Timeline.nest(listOf(run2, run1, parent, plain), listOf(job, orphan))
        val now = Timeline.now(n)
        assertEquals(listOf(10L), now.tasks.map { it.task.id })
        assertEquals(listOf("w9"), now.jobs.map { it.worker.id })
        assertEquals(2, now.count)
        val calm = Timeline.nest(listOf(parent.copy(), plain), listOf(job.copy(status = Worker.DONE)))
        assertTrue(Timeline.now(calm).empty)
    }

    @Test fun pastGroupsByDayAndFilters() {
        val n = Timeline.nest(listOf(parent, plain), emptyList())
        val all = Timeline.past(n, PastFilter.ALL, today, utc)
        assertEquals(listOf(DayGroups.Label.Today, DayGroups.Label.Yesterday), all.map { it.label })
        assertEquals(listOf(10L), Timeline.past(n, PastFilter.DONE, today, utc).flatMap { g -> g.items.map { it.task.id } })
        assertEquals(listOf(5L), Timeline.past(n, PastFilter.PROBLEMS, today, utc).flatMap { g -> g.items.map { it.task.id } })
        assertEquals(listOf(5L), Timeline.past(n, PastFilter.AUTOMATIC, today, utc).flatMap { g -> g.items.map { it.task.id } })
    }

    @Test fun aFailedJobMakesItsTaskAProblem() {
        val n = Timeline.nest(listOf(parent), listOf(job.copy(status = Worker.FAILED)))
        assertTrue(n.roots.single().problem)
        assertEquals(listOf(10L), Timeline.past(n, PastFilter.PROBLEMS, today, utc).flatMap { g -> g.items.map { it.task.id } })
    }

    private fun pending(id: String, needsCard: Boolean, askedAt: Double): PendingApproval {
        val card = if (needsCard) ""","card":{"id":"c","label":"Visa","brand":"visa","last4":"4242","needs_card":true}""" else ""
        val pay = if (needsCard) ""","payment":{"site":"s","summary":"x","amount":1,"currency":"KRW","amount_text":"1원"}""" else ""
        val o = Json.parseToJsonElement("""{"type":"approval_request","approval_id":"$id","name":"t","asked_at":$askedAt$pay$card}""") as JsonObject
        return PendingApproval.parse(o)!!
    }

    @Test fun cardDeliveriesComeFirstThenTheOldestQuestionThenStoppedJobs() {
        val asks = Timeline.asks(
            listOf(pending("a", false, 5.0), pending("b", true, 9.0), pending("c", false, 1.0)),
            listOf(job, Worker("w2", status = Worker.NEEDS_INPUT, updatedAt = 3.0), Worker("w3", status = Worker.INTERRUPTED, updatedAt = 1.0)),
        )
        assertEquals(listOf("ask:b", "ask:c", "ask:a", "askjob:w3", "askjob:w2"), asks.map { it.key })
        assertTrue((asks.first() as Ask.Approval).deliver)
    }

    @Test fun followupsOnHoldComeLastWithoutRepeatingWhatIsListed() {
        fun hold(id: String, at: Double, vararg acts: chat.kelo.app.followup.FollowupAction) =
            chat.kelo.app.followup.Followup(id, "reply", "t$id", chat.kelo.app.followup.Followup.ON_HOLD, at, acts.toList())
        val asks = Timeline.asks(
            listOf(pending("a", false, 5.0)),
            listOf(Worker("w2", status = Worker.NEEDS_INPUT, updatedAt = 3.0)),
            listOf(
                hold("f2", 9.0), hold("f1", 2.0),
                hold("f3", 1.0, chat.kelo.app.followup.FollowupAction("resume", workerId = "w2")),
                hold("f4", 1.0, chat.kelo.app.followup.FollowupAction("approve", approvalId = "a")),
                chat.kelo.app.followup.Followup("f5", "reply", "", "first", 0.0, emptyList()),
            ),
        )
        assertEquals(listOf("ask:a", "askjob:w2", "askhold:f1", "askhold:f2"), asks.map { it.key })
    }

    @Test fun theLiveTabOfATaskOrAJob() {
        val tabs = listOf(BrowserTab("task:x", taskId = 12, status = "working"), BrowserTab("old", taskId = 5, status = "ended"))
        assertEquals("task:x", Timeline.tabFor(run2, tabs))
        assertNull(Timeline.tabFor(plain, tabs))                      // its tab has ended
        assertEquals("task:w1", Timeline.tabFor(run1, emptyList(), listOf(job)))
        assertEquals("task:w1", Timeline.tabFor(job))
        assertNull(Timeline.tabFor(orphan))                            // research with no picture: no browser
        assertEquals(0.15f, Timeline.progress(job)!!, 0.001f)
        assertNull(Timeline.progress(orphan))
    }

    @Test fun aDoneTaskWithAGoingJobIsInBackground() {
        val nodeRunning = TaskNode(parent, listOf(JobNode(job)))
        assertTrue(nodeRunning.inBackground)

        val nodeNeedsInput = TaskNode(parent, listOf(JobNode(job.copy(status = Worker.NEEDS_INPUT))))
        assertTrue(nodeNeedsInput.inBackground)

        val nodeDone = TaskNode(parent, listOf(JobNode(job.copy(status = Worker.DONE))))
        assertFalse(nodeDone.inBackground)

        val runningTask = TaskNode(run2, listOf(JobNode(job)))
        assertFalse(runningTask.inBackground)
    }

    @Test fun liveViewTabOnlyOfferedWhileJobIsGoing() {
        assertNull(Timeline.tabFor(Worker("w1", kind = "browser", status = Worker.STOPPED, tab = "task:w1")))
        assertEquals("task:w1", Timeline.tabFor(Worker("w1", kind = "browser", status = Worker.RUNNING, tab = "task:w1")))
        assertEquals("task:w1", Timeline.tabFor(Worker("w1", kind = "browser", status = Worker.BLOCKED, tab = "task:w1")))
    }

    private fun sched(id: Long, next: Double?, enabled: Boolean = true, paused: Boolean = false, name: String = "s$id") =
        Schedule(id, name = name, enabled = enabled, paused = paused, nextRunTs = next)

    @Test fun theAgendaGoesByDayThenTimeAndKeepsTheRestApart() {
        val plan = Agenda.build(
            listOf(
                sched(1, at(8, 9)), sched(2, at(7, 18)), sched(3, at(7, 7)), sched(4, null), sched(5, at(9, 9), enabled = false),
                sched(6, at(9, 9), paused = true), sched(7, at(8, 9), name = "a"),
            ),
            utc,
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)), plan.days.map { it.date })
        assertEquals(listOf(3L, 2L), plan.days[0].entries.map { it.schedule.id })
        assertEquals(listOf(7L, 1L), plan.days[1].entries.map { it.schedule.id })   // same time: by name
        assertEquals(listOf(4L), plan.unscheduled.map { it.id })
        assertEquals(listOf(6L, 5L), plan.paused.map { it.id })                      // paused by a run before switched off
        assertFalse(plan.empty)
        assertTrue(Agenda.build(emptyList(), utc).empty)
    }

    @Test fun agendaDayWords() {
        assertEquals(Agenda.When.TODAY, Agenda.whenOf(today, today))
        assertEquals(Agenda.When.TOMORROW, Agenda.whenOf(today.plusDays(1), today))
        assertEquals(Agenda.When.LATER, Agenda.whenOf(today.plusDays(2), today))
    }

    @Test fun theBadgeShowsOnlyWhileSomethingWaitsForTheOwner() {
        assertNull(WorkBadge.of(null))
        assertNull(WorkBadge.of(AgentStatus()))
        // each case on its own lights it, with its count
        assertEquals(WorkBadge.Shown(2), WorkBadge.of(AgentStatus(pendingApprovals = 2)))
        assertEquals(WorkBadge.Shown(1), WorkBadge.of(AgentStatus(openFollowups = 1)))
        assertEquals(WorkBadge.Shown(1), WorkBadge.of(AgentStatus(secretRequests = 1)))
        assertEquals(WorkBadge.Shown(4), WorkBadge.of(AgentStatus(pendingApprovals = 1, openFollowups = 2, secretRequests = 1, unseenTasks = 9)))
        // finished work and notifications nobody looked at: no badge, no dot
        assertNull(WorkBadge.of(AgentStatus(unseenTasks = 3, unseenNotifications = 5)))
        // handled: the next poll has the count gone, and the badge with it
        val waiting = AgentStatus(pendingApprovals = 1, openFollowups = 1, secretRequests = 1)
        assertNull(WorkBadge.of(waiting.copy(pendingApprovals = 0, openFollowups = 0, secretRequests = 0)))
        assertEquals(WorkBadge.Shown(2), WorkBadge.of(waiting.copy(pendingApprovals = 0)))
        assertEquals(WorkBadge.Shown(1), WorkBadge.of(waiting.copy(pendingApprovals = 0, openFollowups = 0)))
    }

    @Test fun theBadgeSaysWhatWaitsAndAnAnsweredEntryLeavesItAtOnce() {
        val s = chat.kelo.app.core.KeloApi.json.decodeFromString(AgentStatus.serializer(),
            """{"pending_approvals": 2, "open_followups": 0, "secret_requests": 2, "secret_request_kinds": {"key": 1, "login": 1}}""")
        assertEquals(listOf(WorkBadge.Part(WorkBadge.APPROVALS, 2), WorkBadge.Part(WorkBadge.KEYS, 1), WorkBadge.Part(WorkBadge.LOGINS, 1)),
            WorkBadge.parts(s))
        // an older server: entries without kinds still say something
        assertEquals(listOf(WorkBadge.Part(WorkBadge.ENTRIES, 1)), WorkBadge.parts(AgentStatus(secretRequests = 1)))
        // the owner's Telegram token is the only thing that waits: cancelling it clears the badge without waiting for the next poll
        val a = chat.kelo.app.chat.AgentActivity()
        a.status.value = AgentStatus(secretRequests = 1, secretKinds = mapOf("key" to 1))
        assertEquals(WorkBadge.Shown(1), WorkBadge.of(a.status.value))
        a.entryClosed("key")
        assertNull(WorkBadge.of(a.status.value))
        assertEquals(emptyMap<String, Int>(), a.status.value!!.secretKinds)
        a.entryClosed("key")                                                   // twice (the poll came first): never below zero
        assertEquals(0, a.status.value!!.secretRequests)
    }

    @Test fun waitingEntriesAreRowsInAsksAfterTheApprovals() {
        val token = chat.kelo.app.chat.vault.SecretRequest("sr1", kind = "key", name = "telegram", label = "텔레그램 봇 토큰", createdAt = 4.0)
        val draft = chat.kelo.app.chat.vault.SecretRequest("sr2", kind = "login", name = "x", source = "draft", createdAt = 2.0)
        val done = chat.kelo.app.chat.vault.SecretRequest("sr3", kind = "key", state = "saved", createdAt = 1.0)
        val asks = Timeline.asks(listOf(pending("a", false, 5.0)), listOf(Worker("w2", status = Worker.NEEDS_INPUT, updatedAt = 3.0)),
            entries = listOf(token, draft, done))
        assertEquals(listOf("ask:a", "askentry:sr2", "askentry:sr1", "askjob:w2"), asks.map { it.key })
        assertTrue((asks[2] as Ask.Entry).chatCard)            // the agent asked in the chat: a tap goes to its card
        assertFalse((asks[1] as Ask.Entry).chatCard)           // the Keychain's own: no card in the chat
    }

    @Test fun theStatusReadsTheFollowupCount() {
        val s = chat.kelo.app.core.KeloApi.json.decodeFromString(AgentStatus.serializer(),
            """{"pending_approvals": 0, "open_followups": 2, "secret_requests": 1, "unseen_tasks": 5}""")
        assertEquals(3, WorkBadge.waiting(s))
    }

    @Test fun watchersThatNeedALookComeFirst() {
        val list = listOf(
            Watcher(1, "b", state = "off"), Watcher(2, "a", state = "watching", enabled = true), Watcher(3, "c", state = "error", enabled = true),
            Watcher(4, "d", state = "watching", enabled = true, lastFiredAt = 50.0),
        )
        assertEquals(listOf(3L, 4L, 2L, 1L), Watching.order(list).map { it.id })
    }
}
