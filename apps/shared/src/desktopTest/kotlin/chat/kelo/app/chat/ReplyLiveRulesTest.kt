package chat.kelo.app.chat

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyLiveRulesTest {
    private fun tool(label: String, hidden: Boolean = false) = TurnEvent.ToolStarted("t", "browse", JsonObject(emptyMap()), label = label, hidden = hidden)
    private fun page(image: String?, host: String = "example.com") = TurnEvent.Browser("b", "T", "https://$host/", host, "ok", image)

    @Test fun aLabelGivesTheStep() {
        val s = ReplyLiveRules.next(ReplyLive(), tool("Opening a site"), 0)
        assertEquals("Opening a site", s?.step)
    }

    @Test fun aHiddenCallAndTheSameLabelGiveNull() {
        assertNull(ReplyLiveRules.next(ReplyLive(), tool("Opening a site", hidden = true), 0))
        assertNull(ReplyLiveRules.next(ReplyLive(step = "Opening a site"), tool("Opening a site"), 0))
        assertNull(ReplyLiveRules.next(ReplyLive(), tool("  "), 0))
    }

    @Test fun aPictureIsRateLimited() {
        val first = ReplyLiveRules.next(ReplyLive(), page("data:a"), 1_000)
        assertEquals("data:a", first?.image)
        assertNull(ReplyLiveRules.next(first!!, page("data:b"), 2_000))
        val third = ReplyLiveRules.next(first, page("data:c"), 3_500)
        assertNotNull(third)
        assertEquals("data:c", third?.image)
    }

    @Test fun aBrowserEventWithoutAPictureSetsTheSiteOnce() {
        val s = ReplyLiveRules.next(ReplyLive(), page(null), 0)
        assertEquals("example.com", s?.site)
        assertNull(ReplyLiveRules.next(s!!, page(null), 0))
    }

    @Test fun trayLineIsStepThenSiteThenNull() {
        assertEquals("Opening a site", ReplyLiveRules.trayLine(ReplyLive(step = "Opening a site", site = "example.com")))
        assertEquals("example.com", ReplyLiveRules.trayLine(ReplyLive(site = "example.com")))
        assertNull(ReplyLiveRules.trayLine(ReplyLive()))
    }
}
