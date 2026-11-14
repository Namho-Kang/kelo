package chat.kelo.app.chat.vault

import chat.kelo.app.core.KeloApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GET /api/vault items. kind is "api", "login" or "email". Values never come back from the server. */
@Serializable
data class VaultItem(
    val name: String,
    val label: String = "",
    val kind: String = "",
    val hosts: List<String> = emptyList(),
    val endpoint: String = "",
    @SerialName("has_secret") val hasSecret: Boolean = false,
    @SerialName("auto_allow") val autoAllow: Boolean = false,
    @SerialName("last_used") val lastUsed: Double? = null,
    @SerialName("username_hint") val usernameHint: String? = null,
    @SerialName("last_result") val lastResult: String? = null,
    @SerialName("last_result_at") val lastResultAt: Double? = null,
    @SerialName("last_result_detail") val lastResultDetail: String = "",
) {
    val isLogin: Boolean get() = kind == "login"
}

/** GET /api/vault: [items] hold a value; [pending] are requests without one yet. */
@Serializable
data class VaultInfo(
    val locked: Boolean = false,
    val items: List<VaultItem> = emptyList(),
    val pending: List<VaultItem> = emptyList(),
    val verifying: List<String> = emptyList(),
)

/** How a typed login is kept (LoginIn.mode on the server). */
enum class VaultMode(val wire: String) { AFTER_SUCCESS("after_success"), NOW("now"), ONCE("once") }

/**
 * What a save (or a cancel) answers: [turnId] is a reply the server started to carry on (follow it like this phone's own), or null.
 * [reply] (newer servers): "live" (the reply that asked is still running and goes on by itself), "started" (a new reply, [turnId]),
 * "queued" (one will start later) or "none" (no reply follows); null from an older server.
 */
@Serializable
data class SaveAnswer(
    val ok: Boolean = true,
    val checked: Boolean = false,
    @SerialName("turn_id") val turnId: String? = null,
    val reply: String? = null,
) {
    companion object {
        const val REPLY_NONE = "none"
    }
}

/** The vault calls the secure form and card make. */
object VaultCalls {
    suspend fun info(api: KeloApi): VaultInfo = api.get("/api/vault")

    /** An item by name, whether it holds a value or is still a request. */
    suspend fun find(api: KeloApi, name: String): VaultItem? = info(api).let { v -> (v.items + v.pending).find { it.name == name } }

    /** PUT /api/vault/{name}/login */
    suspend fun putLogin(api: KeloApi, name: String, username: String, password: String, mode: VaultMode): SaveAnswer =
        api.put("/api/vault/${KeloApi.segment(name)}/login", buildJsonObject {
            put("username", username); put("password", password); put("mode", mode.wire)
        })

    /** PUT /api/credentials/{name}/secret (422 when the service refused the key on its test request: nothing was saved) */
    suspend fun putSecret(api: KeloApi, name: String, secret: String): SaveAnswer =
        api.put("/api/credentials/${KeloApi.segment(name)}/secret", buildJsonObject { put("secret", secret) })

    /** DELETE /api/vault/{name}/draft: the form was closed without entering anything. */
    suspend fun cancelDraft(api: KeloApi, name: String) {
        api.delete<JsonElement>("/api/vault/${KeloApi.segment(name)}/draft")
    }
}

/** A card a card entry added (secret_request result): its nickname and last four digits, never the number. */
@Serializable
data class SecretCard(
    val id: String = "",
    val label: String = "",
    val brand: String = "",
    val last4: String = "",
    val default: Boolean = false,
)

/** How an answered entry went: a key's test ("ok" / "unchecked"), how a login is kept, or the card that was added. */
@Serializable
data class SecretResult(
    val checked: String? = null,
    val mode: String? = null,
    val card: SecretCard? = null,
)

/**
 * A secure entry the agent asked for (server secret_requests.py): a service key ([KIND_KEY], the vault item [name]), a website
 * login ([KIND_LOGIN]) or a payment card ([KIND_CARD], [name] empty). [hosts]: where the value may be sent (a card: where it will
 * pay; may be empty). [state]: open, saved, cancelled, expired or replaced. [messageId]: the agent message it goes under (only from
 * GET /api/secret-requests; null while that reply is still being written). [source] "draft": a vault item waiting for its value that
 * no agent request stands for; listed in the Keychain only, never in the chat. Never holds a value.
 */
@Serializable
data class SecretRequest(
    val id: String,
    val kind: String = "",
    val name: String = "",
    val label: String = "",
    val hosts: List<String> = emptyList(),
    val reason: String = "",
    val check: Boolean = false,
    val source: String = SOURCE_AGENT,
    @SerialName("turn_id") val turnId: String? = null,
    val state: String = STATE_OPEN,
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("expires_at") val expiresAt: Double = 0.0,
    val result: SecretResult? = null,
) {
    val open: Boolean get() = state == STATE_OPEN
    val isKey: Boolean get() = kind == KIND_KEY
    val isLogin: Boolean get() = kind == KIND_LOGIN
    val isCard: Boolean get() = kind == KIND_CARD
    val isPin: Boolean get() = kind == KIND_PIN
    val fromAgent: Boolean get() = source == SOURCE_AGENT

    companion object {
        const val KIND_KEY = "key"
        const val KIND_LOGIN = "login"
        const val KIND_CARD = "card"
        const val KIND_PIN = "payment_pin"
        const val STATE_OPEN = "open"
        const val STATE_SAVED = "saved"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_EXPIRED = "expired"
        const val STATE_REPLACED = "replaced"
        const val SOURCE_AGENT = "agent"

        /** The "request" object of a secret_request turn event; null when it is not one. */
        fun parse(e: JsonElement?): SecretRequest? =
            (e as? JsonObject)?.let { runCatching { KeloApi.json.decodeFromJsonElement(serializer(), it) }.getOrNull() }?.takeIf { it.id.isNotEmpty() }
    }
}

@Serializable
data class SecretRequestList(val items: List<SecretRequest> = emptyList())

/** GET /api/secret-requests and the answers a secure entry gives. Values go to [VaultCalls] / the wallet, never here. */
object SecretCalls {
    suspend fun list(api: KeloApi): List<SecretRequest> = api.get<SecretRequestList>("/api/secret-requests").items

    /** [Cancel]: the agent is told and does not ask again; an empty vault item made for it is removed. */
    suspend fun cancel(api: KeloApi, id: String): SaveAnswer =
        api.post<SaveAnswer?>("/api/secret-requests/${KeloApi.segment(id)}/cancel") ?: SaveAnswer()

    /** The card form saved [cardId] in the wallet for the card entry [id]. */
    suspend fun cardSaved(api: KeloApi, id: String, cardId: String): SaveAnswer =
        api.post("/api/secret-requests/${KeloApi.segment(id)}/card", buildJsonObject { put("card_id", cardId) })
}

/** Where secure entries show and how a new state lands (pure; shared with the desktop app). */
object SecretRules {
    /** A request after [incoming]: the newer state wins; an answered request never opens again. */
    fun merge(old: SecretRequest?, incoming: SecretRequest): SecretRequest = when {
        old == null -> incoming
        !old.open && incoming.open -> old
        else -> incoming.copy(messageId = incoming.messageId ?: old.messageId)
    }

    /**
     * The list kept for the chat after GET /api/secret-requests (the server lists only OPEN requests): the server's list, merged
     * with what this phone knew (an entry answered here stays answered). Answered entries are dropped here: their one line lives
     * only in the chat's rows, and only while a reply on this phone is going on ([followsHere]).
     */
    fun refresh(kept: List<SecretRequest>, fresh: List<SecretRequest>): List<SecretRequest> =
        settled(fresh.map { merge(kept.find { k -> k.id == it.id }, it) })

    /**
     * After an entry was answered here (saved, cancelled, or found gone): whether a reply on this phone will end later and take
     * the entry's line away then. True when the answer names a reply to follow ([SaveAnswer.turnId], unless it says "none"), or,
     * without one, when a reply is running here now ([busy]) and the server does not say "none". Otherwise the line goes at once:
     * nothing here would ever clear it (the reply already ended, failed before the answer came, or none follows).
     */
    fun followsHere(answer: SaveAnswer?, busy: Boolean): Boolean = when {
        answer?.reply == SaveAnswer.REPLY_NONE -> false
        answer?.turnId != null -> true
        else -> busy
    }

    /** At every end of a reply (done, failed, stopped) and on a reload: only the entries still open are kept. */
    fun settled(list: List<SecretRequest>): List<SecretRequest> = if (list.all { it.open }) list else list.filter { it.open }

    /** One request updated in [list] (added when new). */
    fun upsert(list: List<SecretRequest>, req: SecretRequest): List<SecretRequest> {
        val i = list.indexOfFirst { it.id == req.id }
        return if (i < 0) list + req else list.toMutableList().also { it[i] = merge(list[i], req) }
    }

    /** "api.telegram.org" / "a.com, b.com"; empty when there are none. */
    fun hostsText(hosts: List<String>): String = hosts.joinToString(", ")

    /** Requests for the Keychain's "waiting for input" list: open ones, newest first. */
    fun waiting(list: List<SecretRequest>): List<SecretRequest> = list.filter { it.open }.sortedByDescending { it.createdAt }
}

/** When [저장] may be pressed (pure, unit-tested). */
object SecretEntryRules {
    /** A key: at least 8 characters, one line, no spaces inside (the server refuses anything else). */
    fun keyReady(value: String): Boolean = value.trim().let { it.length >= 8 && it.none { ch -> ch.isWhitespace() } }

    /** A login: a user name and a password, both one line. */
    fun loginReady(user: String, pass: String): Boolean =
        user.isNotBlank() && pass.isNotEmpty() && (user + pass).none { it == '\n' || it == '\r' }
}
