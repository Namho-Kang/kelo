package chat.kelo.app.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceptLanguageTest {
    @Test fun `the app language comes first with the phone's region`() {
        assertEquals("ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7", AcceptLanguage.build("ko", listOf("en-US", "ko-KR")))
    }

    @Test fun `a phone without the app language still asks for it first`() {
        assertEquals("ko,en-US;q=0.9,en;q=0.8", AcceptLanguage.build("ko", listOf("en-US")))
    }

    @Test fun `no app pick uses the phone's languages in order`() {
        assertEquals("ja-JP,ja;q=0.9,ko-KR;q=0.8,ko;q=0.7", AcceptLanguage.build(null, listOf("ja-JP", "ko-KR")))
    }

    @Test fun `tags are cleaned, repeats and unknowns dropped, the list is capped`() {
        assertEquals("ko-KR,ko;q=0.9", AcceptLanguage.build("ko", listOf("ko_kr", "und", "", "KO-KR")))
        val long = AcceptLanguage.build(null, listOf("de-DE", "fr-FR", "es-ES", "it-IT"))
        assertEquals(6, long.split(",").size)
        assertEquals("zh-TW,zh;q=0.9", AcceptLanguage.build(null, listOf("zh-Hant-TW")))
        assertEquals("es-419,es;q=0.9", AcceptLanguage.build(null, listOf("es-419")))
        assertEquals("zh-TW,zh;q=0.9", AcceptLanguage.build("zh-Hant", emptyList()))
        assertEquals("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7", AcceptLanguage.build("zh-Hans", listOf("en-US")))
        assertEquals("pt-BR,pt;q=0.9", AcceptLanguage.build("pt-BR", listOf("pt-BR")))
        assertEquals("", AcceptLanguage.build(null, emptyList()))
    }

    @Test fun `every call of the shared client carries the header`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val before = AcceptLanguage.current
        try {
            AcceptLanguage.current = "ko-KR,ko;q=0.9"
            server.enqueue(MockResponse().setBody("{}"))
            KeloApi(server.url("/").toString(), "tok").get<JsonElement>("/api/connectors")
            assertEquals("ko-KR,ko;q=0.9", server.takeRequest().getHeader("Accept-Language"))
            AcceptLanguage.current = ""
            server.enqueue(MockResponse().setBody("{}"))
            KeloApi(server.url("/").toString(), "tok").get<JsonElement>("/api/connectors")
            assertNull(server.takeRequest().getHeader("Accept-Language"))
        } finally {
            AcceptLanguage.current = before
            server.shutdown()
        }
    }
}
