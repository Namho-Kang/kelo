package chat.kelo.app.my

import chat.kelo.app.res.*
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorRulesTest {
    private val hex = "0123456789abcdef0123456789abcdef"

    @Test fun `an empty form is fine and sends nothing`() {
        assertNull(OperatorRules.problem(CloudflareForm()))
        assertTrue(OperatorRules.cloudflareBody(CloudflareForm()).isEmpty())
    }

    @Test fun `each field is checked`() {
        assertEquals(Res.string.operator_cf_bad_token, OperatorRules.problem(CloudflareForm(token = "short")))
        assertEquals(Res.string.operator_cf_bad_token, OperatorRules.problem(CloudflareForm(token = "has spaces in it, twenty+ chars")))
        assertEquals(Res.string.operator_cf_bad_account, OperatorRules.problem(CloudflareForm(accountId = "123")))
        assertEquals(Res.string.operator_cf_bad_zone_id, OperatorRules.problem(CloudflareForm(zoneId = hex + "0")))
        assertEquals(Res.string.operator_cf_bad_zone, OperatorRules.problem(CloudflareForm(zoneName = "not a domain")))
        assertEquals(Res.string.operator_cf_bad_max, OperatorRules.problem(CloudflareForm(maxNames = "0")))
        assertEquals(Res.string.operator_cf_bad_max, OperatorRules.problem(CloudflareForm(maxNames = "100001")))
        assertNull(OperatorRules.problem(CloudflareForm("A".repeat(40), hex.uppercase(), hex, "Kelo.Chat", "190")))
    }

    @Test fun `the body holds only what is filled in, ids and domain in lower case`() {
        val b = OperatorRules.cloudflareBody(CloudflareForm("tok_" + "x".repeat(30), hex.uppercase(), "", " kelo.chat ", "190"))
        assertEquals("tok_" + "x".repeat(30), b["api_token"]!!.jsonPrimitive.content)
        assertEquals(hex, b["account_id"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("zone_id"))
        assertEquals("kelo.chat", b["zone_name"]!!.jsonPrimitive.content)
        assertEquals(190, b["max_names"]!!.jsonPrimitive.int)
    }

    @Test fun `an empty token field keeps the stored token, turning off removes it`() {
        assertFalse(OperatorRules.cloudflareBody(CloudflareForm(zoneName = "kelo.chat")).containsKey("api_token"))
        assertEquals("", OperatorRules.cloudflareOff()["api_token"]!!.jsonPrimitive.content)
    }

    @Test fun `the form is filled from the server, never with a token`() {
        val f = OperatorRules.formOf(CloudflareConfig(accountId = hex, zoneId = hex, zoneName = "kelo.chat", hasToken = true, maxNames = 900))
        assertEquals("", f.token)
        assertEquals("900", f.maxNames)
        assertEquals("", OperatorRules.formOf(CloudflareConfig()).maxNames)
    }

    @Test fun `a key file must be a small JSON object`() {
        assertTrue(OperatorRules.keyFileOk("""{"type":"service_account"}""".toByteArray()))
        assertFalse(OperatorRules.keyFileOk(ByteArray(0)))
        assertFalse(OperatorRules.keyFileOk("[1]".toByteArray()))
        assertFalse(OperatorRules.keyFileOk("not json".toByteArray()))
        assertFalse(OperatorRules.keyFileOk(("{\"a\":\"" + "x".repeat(OperatorRules.MAX_KEY_FILE) + "\"}").toByteArray()))
    }

    @Test fun `who is signed in, from an operator GET`() {
        assertEquals(true, OperatorRules.ownerFrom(null))
        assertEquals(false, OperatorRules.ownerFrom(403))
        assertNull(OperatorRules.ownerFrom(500))
    }

    @Test fun `the operator section shows only for an owner on the operator's server`() {
        assertEquals(true, OperatorRules.operatorFrom(null, true))
        assertEquals(false, OperatorRules.operatorFrom(null, false))      // a self-hosted copy: its owner's phone does not see it
        assertEquals(false, OperatorRules.operatorFrom(403, null))        // an ordinary phone
        assertNull(OperatorRules.operatorFrom(500, null))                 // not known: the last value stays
        assertEquals(true, OperatorRules.operatorFrom(404, null) { OperatorRules.ownerFrom(null) })   // an older server: the older rule
        assertEquals(false, OperatorRules.operatorFrom(404, null) { OperatorRules.ownerFrom(403) })
        assertNull(OperatorRules.operatorFrom(404, null))
        val st = chat.kelo.app.core.KeloApi.json.decodeFromString<OperatorStatus>("""{"owner":true,"operator":false}""")
        assertEquals(false, st.operator)
        assertEquals(true, st.owner)
    }

    @Test fun `server answers decode`() {
        val c = chat.kelo.app.core.KeloApi.json.decodeFromString<CloudflareConfig>(
            """{"account_id":"a","zone_id":"z","zone_name":"kelo.chat","has_token":true,"max_names":900,"used":12,"enabled":true,"idle_days":30}""",
        )
        assertEquals(12, c.used)
        assertTrue(c.enabled)
        val f = chat.kelo.app.core.KeloApi.json.decodeFromString<FirebaseConfig>("""{"enabled":true,"project_id":"p","client_email":"e@p.iam"}""")
        assertEquals("p", f.projectId)
    }
}
