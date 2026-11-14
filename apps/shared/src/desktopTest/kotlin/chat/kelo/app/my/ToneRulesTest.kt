package chat.kelo.app.my

import chat.kelo.app.chat.AgentStatus
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Profile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 마이 → 에이전트 → 말투: the page's chosen tone follows the server, whether the agent changed it in the chat or the page did. */
class ToneRulesTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun api() = KeloApi(server.url("/").toString(), "tok")

    @Test fun anOlderServerOrAnUnknownValueReadsAsPolite() {
        assertEquals(ToneRules.POLITE, ToneRules.selected(KeloApi.json.decodeFromString(Profile.serializer(), """{"agent_name":"Kelo"}""")))
        assertEquals(ToneRules.POLITE, ToneRules.selected(Profile(tone = "")))
        assertEquals(ToneRules.POLITE, ToneRules.selected(Profile(tone = "loud")))
        assertEquals(ToneRules.FRIENDLY, ToneRules.selected(Profile(tone = "friendly")))
    }

    @Test fun theStatusPollOnlyChangesTheProfileWhenTheToneDiffers() {
        val polite = Profile(agentName = "Kelo", tone = "polite")
        assertNull(ToneRules.synced(polite, null))                 // an older server says nothing
        assertNull(ToneRules.synced(polite, "polite"))
        assertNull(ToneRules.synced(polite, "shouty"))
        assertEquals(polite.copy(tone = "friendly"), ToneRules.synced(polite, "friendly"))
    }

    @Test fun theChatChangesItThePageShowsItAndTappingPoliteSwitchesBack() = runBlocking {
        var shown = Profile(agentName = "Kelo", tone = "polite")
        // "반말로 해 줘" in the chat: set_tone saved friendly; the next status poll carries it
        val status = KeloApi.json.decodeFromString(AgentStatus.serializer(), """{"busy":false,"agent_name":"Kelo","tone":"friendly"}""")
        ToneRules.synced(shown, status.tone)?.let { shown = it }
        assertEquals(ToneRules.FRIENDLY, ToneRules.selected(shown))
        // the page opens: GET /api/profile says the same
        server.enqueue(MockResponse().setBody("""{"agent_name":"Kelo","tone":"friendly","avatar_version":0}"""))
        shown = ToneRules.reload(api())
        assertEquals("/api/profile", server.takeRequest().path)
        assertEquals(ToneRules.FRIENDLY, ToneRules.selected(shown))
        // 공손 tapped: PUT {"tone":"polite"}, the saved profile shows
        server.enqueue(MockResponse().setBody("""{"agent_name":"Kelo","tone":"polite","avatar_version":0}"""))
        shown = ToneRules.save(api(), ToneRules.POLITE)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("""{"tone":"polite"}""", put.body.readUtf8())
        assertEquals(ToneRules.POLITE, ToneRules.selected(shown))
        assertNull(ToneRules.synced(shown, "polite"))
    }
}
