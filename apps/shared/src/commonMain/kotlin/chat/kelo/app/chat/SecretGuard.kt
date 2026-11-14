package chat.kelo.app.chat

import chat.kelo.app.chat.vault.VaultCalls
import chat.kelo.app.chat.vault.VaultMode
import chat.kelo.app.connectors.ConnectorCalls
import chat.kelo.app.connectors.KeyForm
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Where a detected secret sits in the sent text: UTF-16 code-unit offsets (Kotlin's own String indices), [start] inclusive. */
data class SecretSpan(val start: Int, val end: Int, val kind: String) {
    companion object {
        const val PASSWORD = "password"
        const val CARD = "card"
    }
}

/** The form that can keep it: "provider" (an AI service's key), "connector", "wallet" (a card) or "vault" (the Keychain). */
data class SecretTarget(val type: String, val id: String) {
    companion object {
        const val PROVIDER = "provider"
        const val CONNECTOR = "connector"
        const val WALLET = "wallet"
        const val VAULT = "vault"
    }
}

/**
 * The server's 409 `secret_in_message` answer: the message was neither sent to the model nor stored. Never carries the value.
 * [label]: the server's guess of what it belongs to ("Anthropic", "GitHub", a connector's name), "" when it has none.
 */
data class SecretFound(val spans: List<SecretSpan>, val placeholder: String, val target: SecretTarget, val label: String = "")

/** What the owner keeps it as, on the save step of the sheet. */
enum class SecretUse { AI_KEY, CONNECTOR_KEY, CARD, LOGIN, SERVICE_KEY }

/**
 * The chat send endpoints refuse a message that holds a key, token, password or card number (server: secret_guard.py) with
 * 409 `{"detail": {"code": "secret_in_message", "spans": [...], "placeholder": "...", "target": {...}, "label": "..."}}`. The app then
 * asks what to do: keep it in the Keychain (the value already filled in, saved straight from the app to the vault), send it as is (the
 * same text again with `"secrets": "allow"`), or go back and edit it.
 */
object SecretGuard {
    const val CODE = "secret_in_message"
    /** The request field that sends a refused message anyway, after the owner said so */
    const val ALLOW = "allow"

    /** The 409 answer of a chat send, or null for any other failure. */
    fun of(e: Throwable): SecretFound? = (e as? ApiError)?.takeIf { it.status == 409 }?.detailJson?.let(::parse)

    fun parse(detail: JsonObject?): SecretFound? {
        if (detail == null || str(detail["code"]) != CODE) return null
        val spans = (detail["spans"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = (o["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val end = (o["end"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (start < 0 || end <= start) null else SecretSpan(start, end, str(o["kind"]).ifEmpty { "token" })
        }.sortedBy { it.start }
        val t = detail["target"] as? JsonObject
        val type = str(t?.get("type")).takeIf { it in TYPES } ?: SecretTarget.VAULT
        return SecretFound(spans, str(detail["placeholder"]), SecretTarget(type, str(t?.get("id"))), str(detail["label"]).trim())
    }

    /** The detected values, cut from [text] (the exact text that was sent) by the spans; spans outside it are skipped. */
    fun values(text: String, spans: List<SecretSpan>): List<String> =
        spans.filter { it.start >= 0 && it.end <= text.length && it.start < it.end }
            .map { text.substring(it.start, it.end).trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * The spans moved from the sent text onto the composer's text: the sent text is the composer's text trimmed (plus attachment
     * lines after it), so each span shifts by the leading blanks, and spans past the typed part are dropped.
     */
    fun composerSpans(composer: String, spans: List<SecretSpan>): List<SecretSpan> {
        val lead = composer.length - composer.trimStart().length
        val typed = composer.trim().length
        return spans.filter { it.end <= typed }.map { it.copy(start = it.start + lead, end = it.end + lead) }
    }

    /** [text] with every span whose value is [value] (all spans when null) replaced by [with], last span first so offsets hold. */
    fun replace(text: String, spans: List<SecretSpan>, with: String, value: String? = null): String {
        var out = text
        for (s in spans.sortedByDescending { it.start }) {
            if (s.start < 0 || s.end > out.length || s.start >= s.end) continue
            if (value != null && out.substring(s.start, s.end).trim() != value) continue
            out = out.substring(0, s.start) + with + out.substring(s.end)
        }
        return out
    }

    /** The ways to keep it, the likeliest first (from the server's target and the kind of the first span). */
    fun uses(found: SecretFound): List<SecretUse> {
        val kind = found.spans.firstOrNull()?.kind
        val first = when {
            found.target.type == SecretTarget.PROVIDER && found.target.id.isNotEmpty() -> SecretUse.AI_KEY
            found.target.type == SecretTarget.CONNECTOR && found.target.id.isNotEmpty() -> SecretUse.CONNECTOR_KEY
            found.target.type == SecretTarget.WALLET || kind == SecretSpan.CARD -> SecretUse.CARD
            kind == SecretSpan.PASSWORD -> SecretUse.LOGIN
            else -> SecretUse.SERVICE_KEY
        }
        val rest = when (first) {
            SecretUse.CARD -> emptyList()   // a card number is only ever a card
            SecretUse.LOGIN -> listOf(SecretUse.SERVICE_KEY)
            else -> listOf(SecretUse.SERVICE_KEY, SecretUse.LOGIN)
        }
        return (listOf(first) + rest).distinct()
    }

    /** The API address a few well-known services' keys are used on, for the service key form ("" when unknown). */
    fun hostGuess(label: String): String = HOSTS[label.trim().lowercase()].orEmpty()

    /** A vault name for a service key from its address ("api.github.com" -> "api-github-com"). */
    fun nameFor(host: String): String {
        val n = host.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
        return if (n.length >= 3) n else "key-$n".trimEnd('-').padEnd(3, 'x')
    }

    private val HOSTS = mapOf(
        "github" to "api.github.com", "gitlab" to "gitlab.com", "stripe" to "api.stripe.com", "slack" to "slack.com",
        "notion" to "api.notion.com", "openai" to "api.openai.com", "anthropic" to "api.anthropic.com", "huggingface" to "huggingface.co",
        "hugging face" to "huggingface.co", "sendgrid" to "api.sendgrid.com", "npm" to "registry.npmjs.org",
    )
    private val TYPES = setOf(SecretTarget.PROVIDER, SecretTarget.CONNECTOR, SecretTarget.WALLET, SecretTarget.VAULT)
    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

/** The vault calls behind the save step (the value goes from the app straight to these endpoints, never through chat). */
object SecretSave {
    /** PUT /api/providers/{name}/key */
    suspend fun aiKey(api: KeloApi, provider: String, value: String) {
        api.put<JsonElement>("/api/providers/${KeloApi.segment(provider)}/key", buildJsonObject { put("api_key", value.trim()) })
    }

    /** GET /api/keys/{id}: the connector's key form (its fields; the server checks the key once when saved). */
    suspend fun connectorForm(api: KeloApi, connector: String): KeyForm = ConnectorCalls.keyForm(api, connector)

    /** POST /api/keys/{id} with the form's values; [value] goes into the first secret field. */
    suspend fun connectorKey(api: KeloApi, form: KeyForm, value: String, others: Map<String, String>) {
        val secret = form.fields.firstOrNull { it.secret }?.id ?: form.fields.firstOrNull()?.id ?: "key"
        ConnectorCalls.saveKey(api, form.id, others + (secret to value.trim()))
    }

    /** POST /api/vault/logins, then PUT /api/vault/{name}/login (kept now). Returns the login's name. */
    suspend fun login(api: KeloApi, site: String, label: String, username: String, password: String): String {
        val name = ConnectorCalls.addLogin(api, site.trim(), label.trim())
        VaultCalls.putLogin(api, name, username.trim(), password, VaultMode.NOW)
        return name
    }

    /** POST /api/credentials (a bearer key for [host]), then PUT /api/credentials/{name}/secret. Returns its name. */
    suspend fun serviceKey(api: KeloApi, host: String, label: String, value: String): String {
        val h = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val name = SecretGuard.nameFor(h)
        api.post<JsonElement>("/api/credentials", buildJsonObject {
            put("name", name); put("label", label.trim().ifEmpty { h })
            putJsonArray("hosts") { add(JsonPrimitive(h)) }
            putJsonObject("placement") { put("type", "bearer_header") }
        })
        VaultCalls.putSecret(api, name, value.trim())
        return name
    }
}

/**
 * The value from a refused message, handed to the wallet's card form when the owner keeps a card number (the number is already
 * filled in there). Memory only: never saved, never logged, never in saved UI state; dropped when the form closes ([clear]) and after
 * [ttlMs] at the latest. [saved] tells the chat the card was kept, so it can put a reference in the composer.
 */
class SecretHandoff(private val clock: () -> Long = System::currentTimeMillis, private val ttlMs: Long = TTL_MS) {
    private var values: List<String> = emptyList()
    private var at = 0L
    private val _saved = MutableStateFlow(false)
    /** A form just kept the value (the chat then puts its reference in the composer); taken with [takeSaved] */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    @Synchronized fun offer(found: List<String>) {
        values = found.filter { it.isNotEmpty() }
        at = clock()
    }

    /** The first value, while it is still fresh. */
    @Synchronized fun peek(): String? {
        if (values.isEmpty()) return null
        if (clock() - at > ttlMs) { clear(); return null }
        return values.first()
    }

    /** The form kept the value: it is dropped here, and the chat is told. */
    @Synchronized fun kept() {
        values = emptyList()
        _saved.value = true
    }

    @Synchronized fun takeSaved(): Boolean = _saved.value.also { _saved.value = false }

    @Synchronized fun clear() {
        values = emptyList()
    }

    override fun toString(): String = "SecretHandoff(${values.size})"   // never the value

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
