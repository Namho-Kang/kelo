package chat.kelo.app.agent

import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherModelsTest {
    @Test
    fun `a watcher with buy ready decodes its state problems and sign in`() {
        val json = """{
            "id": 42,
            "name": "Special Item",
            "buy": {
                "armed": true,
                "tab": "buy:42",
                "last_kept_at": 1700000000.5,
                "signed_in": true,
                "problems": [
                    {"code": "out_of_stock", "text": "Item is temporarily out of stock"},
                    {"code": "address_missing", "text": "No shipping address selected"}
                ],
                "remembered": true
            }
        }"""
        val w = KeloApi.json.decodeFromString(Watcher.serializer(), json)
        assertEquals(42L, w.id)
        assertNotNull(w.buy)
        val buy = w.buy!!
        assertTrue(buy.armed)
        assertEquals(1700000000.5, buy.lastKeptAt)
        assertEquals(true, buy.signedIn)
        assertTrue(buy.remembered)
        assertEquals(2, buy.problems.size)
        assertEquals("out_of_stock", buy.problems[0].code)
        assertEquals("Item is temporarily out of stock", buy.problems[0].text)
        assertEquals("address_missing", buy.problems[1].code)
        assertEquals("No shipping address selected", buy.problems[1].text)
    }

    @Test
    fun `a watcher without buy gives null`() {
        val json = """{"id": 43, "name": "Regular Watcher"}"""
        val w = KeloApi.json.decodeFromString(Watcher.serializer(), json)
        assertEquals(43L, w.id)
        assertNull(w.buy)

        val jsonNullBuy = """{"id": 44, "name": "Null Buy", "buy": null}"""
        val wNull = KeloApi.json.decodeFromString(Watcher.serializer(), jsonNullBuy)
        assertNull(wNull.buy)
    }
}
