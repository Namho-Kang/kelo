package chat.kelo.app.connectors

import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleReviewRulesTest {
    @Test fun `a google row waiting for review shows the pill until it is connected`() {
        val waiting = Connector("google_calendar", "Google Calendar", status = "available", connect = "google", connectState = "pending_review")
        assertTrue(ConnectorRules.pendingReview(waiting))
        assertFalse(ConnectorRules.pendingReview(waiting.copy(status = "connected")))   // the owner's connected calendar stays as it is
        assertFalse(ConnectorRules.pendingReview(waiting.copy(connectState = "")))
    }

    @Test fun `the server's row is read with its note and an empty support link`() {
        val row = """{"id":"gmail","name":"Gmail","status":"available","connect":"mail","connect_state":"pending_review",
            "note":"Google 검토가 끝나야 열 수 있어요.","featured":true,"rank":0}"""
        val c = KeloApi.json.decodeFromString(Connector.serializer(), row)
        assertTrue(ConnectorRules.pendingReview(c))
        assertEquals("Google 검토가 끝나야 열 수 있어요.", c.note)
        assertEquals("", c.supportUrl)
        val app = KeloApi.json.decodeFromString(OAuthApp.serializer(), """{"client_id":"x","open_to_all":true}""")
        assertTrue(app.openToAll)
    }
}
