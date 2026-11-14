package chat.kelo.app.chat

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeySavedTest {
    private var n = 0
    private val key = { "k${++n}" }
    private fun run(vararg evs: TurnEvent, start: List<ChatItem> = emptyList()) =
        evs.fold(start) { items, ev -> ChatReducer.apply(items, ev, key) }

    private fun started(id: String, name: String) =
        TurnEvent.ToolStarted(id, name, JsonObject(emptyMap()))

    private fun result(id: String, name: String, output: String = "", isError: Boolean = false) =
        TurnEvent.ToolResult(id, name, output, isError, null)

    @Test fun `the server line gives the name and hosts`() {
        val s = KeySavedRules.parse("Saved the key for 'openweather' in the keychain (hosts: api.openweathermap.org, openweathermap.org). You never see it.")
        assertEquals("openweather", s?.name)
        assertEquals(listOf("api.openweathermap.org", "openweathermap.org"), s?.hosts)
    }

    @Test fun `anything else is not a card`() {
        assertNull(KeySavedRules.parse("error: no hidden secret #2 in this conversation turn"))
    }

    @Test fun `save_found_secret adds a KeySaved card`() {
        val output = "Saved the key for 'acme' in the keychain (hosts: api.acme.com, other.acme.com). You never see it."
        val items = run(started("s1", "save_found_secret"), result("s1", "save_found_secret", output = output))
        val card = items.filterIsInstance<ChatItem.KeySaved>().single()
        assertEquals("acme", card.name)
        assertEquals(listOf("api.acme.com", "other.acme.com"), card.hosts)
    }
}
