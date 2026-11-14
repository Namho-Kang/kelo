package chat.kelo.app.agent

import chat.kelo.app.chat.ChatItem
import chat.kelo.app.chat.ChatReducer
import chat.kelo.app.chat.TurnEvent
import chat.kelo.app.notify.Channel
import chat.kelo.app.notify.NoteTypes
import chat.kelo.app.notify.NotifyRules
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup card of a "when X happens, do Y" task, its grants on task pages, and urgent cards (server schedule_grants.py). */
class TaskGrantCardTest {
    private val setup = """{"type":"approval_request","approval_id":"a1","id":"c1","name":"task_allow","arguments":{},"label":"x",
        "lines":[{"label":"When","value":"a seat shows up"}],
        "grant":{"scope":"watcher","task_name":"Seat watch","task_when":"every 5 min","trigger":"a cancelled seat shows up",
          "action":"buy one ticket","once":true,"urgent":true,"missed":"the seat goes to someone else","login":null,
          "purchase":{"site":"tickets.example.com","what":"concert ticket","max_amount":50000,"currency":"KRW","max_amount_text":"50,000 KRW",
            "max_count":1,"monthly_cap":50000,"monthly_cap_text":"50,000 KRW","card":{"id":"c","label":"Visa","last4":"4242","auto_pay":true,
            "auto_limit":30000,"auto_limit_text":"30,000 KRW","phone_only":false},"card_note":"...","shipping":""},
          "send":{"to":["wife@example.com"],"site":"","template":"Got it: {item}","varies":"the item","max_count":1},
          "change":{"what":"move my run","where":["calendar","connector:notion"],"allow_delete":false,"max_count":1},
          "something_new":42}}"""

    private fun parse(json: String) = TurnEvent.parse(Json.parseToJsonElement(json))

    @Test fun theSetupCardIsReadWithEveryPartAndUnknownFieldsAreIgnored() {
        val ev = parse(setup) as TurnEvent.ApprovalRequest
        assertNotNull(ev.grant)
        val g = ev.grant!!
        assertEquals("watcher", g.scope)
        assertEquals("a cancelled seat shows up", g.trigger)
        assertEquals("buy one ticket", g.action)
        assertTrue(g.once && g.urgent)
        assertEquals("50,000 KRW", g.purchase!!.maxAmountText)
        assertEquals(1, g.purchase!!.maxCount)
        assertEquals("30,000 KRW", g.purchase!!.card!!.autoLimitText)
        assertEquals(listOf("wife@example.com"), g.send!!.to)
        assertFalse(g.change!!.allowDelete)
        assertNull(g.login)
        // it reaches the chat's card
        val items = ChatReducer.apply(emptyList(), ev) { "k" }
        val card = items.single() as ChatItem.Approval
        assertEquals(g, card.grant)
    }

    @Test fun anOrdinaryCardHasNoSetupPartAndAnUrgentOneSaysWhatIsMissed() {
        val plain = parse("""{"type":"approval_request","approval_id":"a2","id":"c2","name":"send","arguments":{}}""") as TurnEvent.ApprovalRequest
        assertNull(plain.grant)
        assertFalse(plain.urgent)
        val urgent = parse("""{"type":"approval_request","approval_id":"a3","id":"c3","name":"send","arguments":{},"urgent":true,
            "missed":"the seat goes"}""") as TurnEvent.ApprovalRequest
        assertTrue(urgent.urgent)
        assertEquals("the seat goes", urgent.missed)
        val item = ChatReducer.apply(emptyList(), urgent) { "k" }.single() as ChatItem.Approval
        assertTrue(item.urgent)
        assertEquals("the seat goes", item.missed)
        assertNull(TaskGrantAsk.parse(Json.parseToJsonElement("""{"scope":"schedule"}""")))   // nothing to show: not a setup card
    }

    @Test fun theCardLineComparesTheCardsOwnLimitWithTheAmountAskedFor() {
        val g = (parse(setup) as TurnEvent.ApprovalRequest).grant!!
        val p = g.purchase!!
        assertEquals(CardLimitLine.AUTO_LIMIT, TaskGrantRules.cardLine(p))
        assertTrue(TaskGrantRules.aboveCardLimit(p))                                          // 50,000 asked, the card alone pays 30,000
        assertFalse(TaskGrantRules.aboveCardLimit(p.copy(maxAmount = 30000.0)))
        assertEquals(CardLimitLine.PHONE_ONLY, TaskGrantRules.cardLine(p.copy(card = p.card!!.copy(phoneOnly = true, autoPay = false))))
        assertEquals(CardLimitLine.NOT_AUTO, TaskGrantRules.cardLine(p.copy(card = p.card!!.copy(autoPay = false, autoLimitText = ""))))
        assertEquals(CardLimitLine.NONE, TaskGrantRules.cardLine(p.copy(card = null)))
        assertEquals("calendar, notion", TaskGrantRules.places(g.change!!))
        assertEquals("wife@example.com, shop.example.com", TaskGrantRules.recipients(g.send!!.copy(site = "shop.example.com")))
    }

    @Test fun grantsOfEveryTaskAreReadAndRevokedUnderTheirOwnTask() {
        val json = Json { ignoreUnknownKeys = true }
        val w = json.decodeFromString(ScheduleGrant.serializer(), """{"id":5,"scope":"watcher","scope_ref":"3","task_name":"Seat watch","kind":"send",
            "site":"","targets":["wife@example.com","connector:notion"],"max_count":2,"used":1,"once":true,"trigger":"a seat shows up"}""")
        assertTrue(w.isSend)
        assertEquals("Seat watch", w.ownerName)
        assertEquals("wife@example.com, notion", w.placesText)
        assertEquals(1, w.left)
        assertEquals("/api/watchers/3/grants/5", ScheduleGrantCalls.revokePath(w))
        assertEquals("/api/workers/j1/grants/6", ScheduleGrantCalls.revokePath(w.copy(id = 6, scope = "job", scopeRef = "j1")))
        val s = json.decodeFromString(ScheduleGrant.serializer(), """{"id":7,"schedule_id":9,"schedule_name":"Weekly beans","kind":"purchase"}""")
        assertEquals("schedule", s.scope)                                                     // an older server: schedules only
        assertEquals("/api/schedules/9/grants/7", ScheduleGrantCalls.revokePath(s))
        assertEquals("Weekly beans", s.ownerName)
        assertNull(s.left)
    }

    @Test fun anUrgentCardHasItsOwnLouderChannel() {
        assertEquals(Channel.URGENT, NotifyRules.channelOf(NoteTypes.APPROVAL_WAITING, null, urgent = true))
        assertEquals(Channel.APPROVALS, NotifyRules.channelOf(NoteTypes.APPROVAL_WAITING, null, urgent = false))
        val n = Json { ignoreUnknownKeys = true }.decodeFromString(ServerNotification.serializer(),
            """{"id":1,"type":"approval_waiting","title":"Urgent: please confirm","urgent":true}""")
        assertTrue(n.urgent)
        assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(ServerNotification.serializer(), """{"id":2}""").urgent)
    }

    @Test fun applyPermissionParsesAndCalculatesLeftAcrossRuns() {
        val ask = TaskGrantAsk.parse(Json.parseToJsonElement("""{"trigger":"매일 9시","action":"체험단 신청","apply":{"site":"example.com","what":"선물 체험단","max_count":3,"total":10}}"""))
        assertNotNull(ask)
        val apply = ask!!.apply
        assertNotNull(apply)
        assertEquals(3, apply!!.maxCount)
        assertEquals(10, apply.total)

        assertEquals(7, ScheduleGrant(id = 1, kind = "apply", maxCount = 3, totalCount = 10, used = 3).left)
        assertNull(ScheduleGrant(id = 2, kind = "apply", maxCount = 3, totalCount = 0, used = 5).left)
        assertEquals(3, ScheduleGrant(id = 3, kind = "send", maxCount = 5, used = 2).left)
    }
}
