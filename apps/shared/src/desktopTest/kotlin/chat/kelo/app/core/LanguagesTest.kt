package chat.kelo.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    @Test fun `twenty languages, each under its own name, no repeats`() {
        assertEquals(20, Languages.ALL.size)
        assertEquals(Languages.ALL.size, Languages.ALL.map { it.code }.toSet().size)
        assertEquals("English", Languages.name("en"))
        assertEquals("العربية", Languages.name("ar"))
    }

    @Test fun `device tags map to the app's languages`() {
        assertEquals("en", Languages.match("en-GB"))
        assertEquals("ko", Languages.match("ko-KR"))
        assertEquals("zh-Hant", Languages.match("zh-Hant-TW"))
        assertEquals("zh-Hant", Languages.match("zh-HK"))
        assertEquals("zh-Hans", Languages.match("zh-CN"))
        assertEquals("zh-Hans", Languages.match("zh"))
        assertEquals("pt-BR", Languages.match("pt-PT"))
        assertEquals("id", Languages.match("in-ID"))
        assertEquals("id", Languages.match("id"))
        assertNull(Languages.match("sv-SE"))
    }

    @Test fun `an unsupported phone language suggests English`() {
        assertEquals("en", Languages.suggested(listOf("sv-SE", "fi-FI")))
        assertEquals("de", Languages.suggested(listOf("sv-SE", "de-AT")))
        assertEquals("en", Languages.suggested(emptyList()))
    }

    @Test fun `following the system is a known choice with no override`() {
        assertTrue(Languages.isKnown(Languages.SYSTEM))
        assertNull(Languages.override(Languages.SYSTEM))
        assertNull(Languages.override(null))
        assertEquals("ja", Languages.override("ja"))
        assertFalse(Languages.isKnown("xx"))
    }

    @Test fun `a pick tells the server the language the app shows`() {
        assertEquals("ja", Languages.forServer("ja", listOf("ko-KR")))
        assertEquals("zh-Hant", Languages.forServer("zh-Hant", emptyList()))
        assertEquals("ko", Languages.forServer(Languages.SYSTEM, listOf("sv-SE", "ko-KR")))
        assertEquals("pt-BR", Languages.forServer(Languages.SYSTEM, listOf("pt-PT")))
        assertEquals("en", Languages.forServer(Languages.SYSTEM, listOf("sv-SE")))
        assertEquals("en", Languages.forServer("xx", emptyList()))
    }
}
