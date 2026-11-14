package chat.kelo.app.connectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val answer = """{"connector":"github","name":"GitHub","extra":1,"kinds":[
        {"kind":"read","mode":"allow","default":"allow","actions":[]},
        {"kind":"send","mode":"ask","default":"ask","actions":[{"id":"create_issue","label":"Create an issue","mode":"ask","default":"ask"}]}]}"""

    private fun parsed() = json.decodeFromString<ConnectorPermissions>(answer)

    @Test fun `a GET answer decodes with two kinds and one with actions`() {
        val p = parsed()
        assertEquals("GitHub", p.name)
        assertEquals(listOf("read", "send"), p.kinds.map { it.kind })
        assertTrue(p.kinds[0].actions.isEmpty())
        assertEquals("create_issue", p.kinds[1].actions.single().id)
    }

    @Test fun `changed is false at the defaults and true when one action differs`() {
        val p = parsed()
        assertFalse(PermissionRules.changed(p))
        val a = p.kinds[1].actions[0]
        assertTrue(PermissionRules.changed(p.copy(kinds = listOf(p.kinds[0], p.kinds[1].copy(actions = listOf(a.copy(mode = "off")))))))
        assertTrue(PermissionRules.changed(p.copy(kinds = listOf(p.kinds[0].copy(mode = "off"), p.kinds[1]))))
    }

    @Test fun `an action set back to its default sends null, another mode sends the mode`() {
        val a = parsed().kinds[1].actions[0]
        val back = PermissionRules.actionBody(a, a.default)["actions"]!!.jsonObject
        assertEquals(JsonNull, back["create_issue"])
        val off = PermissionRules.actionBody(a, "off")["actions"]!!.jsonObject
        assertEquals("off", (off["create_issue"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `a kind body is the kind and its mode`() {
        assertEquals(json.parseToJsonElement("""{"send":"off"}""") as JsonObject, PermissionRules.kindBody("send", "off"))
    }
}
