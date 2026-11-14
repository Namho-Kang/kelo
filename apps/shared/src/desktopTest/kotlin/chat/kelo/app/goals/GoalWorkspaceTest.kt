package chat.kelo.app.goals

import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.QuickReplyRules
import chat.kelo.app.chat.ReplyQuestion
import chat.kelo.app.chat.ReplySet
import chat.kelo.app.chat.StoredMessage
import chat.kelo.app.chat.TurnEvent
import chat.kelo.app.core.Goal
import chat.kelo.app.core.GoalPlan
import chat.kelo.app.core.KeloApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalWorkspaceTest {
    @Test fun readsAGoalPageAndAnOlderGoalList() {
        val w = KeloApi.json.decodeFromString<GoalWorkspace>("""
            {"goal":{"id":3,"title":"G","category":"","note":"","status":"tracking","origin":"suggestion","plan":{"done":1,"total":2},
                     "next_step":"second","asking":true,"created_at":1,"updated_at":2},
             "facts":[{"key":"a","value":"1","skipped":false,"source":"user","updated_at":1},{"key":"b","value":"","skipped":true}],
             "steps":[{"id":1,"title":"first","status":"done","owner":"user","due":null,"done_at":5},
                      {"id":2,"title":"second","status":"doing","owner":"agent","due":"2026-11-20"}],
             "metrics":[{"id":1,"name":"n","unit":"pt","start":10,"target":20,"values":[{"value":12,"note":"","at":1},{"value":15.5,"at":2}]}],
             "log":[{"id":1,"at":1,"text":"x","kind":"note","by":"agent"}],
             "documents":[{"id":1,"path":"files/a.pdf","title":"A","added_at":1,"exists":true}],
             "schedules":[{"id":4,"name":"Check-in","description":"Every Monday","enabled":true,"goal_id":3,"extra":1}]}
        """)
        assertEquals(GoalPlan(1, 2), w.goal.plan)
        assertEquals("second", w.goal.nextStep)
        assertTrue(w.goal.asking)
        assertTrue(w.facts[1].skipped)
        assertTrue(w.steps[0].mine && !w.steps[1].mine)
        assertEquals("Check-in", w.schedules.single().name)
        val old = KeloApi.json.decodeFromString<List<Goal>>("""[{"id":1,"title":"x","category":"","note":"","status":"tracking"}]""")
        assertEquals(GoalPlan(0, 0), old.single().plan)
        assertFalse(old.single().asking)
    }

    @Test fun onlyTheUsersOwnStepsTick() {
        val mine = GoalStep(1, "a", owner = "user")
        val kelos = GoalStep(2, "b", owner = "agent")
        assertTrue(GoalDetailRules.canTick(mine))
        assertFalse(GoalDetailRules.canTick(kelos))
        assertEquals("done", GoalDetailRules.toggled(mine))
        assertEquals("todo", GoalDetailRules.toggled(mine.copy(status = "done")))
    }

    @Test fun numbersReadLikePeopleWriteThem() {
        assertEquals("72", GoalDetailRules.number(72.0))
        assertEquals("72.5", GoalDetailRules.number(72.5))
        assertEquals("0.33", GoalDetailRules.number(1.0 / 3))
        assertEquals("-", GoalDetailRules.number(null))
        assertEquals("15 pt", GoalDetailRules.withUnit(15.0, "pt"))
    }

    @Test fun theChartSpansTheValuesAndTheTarget() {
        val m = GoalMetric(1, "n", target = 20.0, values = listOf(GoalMetricValue(10.0), GoalMetricValue(15.0)))
        assertEquals(listOf(0f to 0f, 1f to 0.5f), GoalDetailRules.chart(m))
        assertEquals(1f, GoalDetailRules.targetLevel(m))
        assertEquals(emptyList<Pair<Float, Float>>(), GoalDetailRules.chart(m.copy(values = emptyList())))
        assertNull(GoalDetailRules.targetLevel(m.copy(values = emptyList())))
    }

    @Test fun suggestionsSettleOutOfSight() {
        val s = GoalsState(suggestions = listOf(GoalSuggestion(1, "a"), GoalSuggestion(2, "b")), settling = setOf(1L))
        assertEquals(listOf(2L), s.shownSuggestions.map { it.id })
    }

    @Test fun answerButtonsShowUnderTheNewestAgentMessageUntilTheUserWrites() {
        val set = ReplySet(listOf(ReplyQuestion("Q", listOf("a", "b"))), goalId = 3)
        val items = listOf(ChatItem.Msg("m1", mine = false, text = "q?", id = 1, replies = set))
        assertTrue(QuickReplyRules.isOpen(items, "m1"))
        assertFalse(QuickReplyRules.isOpen(items + ChatItem.Msg("m2", mine = true, text = "a"), "m1"))
        assertTrue(QuickReplyRules.sendsAtOnce(set))
        val two = ReplySet(listOf(ReplyQuestion("Where", listOf("a", "b")), ReplyQuestion("When", listOf("now", "later"))))
        assertFalse(QuickReplyRules.sendsAtOnce(two))
        assertEquals("Where: b\nWhen: now", QuickReplyRules.compose(two, mapOf(1 to "now", 0 to "b")))
        assertEquals("When: later", QuickReplyRules.compose(two, mapOf(1 to "later")))
    }

    @Test fun storedMessagesCarryTheirButtonsAndTheEventIsKnown() {
        val ms = KeloApi.json.decodeFromString<List<StoredMessage>>("""
            [{"id":5,"role":"assistant","content":"q?","created_at":1,"replies":{"questions":[{"question":"Q","options":["a","b"]}],"goal_id":3}},
             {"id":6,"role":"assistant","content":"plain","created_at":2}]
        """)
        assertEquals(3L, ms[0].replies?.goalId)
        assertEquals(listOf("a", "b"), ms[0].replies?.questions?.single()?.options)
        assertNull(ms[1].replies)
        assertEquals(TurnEvent.Replies, TurnEvent.parse(Json.parseToJsonElement("""{"type":"replies","questions":[],"goal_id":null}""")))
    }
}
