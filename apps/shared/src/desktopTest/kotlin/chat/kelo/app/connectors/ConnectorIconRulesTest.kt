package chat.kelo.app.connectors

import chat.kelo.app.core.KeloApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorIconRulesTest {
    private val big = "/api/connectors/icons/notion.png?v=0123456789abcdef"
    private val small = "/api/connectors/icons/notion-128.png?v=fedcba9876543210"

    @Test fun smallTilesUseTheSmallIconAndBigOnesTheBigOne() {
        assertEquals(small, ConnectorIconRules.pick(big, small, 40f))
        assertEquals(big, ConnectorIconRules.pick(big, small, 64f))
        assertEquals(big, ConnectorIconRules.pick(big, "", 40f))
        assertEquals(small, ConnectorIconRules.pick("", small, 64f))
        assertEquals("", ConnectorIconRules.pick("", "", 40f))
    }

    @Test fun onlyTheServersOwnIconPathsAreFetched() {
        assertTrue(ConnectorIconRules.valid(big) && ConnectorIconRules.valid(small))
        assertTrue(ConnectorIconRules.valid("/api/connectors/icons/google_calendar.png"))
        listOf(
            "https://evil.example/api/connectors/icons/notion.png", "//evil.example/x.png", "/api/connectors/icons/../me.png",
            "/api/uploads/x.png", "/api/connectors/icons/Notion.png", "/api/connectors/icons/notion.svg", "",
        ).forEach { assertFalse(it, ConnectorIconRules.valid(it)) }
    }

    @Test fun keptFilesAreNamedByIconAndVersionAndOldVersionsGo() {
        assertEquals("notion-0123456789abcdef.png", ConnectorIconRules.fileName(big))
        assertEquals("notion-128-fedcba9876543210.png", ConnectorIconRules.fileName(small))
        assertEquals("notion.png", ConnectorIconRules.fileName("/api/connectors/icons/notion.png"))
        assertTrue(ConnectorIconRules.stale(big, "notion-00000000aaaaaaaa.png"))
        assertFalse(ConnectorIconRules.stale(big, "notion-0123456789abcdef.png"))
        assertFalse(ConnectorIconRules.stale(big, "notion-128-fedcba9876543210.png"))   // the small one is another file
        assertFalse(ConnectorIconRules.stale(big, "notionx-00000000aaaaaaaa.png"))
        assertTrue(ConnectorIconRules.stale(small, "notion-128-00000000aaaaaaaa.png"))
    }

    @Test fun colourIsReadFromHex() {
        assertEquals(0xFF0A66C2L, ConnectorIconRules.color("#0A66C2"))
        assertEquals(0xFFFFFFFFL, ConnectorIconRules.color("ffffff"))
        assertNull(ConnectorIconRules.color(""))
        assertNull(ConnectorIconRules.color("#12345"))
        assertNull(ConnectorIconRules.color("#GGGGGG"))
    }

    @Test fun rowsWithoutIconFieldsStillParse() {
        val rows: List<Connector> = KeloApi.json.decodeFromString("""[{"id":"x","name":"X"},
            {"id":"notion","name":"Notion","icon":"$big","icon_small":"$small","icon_color":"#000000"}]""")
        assertEquals("", rows[0].icon)
        assertEquals(small, rows[1].iconSmall)
        assertEquals("#000000", rows[1].iconColor)
    }
}
