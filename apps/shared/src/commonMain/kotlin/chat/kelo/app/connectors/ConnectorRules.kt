package chat.kelo.app.connectors

import chat.kelo.app.res.*
import org.jetbrains.compose.resources.StringResource
import java.net.URI

/** A connector's badge: its label and colour style by status. */
enum class Badge(val status: String, val label: StringResource) {
    CONNECTED("connected", Res.string.connector_status_connected),
    DISABLED("disabled", Res.string.connector_status_disabled),
    COMING_SOON("coming_soon", Res.string.connector_status_coming_soon),
    AVAILABLE("available", Res.string.connector_status_available),
    BROWSER("browser", Res.string.connector_status_browser),
    UNSUPPORTED("unsupported", Res.string.connector_status_unsupported),
}

/** The list's small rules and the helpers of the connect panels, kept apart from the UI so they can be tested. */
object ConnectorRules {
    /** `${name} ${description}` containing the query (case ignored). */
    fun filter(list: List<Connector>, query: String): List<Connector> {
        val q = query.lowercase()
        return list.filter { c -> "${c.name} ${c.description}".lowercase().contains(q) }
    }

    /** The shown connectors grouped by category, in the order each category first appears. */
    fun groups(shown: List<Connector>): List<Pair<String, List<Connector>>> =
        shown.groupBy { it.category }.toList()

    /** The badge for a status; null for a status this app does not know (nothing is shown). */
    fun badge(status: String): Badge? = Badge.entries.find { it.status == status }

    /** Gmail / Google Calendar while the Google sign-in waits for Google's review and nothing is connected yet: a "준비 중" pill, no connect button. */
    fun pendingReview(x: Connector): Boolean = x.connectState == "pending_review" && x.status != "connected"

    /** The connector with this id in a fresh list (the open detail follows reloads; gone means back to the list). */
    fun find(list: List<Connector>, id: String?): Connector? = id?.let { i -> list.find { it.id == i } }

    /** Seconds as m:ss. */
    fun mmss(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Whole seconds left until [deadlineMs], never below zero. */
    fun secondsLeft(deadlineMs: Long, nowMs: Long): Long = Math.round((deadlineMs - nowMs).coerceAtLeast(0) / 1000.0)

    /** A site's host name ("" when it is not a URL). */
    fun host(site: String): String = runCatching { URI(site).host.orEmpty() }.getOrDefault("")

    /** A device-flow attempt that ended without connecting (denied, expired, error, cancelled), for the error line. */
    fun ended(flow: OAuthFlow?): OAuthFlow? = flow?.takeIf { it.status != "pending" && it.status != "connected" }

    /** The key form's account: the part after " · " in the label (a Google service account's address). */
    /**
     * The one permission a connected code-connected service was given (its `granted_scope` among its `scopes`); null when it is not
     * connected or the choice is not known, and then the detail page shows no permission section (the connect panel has its picker).
     */
    fun grantedScope(p: OAuthProvider?): OAuthScope? =
        p?.takeIf { it.connected && it.grantedScope.isNotEmpty() }?.let { q -> q.scopes.firstOrNull { it.id == q.grantedScope } }

    fun keyAccount(label: String): String = label.split(" · ").getOrNull(1).orEmpty()

    /** Every field of a key form filled in. */
    fun keyReady(form: KeyForm, values: Map<String, String>): Boolean = form.fields.all { (values[it.id] ?: "").isNotBlank() }

    /** The mailboxes a mail connector lists: only Gmail ones on the Gmail connector. */
    fun mailAccounts(connectorId: String, accounts: List<MailAccount>): List<MailAccount> =
        if (connectorId == "gmail") accounts.filter { it.provider == "gmail" } else accounts

    /** The providers to choose from: only Gmail on the Gmail connector. */
    fun mailChoices(connectorId: String, providers: List<MailProvider>): List<MailProvider> =
        if (connectorId == "gmail") providers.filter { it.id == "gmail" } else providers
}

/**
 * The groups the list falls into when the server gives every connector the same category (it says only "서비스" today): a small
 * map of known ids kept here, and anything new or unknown under 기타.
 */
enum class ConnectorGroup(val label: StringResource) {
    WORK(Res.string.connector_group_work),
    DEV(Res.string.connector_group_dev),
    MONEY(Res.string.connector_group_money),
    MEDIA(Res.string.connector_group_media),
    SHOPPING(Res.string.connector_group_shopping),
    OTHER(Res.string.connector_group_other),
}

/** A part of the list: a title (a string resource, or the server's own category text) and its connectors. */
data class ConnectorSection(val key: String, val label: StringResource?, val title: String, val items: List<Connector>)

/** How a connector is connected, in the app's words (the detail page's 연결 방법). */
enum class ConnectMethod(val label: StringResource) {
    CODE(Res.string.connector_method_code),
    SIGN_IN(Res.string.connector_method_sign_in),
    GOOGLE(Res.string.connector_method_google),
    MAIL(Res.string.connector_method_mail),
    CALDAV(Res.string.connector_method_caldav),
    KEY(Res.string.connector_method_key),
    BROWSER(Res.string.connector_method_browser),
}

/** Why a connection needs doing again; null when it is fine. */
enum class Reconnect(val text: StringResource) {
    /** The stored token is gone (removed, or could not be opened any more) */
    MISSING(Res.string.connector_reconnect_missing),
    /** The service turned the stored token down the last time it was used */
    REFUSED(Res.string.connector_reconnect_refused),
    /** The server itself says so */
    SERVER(Res.string.connector_reconnect_server),
}

object ConnectorGroups {
    /** Every service of the server's catalog (catalog.py), so none falls under 기타 by accident; removed ones are left out. */
    private val MAP: Map<String, ConnectorGroup> = buildMap {
        fun add(g: ConnectorGroup, ids: String) = ids.split(' ').filter { it.isNotBlank() }.forEach { put(it, g) }
        add(ConnectorGroup.WORK, "adobe_workfront airtable apollo asana atlassian attio basecamp box calendly clickup close deepl " +
            "dropbox egnyte evernote fathom fireflies gmail google_calendar google_contacts google_docs google_drive google_forms google_sheets " +
            "google_slides google_tasks greenhouse imap_email jotform krisp linear lucid make miro monday naver notion otter " +
            "outlook_calendar outlook_contacts outlook_mail pandadoc read_ai slack superhuman_docs superhuman_mail ticktick tldv " +
            "todoist trello workable zapier zoho_crm zoho_desk zoho_projects zoho_show zoom")
        add(ConnectorGroup.DEV, "amplitude apify betterstack buildkite clickhouse cloudflare_observability cloudflare_workers cloudinary " +
            "contentful datadog digitalocean exa expo firecrawl github gitlab grafana_cloud hugging_face incident_io launchdarkly mapbox " +
            "mintlify mixpanel motherduck neon netlify planetscale posthog postman prisma pulumi railway resend sanity sentry supabase " +
            "tavily v0 vercel webflow wix wordpress_com workos")
        add(ConnectorGroup.MONEY, "brex coinmarketcap freshbooks ibkr mercury paypal quickbooks ramp shop-pay stripe stripe-link toss " +
            "webull zoho_books")
        add(ConnectorGroup.MEDIA, "ahrefs beehiiv bitly canva customer_io descript facebook_cli figma framer instagram kit mailerlite " +
            "messenger_read metricool restream spotify threads tiktok_ads typefully")
        add(ConnectorGroup.SHOPPING, "agoda baemin coupang daangn expedia grab_mcp instacart kakao meta_catalog_search opentable places " +
            "shopback_mcp shopify shopify-ucp ticketmaster")
        add(ConnectorGroup.OTHER, "apple_healthkit google_health_connect philips_hue")
    }

    /** Whether the map files this id itself (a test checks it against the catalog). */
    fun known(id: String): Boolean = id in MAP

    fun of(id: String): ConnectorGroup = MAP[id] ?: ConnectorGroup.OTHER
}

/** The list's tiles, search, order and the detail's facts, kept apart from the UI. */
object ConnectorList {
    /** Server statuses (or flags) that mean "connect again" (none is sent today; kept for when one is). */
    private val RECONNECT_STATUSES = setOf("needs_reconnect", "reconnect", "expired", "error")

    /**
     * Whether a connector needs connecting again: the server says so, or the vault item it uses ([Connector.item]) exists but has
     * no stored value any more, or the service refused it the last time.
     */
    fun reconnect(x: Connector, creds: List<chat.kelo.app.my.VaultEntry>): Reconnect? {
        if (x.status in RECONNECT_STATUSES) return Reconnect.SERVER
        if (x.item.isBlank()) return null
        val cred = creds.firstOrNull { it.name == x.item } ?: return null
        if (cred.isLogin) return null   // a site login waiting to be filled in is not a broken connection
        return when {
            cred.isBad -> Reconnect.REFUSED
            !cred.hasSecret -> Reconnect.MISSING
            else -> null
        }
    }

    /** Search across everything the list holds: name, description and id (so "google_" finds every Google one). */
    fun search(list: List<Connector>, query: String): List<Connector> {
        val q = query.trim().lowercase()
        return list.filter { c -> q.isEmpty() || "${c.name} ${c.description} ${c.id}".lowercase().contains(q) }
    }

    /** Connected (or needing to be connected again): these are pinned on top of the list. */
    fun pinned(x: Connector, creds: List<chat.kelo.app.my.VaultEntry>): Boolean = x.status == "connected" || reconnect(x, creds) != null

    /** The group a connector is filed under: the server's category when it sends more than one, else the local group. */
    fun groupKey(x: Connector, serverCategories: Boolean): String = if (serverCategories) "cat:" + x.category else "grp:" + ConnectorGroups.of(x.id).name

    private fun usesServerCategories(list: List<Connector>): Boolean = list.map { it.category }.filter { it.isNotBlank() }.distinct().size > 1

    /** The filter chips: one per group that has connectors, in the list's order (the first chip, 전체, is the screen's own). */
    fun filters(list: List<Connector>): List<ConnectorSection> = sections(list, emptyList(), pin = false).map { it.copy(items = emptyList()) }

    /**
     * The list in parts: those that need connecting again, then the connected ones (both pinned on top, whatever their group), then
     * the server's categories when it sends more than one, else the local groups in a fixed order. Inside a part the server's order
     * is kept. [filter] (a group key) keeps only that group, in the pinned parts too.
     */
    fun sections(shown: List<Connector>, creds: List<chat.kelo.app.my.VaultEntry> = emptyList(), filter: String? = null, pin: Boolean = true): List<ConnectorSection> {
        val server = usesServerCategories(shown)
        val inFilter = if (filter == null) shown else shown.filter { groupKey(it, server) == filter }
        val broken = if (pin) inFilter.filter { reconnect(it, creds) != null } else emptyList()
        val connected = if (pin) inFilter.filter { it.status == "connected" && reconnect(it, creds) == null } else emptyList()
        val rest = inFilter.filter { it !in broken && it !in connected }
        val out = mutableListOf<ConnectorSection>()
        if (broken.isNotEmpty()) out += ConnectorSection("reconnect", Res.string.connector_group_reconnect, "", broken)
        if (connected.isNotEmpty()) out += ConnectorSection("connected", Res.string.connectors_pinned, "", connected)
        if (server) {
            rest.groupBy { it.category }.forEach { (cat, items) -> out += ConnectorSection("cat:$cat", null, cat, items) }
        } else {
            val byGroup = rest.groupBy { ConnectorGroups.of(it.id) }
            ConnectorGroup.entries.forEach { g -> byGroup[g]?.let { out += ConnectorSection("grp:${g.name}", g.label, "", it) } }
        }
        return out
    }

    /**
     * What the tab shows when it opens: the user's own connections pinned on top (those to connect again first, featured or not),
     * then the popular services by rank. Everything else is found by searching or in the full list. An older server that marks
     * nothing as popular gets the full list instead (null).
     */
    fun home(list: List<Connector>, creds: List<chat.kelo.app.my.VaultEntry> = emptyList()): List<ConnectorSection>? {
        if (list.none { it.featured }) return null
        val pinned = sections(list.filter { pinned(it, creds) }, creds)
        val popular = list.filter { it.featured && !pinned(it, creds) }.sortedWith(compareBy<Connector> { it.rank ?: Int.MAX_VALUE }.thenBy { it.name })
        return pinned + listOfNotNull(popular.takeIf { it.isNotEmpty() }?.let { ConnectorSection("popular", Res.string.connectors_popular, "", it) })
    }

    /** How many services the full list holds beyond what the opening view shows. */
    fun hiddenCount(list: List<Connector>, creds: List<chat.kelo.app.my.VaultEntry> = emptyList()): Int =
        list.count { !it.featured && !pinned(it, creds) }

    fun method(connect: String?): ConnectMethod? = when (connect) {
        "device" -> ConnectMethod.CODE
        "mcp" -> ConnectMethod.SIGN_IN
        "google" -> ConnectMethod.GOOGLE
        "mail" -> ConnectMethod.MAIL
        "caldav" -> ConnectMethod.CALDAV
        "key" -> ConnectMethod.KEY
        "browser" -> ConnectMethod.BROWSER
        else -> null
    }

    /** Who is connected, when the vault item's label says (e.g. "GitHub · @someone" → "@someone"). */
    /** How this one IS connected: the server says so when a service has two ways (Gmail: Google sign-in or app password). */
    fun methodOf(x: Connector): ConnectMethod? = when (x.method) {
        "google" -> ConnectMethod.GOOGLE
        "password" -> if (x.connect == "caldav") ConnectMethod.CALDAV else ConnectMethod.MAIL
        "key" -> ConnectMethod.KEY
        else -> method(x.connect)
    }

    fun account(x: Connector, creds: List<chat.kelo.app.my.VaultEntry>): String {
        if (x.account.isNotBlank()) return x.account
        if (x.item.isBlank()) return ""
        val label = creds.firstOrNull { it.name == x.item }?.label.orEmpty()
        return label.split(" · ", limit = 2).getOrNull(1).orEmpty().trim()
    }

    /** One or two letters for a service's tile: "GitHub" → "G", "Zoho CRM" → "ZC", "1Up" → "1". */
    fun initials(name: String): String {
        val words = name.split(' ', '-', '_', '·', '(', ')').filter { w -> w.isNotBlank() && w.first().isLetterOrDigit() }
        if (words.isEmpty()) return name.trim().take(1).uppercase().ifEmpty { "?" }
        val first = words[0].first().uppercaseChar()
        val second = words.getOrNull(1)?.first()?.takeIf { it.isLetter() && words.size == 2 }?.uppercaseChar()
        return if (second != null) "$first$second" else first.toString()
    }

    /**
     * A hue for a service's initials tile (shown when it has no app icon), the same every time for the same id and spread evenly
     * over the colour wheel.
     */
    /** A tile's colour for a hue: dark enough in both themes for white letters (3:1, large bold text). */
    fun tileColor(hue: Float, dark: Boolean): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color.hsl(hue, 0.42f, if (dark) 0.34f else 0.38f)

    fun hue(id: String): Float {
        var h = 0x811C9DC5.toInt()   // FNV-1a
        id.forEach { ch -> h = (h xor ch.code) * 0x01000193 }
        return ((h ushr 1) % 360).toFloat()
    }
}
