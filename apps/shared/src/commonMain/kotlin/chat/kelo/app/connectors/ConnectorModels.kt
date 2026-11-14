package chat.kelo.app.connectors

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GET /api/connectors items.
 *
 * [status] is "connected", "disabled", "coming_soon", "available", "browser" or "unsupported".
 * [connect] says how it is connected here: "device", "mcp", "mail", "key", "browser", "google" (Google sign-in), or null
 * when it cannot be. [fallback]: another way to connect the same service (Google Calendar: the service account).
 */
@Serializable
data class Connector(
    val id: String,
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val status: String = "",
    val connect: String? = null,
    val item: String = "",
    val via: String = "",
    val site: String = "",
    val steps: List<String> = emptyList(),
    /** one of the services most people use, shown when the tab opens */
    val featured: Boolean = false,
    /** its place among those (0 = the most used); regional services rank after the worldwide ones */
    val rank: Int? = null,
    val fallback: ConnectorFallback? = null,
    /** how it is connected now, when the service has two ways: "google" (Google sign-in), "password" (app password), "key" */
    val method: String = "",
    /** who is connected (e.g. the signed-in Google address), when the server knows */
    val account: String = "",
    /** "pending_review": the Google sign-in is not open yet (Gmail, Google Calendar); [note] says so in the server's words */
    @SerialName("connect_state") val connectState: String = "",
    val note: String = "",
    /** a link to show under [note]; empty until the operator sets one */
    @SerialName("support_url") val supportUrl: String = "",
    /** the service's own app icon on the server (256 px, then 128 px for small tiles), empty when there is none: the initials tile shows */
    val icon: String = "",
    @SerialName("icon_small") val iconSmall: String = "",
    /** the icon's main colour ("#RRGGBB"), the tile's colour while the icon loads */
    @SerialName("icon_color") val iconColor: String = "",
    /** something about what Kelo may do with it differs from the defaults (GET /api/connectors/{id}/permissions) */
    val limited: Boolean = false,
)

/** The other way to connect a service whose main way is a sign-in (Google Calendar: a Google service account key). */
@Serializable
data class ConnectorFallback(
    val connect: String = "",
    val via: String = "",
    val item: String = "",
    val blurb: String = "",
    val connected: Boolean = false,
    /** the link that opens this other way, in the server's words (an MCP sign-in that may refuse this app: "Connect with a key instead") */
    val title: String = "",
)

/** GET /api/google/calendar: whether the Google sign-in can be used on this server (and why not, in words), and the connected accounts. */
@Serializable
data class GoogleCalendarState(
    val available: Boolean = false,
    val reason: String = "",
    val accounts: List<GoogleCalendarAccount> = emptyList(),
    /** new Google sign-ins wait for Google's review; accounts already connected keep working */
    @SerialName("pending_review") val pendingReview: Boolean = false,
)

@Serializable
data class GoogleCalendarAccount(
    val name: String,
    val address: String = "",
    @SerialName("can_write") val canWrite: Boolean = false,
    /** Google stopped renewing the sign-in: connect again */
    val problem: Boolean = false,
)

/**
 * GET/POST /api/google/beta: this server's request to try Google sign-in while it is closed for everyone. [status] is "none",
 * "requested", "approved" or "declined"; [open] is true once new Google sign-ins can start here (approved, or open to all).
 */
@Serializable
data class GoogleBetaState(
    val status: String = "none",
    val email: String = "",
    val products: List<String> = emptyList(),
    val open: Boolean = false,
    @SerialName("can_request") val canRequest: Boolean = false,
)

/** What the beta part of a Gmail / Google Calendar screen shows, and the checks of its form. */
object BetaRules {
    enum class Shown { REQUEST, REQUESTED, DECLINED, OPENED, NOTHING }

    /** The product a connector asks for ("gmail" / "calendar"), or null when it is not a Google sign-in connector. */
    fun product(connectorId: String): String? = when (connectorId) {
        "gmail" -> "gmail"
        "google_calendar" -> "calendar"
        else -> null
    }

    fun shown(s: GoogleBetaState?): Shown = when {
        s == null -> Shown.NOTHING
        s.open -> Shown.OPENED   // approved (or opened to all): the screen reloads and shows the Google sign-in
        s.status == "requested" -> Shown.REQUESTED
        s.status == "declined" -> Shown.DECLINED
        s.status == "approved" -> Shown.OPENED
        s.canRequest -> Shown.REQUEST
        else -> Shown.NOTHING
    }

    private val EMAIL = Regex("^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.[A-Za-z]{2,24}$")

    /** The address as it is sent (trimmed), or null when it is not a plain email address (the same check as the server's). */
    fun email(typed: String): String? = typed.trim().takeIf { it.length <= 254 && EMAIL.matches(it) }
}

/** GET /api/keys/{via}: a key or token made on the service. */
@Serializable
data class KeyForm(
    val id: String,
    val name: String = "",
    val item: String = "",
    val help: List<String> = emptyList(),
    val checked: Boolean = false,
    val connected: Boolean = false,
    val label: String = "",
    val fields: List<KeyField> = emptyList(),
)

@Serializable
data class KeyField(val id: String, val label: String = "", val secret: Boolean = false, val placeholder: String = "")

@Serializable
data class MailProvider(
    val id: String, val name: String = "", val help: List<String> = emptyList(),
    /** the mail service's own icon on the server, empty when there is none (see [Connector.icon]) */
    val icon: String = "", @SerialName("icon_small") val iconSmall: String = "", @SerialName("icon_color") val iconColor: String = "",
)

@Serializable
data class MailAccount(val name: String, val provider: String = "", val label: String = "", val address: String = "")

/** GET /api/mail/providers */
@Serializable
data class MailProviders(
    val providers: List<MailProvider> = emptyList(),
    val accounts: List<MailAccount> = emptyList(),
    @SerialName("google_web") val googleWeb: Boolean = false,
)

/** GET /api/oauth/providers items: a service connected by a code typed on its own page (OAuth device flow). */
@Serializable
data class OAuthProvider(
    val id: String,
    val item: String = "",
    val name: String = "",
    @SerialName("has_client_id") val hasClientId: Boolean = false,
    val connected: Boolean = false,
    val account: String = "",
    @SerialName("settings_url") val settingsUrl: String = "",
    val web: Boolean = false,
    val help: List<String> = emptyList(),
    val scopes: List<OAuthScope> = emptyList(),
    /** The permission choice the stored connection was made with ("" = not connected or not known). */
    @SerialName("granted_scope") val grantedScope: String = "",
)

@Serializable
data class OAuthScope(val id: String, val label: String = "", val detail: String = "")

/** A device-flow attempt. [status]: "pending", "connected", "denied", "expired", "error" or "cancelled". */
@Serializable
data class OAuthFlow(
    val id: String,
    val provider: String = "",
    val status: String = "",
    val message: String = "",
    val account: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
) {
    val pending: Boolean get() = status == "pending"
}

/** GET /api/oauth-apps/{provider}: the operator's OAuth app. The secret is never sent back, only whether one is stored. */
@Serializable
data class OAuthApp(
    @SerialName("client_id") val clientId: String = "",
    @SerialName("has_secret") val hasSecret: Boolean = false,
    @SerialName("is_broker") val isBroker: Boolean = false,
    val callback: String = "",
    /** Google only: new sign-ins are open to everyone (after Google's verification) */
    @SerialName("open_to_all") val openToAll: Boolean = false,
)

@Serializable
data class UrlAnswer(val url: String)

@Serializable
data class NameAnswer(val name: String)

/** The connector endpoints of server.py. [KeloApi.baseUrl] is sent as the origin to come back to. */
object ConnectorCalls {
    suspend fun list(api: KeloApi): List<Connector> = api.get("/api/connectors")

    suspend fun permissions(api: KeloApi, id: String): ConnectorPermissions = api.get("/api/connectors/${KeloApi.segment(id)}/permissions")

    suspend fun putPermissions(api: KeloApi, id: String, body: JsonObject): ConnectorPermissions =
        api.put("/api/connectors/${KeloApi.segment(id)}/permissions", body)

    suspend fun resetPermissions(api: KeloApi, id: String): ConnectorPermissions =
        api.delete("/api/connectors/${KeloApi.segment(id)}/permissions")

    suspend fun disconnect(api: KeloApi, item: String) {
        api.delete<JsonElement>("/api/credentials/${KeloApi.segment(item)}")
    }

    // ---- device flow (GitHub, Microsoft)
    suspend fun oauthProviders(api: KeloApi): List<OAuthProvider> = api.get("/api/oauth/providers")

    suspend fun saveClientId(api: KeloApi, id: String, clientId: String): OAuthProvider =
        api.put("/api/oauth/${KeloApi.segment(id)}/client", buildJsonObject { put("client_id", clientId) })

    suspend fun startFlow(api: KeloApi, id: String, scope: String): OAuthFlow =
        api.post("/api/oauth/${KeloApi.segment(id)}/start", buildJsonObject { put("scope", scope) })

    suspend fun flow(api: KeloApi, flowId: String): OAuthFlow = api.get("/api/oauth/flows/${KeloApi.segment(flowId)}")

    suspend fun cancelFlow(api: KeloApi, flowId: String) {
        api.delete<JsonElement>("/api/oauth/flows/${KeloApi.segment(flowId)}")
    }

    suspend fun githubWebStart(api: KeloApi, scope: String): String =
        api.post<UrlAnswer>("/api/github/web/start", buildJsonObject { put("origin", api.baseUrl); put("scope", scope) }).url

    // ---- the operator's OAuth app ("github" or "google")
    suspend fun oauthApp(api: KeloApi, provider: String): OAuthApp = api.get("/api/oauth-apps/${KeloApi.segment(provider)}")

    suspend fun saveOauthApp(api: KeloApi, provider: String, clientId: String, secret: String): OAuthApp =
        api.put("/api/oauth-apps/${KeloApi.segment(provider)}", buildJsonObject {
            put("client_id", clientId)
            if (secret.isNotEmpty()) put("client_secret", secret)
        })

    /** The operator's switch: open new Google sign-ins to everyone (PUT /api/oauth-apps/google/open). */
    suspend fun setGoogleOpen(api: KeloApi, open: Boolean): OAuthApp =
        api.put("/api/oauth-apps/google/open", buildJsonObject { put("open", open) })

    // ---- hosted MCP servers
    suspend fun mcpStart(api: KeloApi, id: String): String =
        api.post<UrlAnswer>("/api/mcp-oauth/${KeloApi.segment(id)}/start", buildJsonObject { put("origin", api.baseUrl) }).url

    // ---- mail
    suspend fun mailProviders(api: KeloApi): MailProviders = api.get("/api/mail/providers")

    suspend fun addMail(api: KeloApi, provider: String, address: String, password: String) {
        api.post<JsonElement>("/api/mail/accounts", buildJsonObject {
            put("provider", provider); put("address", address); put("password", password)
        })
    }

    suspend fun googleWebStart(api: KeloApi, product: String = "gmail"): String =
        api.post<UrlAnswer>("/api/google/web/start", buildJsonObject { put("origin", api.baseUrl); put("product", product) }).url

    // ---- Google Calendar with the Google sign-in
    suspend fun googleCalendar(api: KeloApi): GoogleCalendarState = api.get("/api/google/calendar")

    // ---- asking to try Google sign-in while it is closed (beta.py)
    suspend fun googleBeta(api: KeloApi): GoogleBetaState = api.get("/api/google/beta")

    suspend fun requestGoogleBeta(api: KeloApi, email: String, product: String, appVersion: String): GoogleBetaState =
        api.post("/api/google/beta", buildJsonObject {
            put("email", email)
            put("products", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(product)) })
            put("app_version", appVersion)
        })

    // ---- keys
    suspend fun keyForm(api: KeloApi, via: String): KeyForm = api.get("/api/keys/${KeloApi.segment(via)}")

    suspend fun saveKey(api: KeloApi, id: String, values: Map<String, String>) {
        api.post<JsonElement>("/api/keys/${KeloApi.segment(id)}", buildJsonObject {
            put("values", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
        })
    }

    // ---- browser sites: register a login to be entered on the secure form
    suspend fun addLogin(api: KeloApi, site: String, label: String): String =
        api.post<NameAnswer>("/api/vault/logins", buildJsonObject { put("site", site); put("label", label) }).name
}

/**
 * Which of the server's service icons a tile shows, and how it is kept (the Android and desktop tiles; plain Kotlin so both test it).
 * The server sends "icon" (256 px) and "icon_small" (128 px), each a URL with ?v=<content hash>: a new picture is a new URL, so a
 * file kept under its URL's name never goes stale.
 */
object ConnectorIconRules {
    /** Tiles up to this size (dp) use the 128 px icon: enough for 40 dp at 3x. */
    const val SMALL_MAX_DP = 48

    /** The URL for a tile of [sizeDp]; empty when the service has no icon (the initials tile shows). */
    fun pick(icon: String, small: String, sizeDp: Float): String =
        if (sizeDp <= SMALL_MAX_DP) small.ifBlank { icon } else icon.ifBlank { small }

    /** Only the server's own icon paths are fetched (never another host, never anything else on the server). */
    fun valid(url: String): Boolean = Regex("^/api/connectors/icons/[a-z0-9_]+(-128)?\\.png(\\?v=[0-9a-f]{1,64})?$").matches(url)

    /** The kept file's name for [url]: its path and version, safe as a file name. */
    fun fileName(url: String): String {
        val name = url.substringAfterLast('/').substringBefore('?').removeSuffix(".png")
        val v = url.substringAfter("?v=", "")
        return if (v.isEmpty()) "$name.png" else "$name-$v.png"
    }

    /** A kept file that is an older version of [url]'s icon (same icon and size, another version), to delete when the new one is kept. */
    fun stale(url: String, kept: String): Boolean {
        val name = url.substringAfterLast('/').substringBefore('?').removeSuffix(".png")
        return kept != fileName(url) && (kept == "$name.png" || Regex("^" + Regex.escape(name) + "-[0-9a-f]{8,}\\.png$").matches(kept))
    }

    /** "#RRGGBB" as 0xAARRGGBB (opaque); null when it is not that shape. */
    fun color(hex: String): Long? {
        val h = hex.removePrefix("#")
        if (h.length != 6) return null
        return h.toLongOrNull(16)?.let { 0xFF000000L or it }
    }
}

/** GET /api/connectors/{id}/permissions (server permissions.py): what Kelo may do with this connector. */
@Serializable
data class ConnectorPermissions(val connector: String = "", val name: String = "", val kinds: List<PermissionKind> = emptyList())

/** One kind of action (read / change / send / delete) with its mode now, its default and its single actions. */
@Serializable
data class PermissionKind(val kind: String, val mode: String, val default: String, val actions: List<PermissionAction> = emptyList())

@Serializable
data class PermissionAction(val id: String, val label: String = "", val mode: String, val default: String)

object PermissionRules {
    val MODES = listOf("allow", "ask", "off")

    /** Anything set away from its default (the "back to the defaults" link shows then). */
    fun changed(p: ConnectorPermissions): Boolean = p.kinds.any { k -> k.mode != k.default || k.actions.any { it.mode != it.default } }

    /** The PUT body for one kind's new mode. */
    fun kindBody(kind: String, mode: String): JsonObject = buildJsonObject { put(kind, mode) }

    /** The PUT body for one action: its default again clears the setting (null) so it follows the kind later. */
    fun actionBody(a: PermissionAction, mode: String): JsonObject =
        buildJsonObject { put("actions", buildJsonObject { if (mode == a.default) put(a.id, JsonNull) else put(a.id, mode) }) }
}
