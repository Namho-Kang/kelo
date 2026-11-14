package chat.kelo.app.connectors

import chat.kelo.app.res.*
import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectorRulesTest {
    private val list = listOf(
        Connector("github", "GitHub", "코드 저장소와 이슈", "개발", "connected", connect = "device"),
        Connector("notion", "Notion", "문서와 데이터베이스", "문서", "available", connect = "mcp"),
        Connector("gmail", "Gmail", "메일 찾기와 보내기", "메일", "available", connect = "mail"),
        Connector("linear", "Linear", "이슈 관리", "개발", "coming_soon"),
        Connector("naver", "네이버", "브라우저로 쓰는 사이트", "웹", "browser", connect = "browser"),
    )

    @Test fun `an empty query shows everything`() {
        assertEquals(list, ConnectorRules.filter(list, ""))
    }

    @Test fun `query matches name or description, case ignored`() {
        assertEquals(listOf("github"), ConnectorRules.filter(list, "git").map { it.id })
        assertEquals(listOf("github"), ConnectorRules.filter(list, "GITHUB").map { it.id })
        assertEquals(listOf("github", "linear"), ConnectorRules.filter(list, "이슈").map { it.id })
        assertEquals(listOf("notion"), ConnectorRules.filter(list, "데이터").map { it.id })
    }


    @Test fun `query is not trimmed`() {
        // "${name} ${description}" holds one space between them, so a space alone still matches everything
        assertEquals(list.size, ConnectorRules.filter(list, " ").size)
        assertTrue(ConnectorRules.filter(list, "  ").isEmpty())
    }

    @Test fun `groups keep the order categories first appear in`() {
        val g = ConnectorRules.groups(list)
        assertEquals(listOf("개발", "문서", "메일", "웹"), g.map { it.first })
        assertEquals(listOf("github", "linear"), g.first().second.map { it.id })
    }

    @Test fun `status maps to the badge labels`() {
        assertEquals(Badge.CONNECTED, ConnectorRules.badge("connected"))
        assertEquals(Res.string.connector_status_connected, ConnectorRules.badge("connected")!!.label)
        assertEquals(Res.string.connector_status_available, ConnectorRules.badge("available")!!.label)
        assertEquals(Res.string.connector_status_browser, ConnectorRules.badge("browser")!!.label)
        assertEquals(Res.string.connector_status_coming_soon, ConnectorRules.badge("coming_soon")!!.label)
        assertEquals(Res.string.connector_status_disabled, ConnectorRules.badge("disabled")!!.label)
        assertEquals(Res.string.connector_status_unsupported, ConnectorRules.badge("unsupported")!!.label)
        assertNull(ConnectorRules.badge("something_new"))
    }

    @Test fun `every status the server sends has a badge`() {
        listOf("connected", "disabled", "coming_soon", "available", "browser", "unsupported").forEach {
            assertEquals(it, ConnectorRules.badge(it)!!.status)
        }
    }

    @Test fun `find follows the list and gives null when gone`() {
        assertEquals("gmail", ConnectorRules.find(list, "gmail")?.id)
        assertNull(ConnectorRules.find(list, "zoom"))
        assertNull(ConnectorRules.find(list, null))
    }

    @Test fun `countdown text`() {
        assertEquals("0:00", ConnectorRules.mmss(0))
        assertEquals("0:09", ConnectorRules.mmss(9))
        assertEquals("14:59", ConnectorRules.mmss(899))
        assertEquals("0:00", ConnectorRules.mmss(-5))
        assertEquals(10L, ConnectorRules.secondsLeft(20_000, 10_000))
        assertEquals(0L, ConnectorRules.secondsLeft(10_000, 20_000))
    }

    @Test fun `ended flows`() {
        assertNull(ConnectorRules.ended(null))
        assertNull(ConnectorRules.ended(OAuthFlow("1", status = "pending")))
        assertNull(ConnectorRules.ended(OAuthFlow("1", status = "connected")))
        assertEquals("denied", ConnectorRules.ended(OAuthFlow("1", status = "denied"))?.status)
        assertEquals("expired", ConnectorRules.ended(OAuthFlow("1", status = "expired"))?.status)
    }

    @Test fun `host, key account, key readiness`() {
        assertEquals("www.naver.com", ConnectorRules.host("https://www.naver.com/login"))
        assertEquals("", ConnectorRules.host("not a url"))
        assertEquals("bot@proj.iam.gserviceaccount.com", ConnectorRules.keyAccount("Google · bot@proj.iam.gserviceaccount.com"))
        assertEquals("", ConnectorRules.keyAccount("Zoom"))
        val form = KeyForm("zoom", fields = listOf(KeyField("a"), KeyField("b", secret = true)))
        assertFalse(ConnectorRules.keyReady(form, mapOf("a" to "x", "b" to " ")))
        assertTrue(ConnectorRules.keyReady(form, mapOf("a" to "x", "b" to "y")))
    }

    @Test fun `gmail connector lists only gmail`() {
        val accounts = listOf(MailAccount("m1", "gmail"), MailAccount("m2", "naver"))
        val providers = listOf(MailProvider("gmail"), MailProvider("naver"), MailProvider("daum"))
        assertEquals(listOf("m1"), ConnectorRules.mailAccounts("gmail", accounts).map { it.name })
        assertEquals(2, ConnectorRules.mailAccounts("mail", accounts).size)
        assertEquals(listOf("gmail"), ConnectorRules.mailChoices("gmail", providers).map { it.id })
        assertEquals(3, ConnectorRules.mailChoices("mail", providers).size)
    }

    @Test fun `server answers decode with defaults`() {
        val l = KeloApi.json.decodeFromString<List<Connector>>(
            """[{"id":"x","name":"X","description":"d","category":"c","status":"available","connect":null,"via":"","item":"","site":"","extra":1}]""",
        )
        assertNull(l[0].connect)
        assertTrue(l[0].steps.isEmpty())
        val f = KeloApi.json.decodeFromString<OAuthFlow>(
            """{"id":"f","provider":"github","status":"pending","message":"","account":"","user_code":"AB-CD","verification_uri":"https://github.com/login/device","expires_in":900}""",
        )
        assertEquals("AB-CD", f.userCode)
        assertEquals(900, f.expiresIn)
        assertTrue(f.pending)
    }

    @Test fun `only the permission a connection was given is shown`() {
        val scopes = listOf(OAuthScope("all", "Mail, calendar and contacts", "Read and send"), OAuthScope("read", "Read only"))
        val outlook = OAuthProvider("microsoft", connected = true, scopes = scopes, grantedScope = "read")
        assertEquals("Read only", ConnectorRules.grantedScope(outlook)?.label)
        assertEquals("all", ConnectorRules.grantedScope(outlook.copy(grantedScope = "all"))?.id)
        assertNull(ConnectorRules.grantedScope(outlook.copy(grantedScope = "")))
        assertNull(ConnectorRules.grantedScope(outlook.copy(grantedScope = "gone")))
        assertNull(ConnectorRules.grantedScope(outlook.copy(connected = false)))
        assertNull(ConnectorRules.grantedScope(null))
    }
}
