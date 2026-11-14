package chat.kelo.app.connectors

import chat.kelo.app.res.*
import chat.kelo.app.my.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorListTest {
    private val list = listOf(
        Connector("github", "GitHub", "저장소와 이슈", "서비스", "connected", connect = "device", item = "github-api"),
        Connector("notion", "Notion", "문서와 데이터베이스", "서비스", "available", connect = "mcp", item = "notion-mcp"),
        Connector("stripe", "Stripe", "결제 내역", "서비스", "connected", connect = "mcp", item = "stripe-mcp"),
        Connector("agoda", "Agoda", "숙소 예약", "서비스", "browser", connect = "browser"),
        Connector("spotify", "Spotify", "음악", "서비스", "browser", connect = "browser"),
        Connector("brand_new", "Brand New", "새 서비스", "서비스", "available", connect = "mcp", item = "brand-new"),
    )
    private val creds = listOf(
        VaultEntry("github-api", label = "GitHub · @someone", kind = "api", hasSecret = true),
        VaultEntry("stripe-mcp", label = "Stripe", kind = "api", hasSecret = true, lastResult = "failed", lastResultDetail = "401 invalid token"),
        VaultEntry("brand-new", label = "Brand New", kind = "api", hasSecret = false),
        VaultEntry("login-agoda", label = "Agoda", kind = "login", hasSecret = false),
    )

    @Test fun `known ids fall into local groups, the rest into other`() {
        assertEquals(ConnectorGroup.DEV, ConnectorGroups.of("github"))
        assertEquals(ConnectorGroup.WORK, ConnectorGroups.of("notion"))
        assertEquals(ConnectorGroup.MONEY, ConnectorGroups.of("stripe"))
        assertEquals(ConnectorGroup.SHOPPING, ConnectorGroups.of("agoda"))
        assertEquals(ConnectorGroup.MEDIA, ConnectorGroups.of("spotify"))
        assertEquals(ConnectorGroup.OTHER, ConnectorGroups.of("brand_new"))
        assertEquals(Res.string.connector_group_other, ConnectorGroup.OTHER.label)
    }

    @Test fun `reconnect comes from the vault item`() {
        assertNull(ConnectorList.reconnect(list[0], creds))                        // fine
        assertEquals(Reconnect.REFUSED, ConnectorList.reconnect(list[2], creds))   // refused last time
        assertEquals(Reconnect.MISSING, ConnectorList.reconnect(list[5], creds))   // item without a value
        assertNull(ConnectorList.reconnect(list[1], creds))                        // never connected
        assertNull(ConnectorList.reconnect(list[3], creds))                        // no item at all
        assertEquals(Reconnect.SERVER, ConnectorList.reconnect(list[1].copy(status = "needs_reconnect"), emptyList()))
    }

    @Test fun `a site login waiting to be filled is not a broken connection`() {
        val x = Connector("box", "Box", "", "", "browser", connect = "browser", item = "login-agoda")
        assertNull(ConnectorList.reconnect(x, creds))
    }

    @Test fun `those needing a new connection come first, then the connected ones, then local groups in order`() {
        val s = ConnectorList.sections(list, creds)
        assertEquals(listOf("reconnect", "connected", "grp:WORK", "grp:MEDIA", "grp:SHOPPING"), s.map { it.key })
        assertEquals(listOf("stripe", "brand_new"), s.first().items.map { it.id })
        assertEquals(Res.string.connector_group_reconnect, s.first().label)
        assertEquals(listOf("github"), s[1].items.map { it.id })
        assertEquals(Res.string.connectors_pinned, s[1].label)
    }

    @Test fun `a filter keeps one group, pinned ones included`() {
        val dev = ConnectorList.sections(list, creds, filter = "grp:DEV")
        assertEquals(listOf("connected"), dev.map { it.key })
        assertEquals(listOf("github"), dev.single().items.map { it.id })
        val work = ConnectorList.sections(list, creds, filter = "grp:WORK")
        assertEquals(listOf("grp:WORK"), work.map { it.key })
    }

    @Test fun `filter chips follow the groups present`() {
        assertEquals(listOf("grp:WORK", "grp:DEV", "grp:MONEY", "grp:MEDIA", "grp:SHOPPING", "grp:OTHER"), ConnectorList.filters(list).map { it.key })
    }

    @Test fun `server categories win when there is more than one`() {
        val l = listOf(Connector("a", "A", category = "개발"), Connector("b", "B", category = "문서"), Connector("c", "C", category = "개발"))
        val s = ConnectorList.sections(l)
        assertEquals(listOf("cat:개발", "cat:문서"), s.map { it.key })
        assertEquals("개발", s[0].title)
        assertNull(s[0].label)
        assertEquals(listOf("a", "c"), s[0].items.map { it.id })
    }

    @Test fun `search covers name, description and id, trimmed`() {
        assertEquals(listOf("github"), ConnectorList.search(list, " git ").map { it.id })
        assertEquals(listOf("brand_new"), ConnectorList.search(list, "brand_").map { it.id })
        assertEquals(listOf("agoda"), ConnectorList.search(list, "숙소").map { it.id })
        assertEquals(list.size, ConnectorList.search(list, "").size)
    }

    @Test fun `pinned means connected or needing a new connection`() {
        assertEquals(listOf("github", "stripe", "brand_new"), list.filter { ConnectorList.pinned(it, creds) }.map { it.id })
    }

    @Test fun `account comes from the label after the dot`() {
        assertEquals("@someone", ConnectorList.account(list[0], creds))
        assertEquals("", ConnectorList.account(list[2], creds))
        assertEquals("", ConnectorList.account(list[3], creds))
    }

    @Test fun `connection methods`() {
        assertEquals(ConnectMethod.CODE, ConnectorList.method("device"))
        assertEquals(ConnectMethod.SIGN_IN, ConnectorList.method("mcp"))
        assertEquals(ConnectMethod.MAIL, ConnectorList.method("mail"))
        assertEquals(ConnectMethod.KEY, ConnectorList.method("key"))
        assertEquals(ConnectMethod.BROWSER, ConnectorList.method("browser"))
        assertEquals(null, ConnectorList.method("autofill"))
        assertNull(ConnectorList.method(null))
    }

    @Test fun `tile letters`() {
        assertEquals("G", ConnectorList.initials("GitHub"))
        assertEquals("ZC", ConnectorList.initials("Zoho CRM"))
        assertEquals("1", ConnectorList.initials("1Up"))
        assertEquals("C", ConnectorList.initials("Cal.com"))
        assertEquals("A", ConnectorList.initials("Atlassian"))
        assertEquals("T", ConnectorList.initials("tl;dv"))
        assertEquals("?", ConnectorList.initials(""))
    }

    @Test fun `tile hue is stable and on the wheel`() {
        val ids = listOf("github", "notion", "stripe", "zoom", "x", "")
        ids.forEach { id ->
            val h = ConnectorList.hue(id)
            assertTrue(h in 0f..359f)
            assertEquals(h, ConnectorList.hue(id))
        }
        assertTrue(ids.map(ConnectorList::hue).toSet().size > 3)
    }

    @Test fun `the opening view is connected first then popular by rank`() {
        val l = list.mapIndexed { i, c ->
            when (c.id) {
                "agoda" -> c.copy(featured = true, rank = 3)
                "spotify" -> c.copy(featured = true, rank = 1)
                "github" -> c.copy(featured = true, rank = 0)     // connected: pinned, not repeated under popular
                else -> c
            }
        }
        val s = ConnectorList.home(l, creds)!!
        assertEquals(listOf("reconnect", "connected", "popular"), s.map { it.key })
        assertEquals(listOf("spotify", "agoda"), s.last().items.map { it.id })
        assertEquals(Res.string.connectors_popular, s.last().label)
        assertEquals(1, ConnectorList.hiddenCount(l, creds))                  // notion is neither connected nor popular
        assertNull(ConnectorList.home(list, creds))                           // an older server marks nothing: the full list
    }

    /** The server's catalog (catalog.py, 151 services) as of this version. */
    private val catalog = "adobe_workfront agoda ahrefs airtable amplitude apify apollo apple_healthkit asana atlassian attio basecamp beehiiv betterstack bitly box brex buildkite calendly canva clickhouse clickup close cloudflare_observability cloudflare_workers cloudinary coinmarketcap contentful customer_io datadog deepl descript digitalocean dropbox egnyte evernote exa expedia expo facebook_cli fathom figma firecrawl fireflies framer freshbooks github gitlab gmail google_calendar google_contacts google_docs google_drive google_forms google_health_connect google_sheets google_slides google_tasks grab_mcp grafana_cloud greenhouse hugging_face ibkr imap_email incident_io instacart instagram jotform kit krisp launchdarkly linear lucid mailerlite make mapbox mercury messenger_read meta_catalog_search metricool mintlify miro mixpanel monday motherduck neon netlify notion opentable otter outlook_calendar outlook_contacts outlook_mail pandadoc paypal philips_hue places planetscale posthog postman prisma pulumi quickbooks railway ramp read_ai resend restream sanity sentry shop-pay shopback_mcp shopify shopify-ucp slack spotify stripe stripe-link supabase superhuman_docs superhuman_mail tavily threads ticketmaster ticktick tiktok_ads tldv todoist trello typefully v0 vercel webflow webull wix wordpress_com workable workos zapier zoho_books zoho_crm zoho_desk zoho_projects zoho_show zoom naver kakao coupang baemin toss daangn".split(' ')

    @Test fun everyCatalogServiceHasALocalGroup() {
        assertEquals(151, catalog.size)
        val missing = catalog.filterNot { ConnectorGroups.known(it) }
        assertTrue("not grouped: $missing", missing.isEmpty())
        assertEquals(ConnectorGroup.SHOPPING, ConnectorGroups.of("coupang"))
        assertEquals(ConnectorGroup.MONEY, ConnectorGroups.of("toss"))
        assertEquals(ConnectorGroup.WORK, ConnectorGroups.of("naver"))
    }

    @Test fun removedServicesAreNoLongerMapped() {
        listOf("haveibeenpwned", "kahoot", "padlet", "oreilly", "splice", "tomorrow_io", "turkish_airlines", "coindesk", "morningstar",
            "craft", "whimsical", "cal_com", "roam_research", "sonos", "tally").forEach { assertTrue(it, !ConnectorGroups.known(it)) }
    }
}
