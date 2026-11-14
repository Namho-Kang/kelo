package chat.kelo.app.connectors

import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Test

/** A CalDAV calendar added with its own address: its own way to connect, shown as connected with its account (Android only: the desktop app has its own connectors screen). */
class CalDavConnectTest {
    @Test fun `a caldav calendar is its own way to connect and shows as connected with its account`() {
        assertEquals(ConnectMethod.CALDAV, ConnectorList.method("caldav"))
        val on = KeloApi.json.decodeFromString<Connector>(
            """{"id":"caldav_calendar","name":"CalDAV Calendar","status":"connected","connect":"caldav","method":"password","account":"me@fastmail.com","item":"caldav-x"}""",
        )
        assertEquals(ConnectMethod.CALDAV, ConnectorList.methodOf(on))
        assertEquals("me@fastmail.com", ConnectorList.account(on, emptyList()))
        assertEquals(ConnectMethod.MAIL, ConnectorList.methodOf(Connector("imap_email", connect = "mail", method = "password")))
        val accounts = KeloApi.json.decodeFromString<CalDavAccounts>(
            """{"accounts":[{"name":"caldav-x","address":"me@fastmail.com","url":"https://dav.fastmail.com/"}]}""",
        )
        assertEquals("me@fastmail.com", accounts.accounts.single().address)
    }

}
