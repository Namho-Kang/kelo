package chat.kelo.app.connectors

import chat.kelo.app.agent.ServerNotification
import chat.kelo.app.core.KeloApi
import chat.kelo.app.my.BetaRequestRow
import chat.kelo.app.my.BetaRequests
import chat.kelo.app.my.OperatorBetaRules
import chat.kelo.app.notify.NoteTypes
import chat.kelo.app.notify.NotifyRules
import chat.kelo.app.notify.OpenTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaRulesTest {
    @Test fun `only gmail and google calendar ask for beta access`() {
        assertEquals("gmail", BetaRules.product("gmail"))
        assertEquals("calendar", BetaRules.product("google_calendar"))
        assertNull(BetaRules.product("imap_email"))
        assertNull(BetaRules.product("github"))
    }

    @Test fun `what the screen shows for each state`() {
        assertEquals(BetaRules.Shown.NOTHING, BetaRules.shown(null))
        assertEquals(BetaRules.Shown.REQUEST, BetaRules.shown(GoogleBetaState(canRequest = true)))
        assertEquals(BetaRules.Shown.NOTHING, BetaRules.shown(GoogleBetaState(canRequest = false)))   // no developer address: no button
        assertEquals(BetaRules.Shown.REQUESTED, BetaRules.shown(GoogleBetaState("requested", "a@b.com", canRequest = true)))
        assertEquals(BetaRules.Shown.DECLINED, BetaRules.shown(GoogleBetaState("declined", canRequest = true)))
        assertEquals(BetaRules.Shown.OPENED, BetaRules.shown(GoogleBetaState("approved", open = true)))
        assertEquals(BetaRules.Shown.OPENED, BetaRules.shown(GoogleBetaState("requested", open = true)))   // opened to all meanwhile
    }

    @Test fun `the email is checked like the server checks it`() {
        assertEquals("a.b+c@gmail.com", BetaRules.email("  a.b+c@gmail.com "))
        assertEquals("Kim@Example.co.kr", BetaRules.email("Kim@Example.co.kr"))
        for (bad in listOf("", "a@b", "a b@c.com", "@c.com", "a@-c.com", "a@c.", "x".repeat(65) + "@c.com", "a@@c.com")) {
            assertNull(bad, BetaRules.email(bad))
        }
    }

    @Test fun `the server's answers are read`() {
        val s = KeloApi.json.decodeFromString(GoogleBetaState.serializer(),
            """{"status":"requested","email":"a@b.com","products":["gmail"],"at":1.0,"open":false,"can_request":true}""")
        assertEquals("requested", s.status)
        assertTrue(s.canRequest)
        val list = KeloApi.json.decodeFromString(BetaRequests.serializer(),
            """{"requests":[{"id":3,"created_at":1.5,"email":"a@b.com","products":["gmail","calendar"],"language":"ko","app_version":"1.3",
               "status":"requested","handled_at":null,"notice":"no_mailbox"}],"open":1,"approved_installs":0,"keep_days":30,"mail_from":"",
               "support":"support@kelo.chat","receiving":true,"open_to_all":false}""")
        val r = list.requests.single()
        assertEquals("Gmail, Google Calendar", OperatorBetaRules.products(r.products))
        assertEquals("no_mailbox", r.notice)
        assertTrue(OperatorBetaRules.canApprove(r) && OperatorBetaRules.canDecline(r))
        assertFalse(OperatorBetaRules.canApprove(r.copy(status = "approved")))
        assertTrue(OperatorBetaRules.canDecline(r.copy(status = "approved")))    // taking an approval back
        assertFalse(OperatorBetaRules.canDecline(BetaRequestRow(status = "declined")))
    }

    @Test fun `a beta request notification opens the operator page`() {
        val n = ServerNotification(id = 9, type = NoteTypes.BETA_REQUEST, title = "Google sign-in beta request", body = "a@b.com · Gmail")
        assertEquals(OpenTarget.Operator, NotifyRules.target(n))
        assertEquals(OpenTarget.Operator, OpenTarget.decode(OpenTarget.Operator.encode()))
        assertEquals(listOf(n), NotifyRules.fresh(listOf(n), 0))
    }
}
