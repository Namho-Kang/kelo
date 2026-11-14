package chat.kelo.app.chat

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import chat.kelo.app.ui.character.KeloMood

class AgentActivityTest {
    private val none = JsonObject(emptyMap())

    @Test fun workChangedCounterStartsAtZeroAndIncrements() {
        val a = AgentActivity()
        assertEquals(0, a.workChanged.value)
        a.workChanged.update { it + 1 }
        assertEquals(1, a.workChanged.value)
    }

    @Test fun theFollowedReplyWinsOverThePolledStatus() {
        assertEquals("사이트 여는 중", AgentActivityRules.shown(true, "사이트 여는 중", "다른 일"))
        assertNull(AgentActivityRules.shown(true, null, "다른 일"))
        assertEquals("다른 일", AgentActivityRules.shown(false, "사이트 여는 중", "다른 일"))
        assertNull(AgentActivityRules.shown(false, null, "  "))
    }

    @Test fun statusLineOnlyWhileBusy() {
        assertNull(AgentActivityRules.line(AgentStatus(busy = false, status = "x")))
        assertEquals("입력하는 중", AgentActivityRules.line(AgentStatus(busy = true, status = "입력하는 중", title = "t")))
        assertEquals("매일 뉴스 요약", AgentActivityRules.line(AgentStatus(busy = true, status = null, title = "매일 뉴스 요약")))
        assertNull(AgentActivityRules.line(AgentStatus(busy = true)))
    }

    @Test fun turnEventsMoveTheLine() {
        var line: String? = null
        line = AgentActivityRules.next(line, TurnEvent.ToolStarted("1", "read_skill", none, "안내서 확인", hidden = true))
        assertNull(line)
        line = AgentActivityRules.next(line, TurnEvent.ToolStarted("2", "browser_open", none, "사이트 여는 중"))
        assertEquals("사이트 여는 중", line)
        line = AgentActivityRules.next(line, TurnEvent.ToolStarted("3", "x", none))   // no label: keep
        assertEquals("사이트 여는 중", line)
        line = AgentActivityRules.next(line, TurnEvent.Browser("2", "", "", "", "구매 진행 중", null))
        assertEquals("구매 진행 중", line)
        line = AgentActivityRules.next(line, TurnEvent.Thinking)
        assertEquals("구매 진행 중", line)
        line = AgentActivityRules.next(line, TurnEvent.Text("다 됐어요"))
        assertNull(line)
    }

    @Test fun followedReplyKindFollowsItsTools() {
        var k = TurnKind.Thinking
        k = AgentActivityRules.turnKind(k, TurnEvent.ToolStarted("1", "read_file", none, "파일 읽는 중"))
        assertEquals(TurnKind.Reading, k)
        k = AgentActivityRules.turnKind(k, TurnEvent.ToolStarted("2", "browser_open", none, "사이트 여는 중"))
        assertEquals(TurnKind.Browser, k)
        k = AgentActivityRules.turnKind(k, TurnEvent.Thinking)
        assertEquals(TurnKind.Browser, k)
        k = AgentActivityRules.turnKind(k, TurnEvent.ToolStarted("3", "read_skill", none, "", hidden = true))   // hidden: keep
        assertEquals(TurnKind.Browser, k)
        k = AgentActivityRules.turnKind(k, TurnEvent.ToolStarted("4", "schedule_create", none, "일정 만드는 중"))
        assertEquals(TurnKind.Tool, k)
        k = AgentActivityRules.turnKind(k, TurnEvent.Browser("5", "", "", "", "사이트 보는 중", null))
        assertEquals(TurnKind.Browser, k)
        k = AgentActivityRules.turnKind(k, TurnEvent.Text("다 됐어요"))
        assertEquals(TurnKind.Thinking, k)
        assertEquals(TurnKind.Reading, AgentActivityRules.toolKind("memory_search"))
        assertEquals(TurnKind.Reading, AgentActivityRules.toolKind("email_read"))
    }

    @Test fun polledRunBrowsesOnlyInARecentOrMovingWorkingTab() {
        val busy = AgentStatus(busy = true)
        val now = 1_000_000.0
        val recent = BrowserTabBrief("task:a", "working", now - 5)
        val stale = BrowserTabBrief("task:a", "working", now - 600)
        assertEquals(true, AgentActivityRules.polledBrowsing(busy, listOf(recent), emptyMap(), now))
        assertEquals(false, AgentActivityRules.polledBrowsing(busy, listOf(stale), emptyMap(), now))
        // the phone's clock is off, but the tab's last action moved since the last poll
        assertEquals(true, AgentActivityRules.polledBrowsing(busy, listOf(stale), mapOf("task:a" to now - 700), now))
        assertEquals(false, AgentActivityRules.polledBrowsing(busy, listOf(stale), mapOf("task:a" to now - 600), now))
        // an idle tab, or nothing running
        assertEquals(false, AgentActivityRules.polledBrowsing(busy, listOf(recent.copy(status = "idle")), emptyMap(), now))
        assertEquals(false, AgentActivityRules.polledBrowsing(AgentStatus(busy = false), listOf(recent), emptyMap(), now))
        assertEquals(false, AgentActivityRules.polledBrowsing(null, listOf(recent), emptyMap(), now))
    }

    private fun mood(m: AgentActivityRules.MoodInput) = AgentActivityRules.mood(m)
    private val busy = AgentStatus(busy = true)
    private val asking = AgentStatus(busy = true, pendingApprovals = 1)

    @Test fun moodPriority() {
        val all = AgentActivityRules.MoodInput(
            offline = true, status = asking, stuck = true, listening = true, following = true, turnKind = TurnKind.Browser,
            polledBrowsing = true, sinceDoneMs = 100, night = true,
        )
        // each step down the list once the ones above it are gone
        assertEquals(KeloMood.Offline, mood(all))
        assertEquals(KeloMood.Approval, mood(all.copy(offline = false)))
        val noAsk = all.copy(offline = false, status = busy)
        assertEquals(KeloMood.Error, mood(noAsk))
        assertEquals(KeloMood.Listening, mood(noAsk.copy(stuck = false)))
        val work = noAsk.copy(stuck = false, listening = false)
        assertEquals(KeloMood.Browser, mood(work))
        assertEquals(KeloMood.Reading, mood(work.copy(turnKind = TurnKind.Reading)))
        assertEquals(KeloMood.Working, mood(work.copy(turnKind = TurnKind.Tool)))
        assertEquals(KeloMood.Thinking, mood(work.copy(turnKind = TurnKind.Thinking)))
        // not following: the poll says
        assertEquals(KeloMood.Browser, mood(work.copy(following = false)))
        assertEquals(KeloMood.Working, mood(work.copy(following = false, polledBrowsing = false)))
        val rest = work.copy(following = false, status = AgentStatus(busy = false))
        assertEquals(KeloMood.Done, mood(rest))
        assertEquals(KeloMood.Sleeping, mood(rest.copy(sinceDoneMs = null)))
        assertEquals(KeloMood.Sleeping, mood(rest.copy(sinceDoneMs = AgentActivityRules.DONE_MS)))   // the cheer is over
        assertEquals(KeloMood.Idle, mood(rest.copy(sinceDoneMs = null, night = false)))
        assertEquals(KeloMood.Idle, mood(AgentActivityRules.MoodInput()))
    }

    @Test fun stuckOnTheUser() {
        val now = 2_000_000.0
        assertEquals(true, AgentActivityRules.needsUser(TaskBrief(1, "failed", seen = false), emptyList(), now))
        assertEquals(false, AgentActivityRules.needsUser(TaskBrief(1, "failed", seen = true), emptyList(), now))   // looked at
        assertEquals(false, AgentActivityRules.needsUser(TaskBrief(1, "done", seen = false), emptyList(), now))
        assertEquals(true, AgentActivityRules.needsUser(null, listOf(WorkerBrief("w", "needs_input", now - 600)), now))
        assertEquals(false, AgentActivityRules.needsUser(null, listOf(WorkerBrief("w", "needs_input", now - 13 * 3600)), now))   // old
        assertEquals(false, AgentActivityRules.needsUser(null, listOf(WorkerBrief("w", "running", now)), now))
    }

    @Test fun finishedOnlyWhenARunEndsWell() {
        val idle = AgentStatus(busy = false)
        assertEquals(true, AgentActivityRules.finished(busy, idle, TaskBrief(1, "done")))
        assertEquals(false, AgentActivityRules.finished(null, idle, TaskBrief(1, "done")))     // app start
        assertEquals(false, AgentActivityRules.finished(idle, idle, TaskBrief(1, "done")))
        assertEquals(false, AgentActivityRules.finished(busy, idle, TaskBrief(1, "failed")))
        assertEquals(false, AgentActivityRules.finished(busy, busy, TaskBrief(1, "done")))
    }

    @Test fun lateNight() {
        assertEquals(true, AgentActivityRules.isNight(23))
        assertEquals(true, AgentActivityRules.isNight(3))
        assertEquals(false, AgentActivityRules.isNight(7))
        assertEquals(false, AgentActivityRules.isNight(14))
    }

    // ---- sleep only after a quiet while (the owner saw it fall asleep right after each reply at night)
    private val quietNight = AgentActivityRules.MoodInput(status = AgentStatus(busy = false), night = true)
    private val sleepAfter = AgentActivityRules.SLEEP_AFTER_IDLE_MS

    @Test fun atNightAFinishedReplyCheersThenRestsAwake() {
        assertEquals(KeloMood.Done, mood(quietNight.copy(sinceDoneMs = 0, idleMs = 0)))
        assertEquals(KeloMood.Idle, mood(quietNight.copy(sinceDoneMs = null, idleMs = AgentActivityRules.DONE_MS)))
        assertEquals(KeloMood.Idle, mood(quietNight.copy(sinceDoneMs = null, idleMs = 60_000)))
    }

    @Test fun sleepsOnlyAfterFiveQuietMinutes() {
        assertEquals(5 * 60_000L, sleepAfter)
        assertEquals(KeloMood.Idle, mood(quietNight.copy(idleMs = 4 * 60_000L + 59_000L)))   // 4:59
        assertEquals(KeloMood.Sleeping, mood(quietNight.copy(idleMs = sleepAfter)))           // 5:00
        assertEquals(KeloMood.Sleeping, mood(quietNight.copy(idleMs = 3 * 3600_000L)))
        assertEquals(KeloMood.Sleeping, mood(quietNight.copy(idleMs = null)))                 // nothing since launch
    }

    @Test fun typingOrANewMessageWakesIt() {
        val a = AgentActivity()
        a.touch(1_000L)
        val later = 1_000L + sleepAfter + 30_000L
        assertEquals(KeloMood.Sleeping, mood(quietNight.copy(idleMs = AgentActivityRules.idleMs(later, a.activeAt.value))))
        a.touch(later)   // a key typed, or a message sent or come in
        assertEquals(KeloMood.Idle, mood(quietNight.copy(idleMs = AgentActivityRules.idleMs(later, a.activeAt.value))))
        // keys within a second do not move it again; a later one does
        a.touch(later + 400)
        assertEquals(later, a.activeAt.value)
        a.touch(later + 1_000)
        assertEquals(later + 1_000, a.activeAt.value)
        assertNull(AgentActivityRules.idleMs(later, 0L))
        assertEquals(0L, AgentActivityRules.idleMs(5, 10))   // a clock step back is not negative
    }

    @Test fun daytimeIsNeverSleeping() {
        val day = quietNight.copy(night = false)
        assertEquals(KeloMood.Idle, mood(day.copy(idleMs = null)))
        assertEquals(KeloMood.Idle, mood(day.copy(idleMs = 10 * 3600_000L)))
        val utc = java.time.ZoneOffset.UTC
        val noon = java.time.Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()
        assertEquals(false, AgentActivityRules.nightAt(noon, utc, null))
        assertEquals(true, AgentActivityRules.nightAt(noon + 11 * 3600_000L, utc, null))   // 23:00
    }

    @Test fun theOwnersContactHoursAreTheNightWhenKnown() {
        val h = SleepHours(wakeStart = "09:00", wakeEnd = "01:30", hoursSource = "learned", timezone = "Asia/Seoul")
        assertEquals(9 * 60 to 90, AgentActivityRules.wakeWindow(h))
        assertEquals(false, AgentActivityRules.isNight(23 * 60 + 30, h))   // the fallback would sleep at 23:30
        assertEquals(false, AgentActivityRules.isNight(60, h))
        assertEquals(true, AgentActivityRules.isNight(90, h))              // the end is not included
        assertEquals(true, AgentActivityRules.isNight(8 * 60, h))          // the fallback is awake at 08:00
        assertEquals(false, AgentActivityRules.isNight(9 * 60, h))
        val manual = h.copy(wakeStart = "08:00", wakeEnd = "21:00", hoursSource = "manual")
        assertEquals(true, AgentActivityRules.isNight(21 * 60 + 5, manual))
        assertEquals(false, AgentActivityRules.isNight(12 * 60, manual))
        assertEquals(false, AgentActivityRules.isNight(3 * 60, manual.copy(wakeEnd = "08:00")))   // equal ends: awake all day
        // the server's untouched default, a missing answer or a bad time: 23:00–07:00
        val untouched = SleepHours("07:00", "22:00", "default", "Asia/Seoul")
        assertNull(AgentActivityRules.wakeWindow(untouched))
        assertEquals(false, AgentActivityRules.isNight(22 * 60 + 30, untouched))
        assertEquals(true, AgentActivityRules.isNight(23 * 60, null))
        assertNull(AgentActivityRules.wakeWindow(h.copy(wakeEnd = "25:00")))
        // read in the owner's time zone: 15:00 UTC is 00:00 in Seoul, inside 09:00–01:30
        val t = java.time.Instant.parse("2026-11-09T15:00:00Z").toEpochMilli()
        assertEquals(false, AgentActivityRules.nightAt(t, java.time.ZoneOffset.UTC, h))
        assertEquals(true, AgentActivityRules.nightAt(t + 2 * 3600_000L, java.time.ZoneOffset.UTC, h))   // 02:00 in Seoul
        // the JSON of GET /api/proactive
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<SleepHours>(
            """{"enabled":true,"wake_start":"09:00","wake_end":"01:30","hours_source":"learned","timezone":"Asia/Seoul","awake":true}""",
        )
        assertEquals(h, parsed)
    }

    @Test fun theNightFollowsTheDayTypeWeekendsAndHolidays() {
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        val h = SleepHours(wakeStart = "07:00", wakeEnd = "23:00", hoursSource = "manual", timezone = "Asia/Seoul",
            weekendStart = "10:00", weekendEnd = "01:00", weekendSource = "learned", dayTypes = mapOf("2026-11-11" to "weekend"))
        fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(seoul).toInstant().toEpochMilli()
        val utc = java.time.ZoneOffset.UTC
        assertEquals(false, AgentActivityRules.nightAt(at("2026-11-09T08:00"), utc, h))     // Monday: the weekday window
        assertEquals(true, AgentActivityRules.nightAt(at("2026-11-14T08:00"), utc, h))      // Saturday 08:00: before the weekend start
        assertEquals(false, AgentActivityRules.nightAt(at("2026-11-14T10:30"), utc, h))
        assertEquals(true, AgentActivityRules.nightAt(at("2026-11-11T08:00"), utc, h))      // a holiday the server named: weekend hours
        assertEquals(false, AgentActivityRules.nightAt(at("2026-11-15T00:30"), utc, h))     // Saturday night past midnight: still Saturday's window
        assertEquals(true, AgentActivityRules.nightAt(at("2026-11-14T00:30"), utc, h))      // Friday night past 23:00: Friday's weekday window
        // weekends without hours of their own follow the weekdays; an older server sends no day types: Sat/Sun
        val follows = h.copy(weekendSource = "default", dayTypes = emptyMap())
        assertEquals(false, AgentActivityRules.nightAt(at("2026-11-14T08:00"), utc, follows))
        assertEquals("weekend", AgentActivityRules.dayType(null, java.time.LocalDate.parse("2026-11-15")))
        assertEquals("weekday", AgentActivityRules.dayType(h, java.time.LocalDate.parse("2026-11-12")))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<SleepHours>(
            """{"wake_start":"07:00","wake_end":"23:00","hours_source":"manual","timezone":"Asia/Seoul","weekend_start":"10:00",
            "weekend_end":"01:00","weekend_source":"learned","day_types":{"2026-11-11":"weekend"},"day_type":"weekday"}""",
        )
        assertEquals(h, parsed)
    }
}
