package chat.kelo.app.my

import chat.kelo.app.chat.ProviderInfo
import chat.kelo.app.chat.vault.VaultItem
import chat.kelo.app.core.WholeLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/usage. [subscription] is null for providers that report no limits. */
@Serializable
data class Usage(
    val today: Tokens = Tokens(),
    val total: Tokens = Tokens(),
    val subscription: Subscription? = null,
    val subscriptions: List<Subscription> = emptyList(),
    /** Balance and spend of API-key providers that report them (OpenRouter, DeepSeek); the others come with `error` set. */
    val accounts: List<ProviderAccount> = emptyList(),
    /** Token use by kind of work, today and this month (null from an older server). */
    val jobs: JobUsage? = null,
    /** The background work model as set, and its last failure (null from an older server). */
    val background: BackgroundModelState? = null,
)

/** GET /api/usage/summary?days=N: the background model's state, the period's sums and the same by provider and model. */
@Serializable
data class UsageSummary(
    val background: BackgroundModelState? = null,
    val total: UsageTotals = UsageTotals(),
    val models: List<ModelUsage> = emptyList(),
)

@Serializable
data class UsageTotals(
    val calls: Long = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
)

@Serializable
data class ModelUsage(
    val provider: String = "",
    val model: String = "",
    val calls: Long = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
)

@Serializable
data class JobUsage(val today: UsagePart = UsagePart(), val month: UsagePart = UsagePart())

/** The model calls since [since], summed, and by kind of work (the largest first). */
@Serializable
data class UsagePart(
    val since: String = "",
    val calls: Long = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    val kinds: List<KindUsage> = emptyList(),
)

/** One kind of work: chat, schedule, checklist, task, worker, reflection, memory, journal, proactive, compaction, followup, probe, other. */
@Serializable
data class KindUsage(
    val kind: String = "",
    val calls: Long = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
)

@Serializable
data class BackgroundModelState(
    val provider: String = "",
    val model: String = "",
    @SerialName("last_fallback") val lastFallback: BackgroundFallback? = null,
)

/** The background model failed at [at] (epoch seconds) and the main model did the job. */
@Serializable
data class BackgroundFallback(val at: Double = 0.0, val kind: String = "", val reason: String = "")

@Serializable
data class ProviderAccount(
    val provider: String = "",
    val role: String = "",
    @SerialName("display_name") val displayName: String = "",
    val currency: String = "",
    val balance: Double? = null,
    @SerialName("used_today") val usedToday: Double? = null,
    @SerialName("used_week") val usedWeek: Double? = null,
    @SerialName("used_month") val usedMonth: Double? = null,
    val limit: Double? = null,
    @SerialName("limit_remaining") val limitRemaining: Double? = null,
    val error: String? = null,
    val admin: Boolean = false,
    @SerialName("admin_error") val adminError: String? = null,
)

@Serializable
data class Tokens(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
) {
    val sum: Long get() = inputTokens + outputTokens
}

@Serializable
data class Subscription(val provider: String = "", val windows: List<UsageWindow> = emptyList(), val error: String? = null, val role: String = "")

/** One limit window, e.g. "Current session" 42% used. [group] names the models it covers ("Gemini Models"), when the provider tells. */
@Serializable
data class UsageWindow(
    val group: String? = null,
    val label: String = "",
    @SerialName("used_percent") val usedPercent: Int = 0,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("resets_text") val resetsText: String? = null,
)

/** GET /api/about */
@Serializable
data class About(val version: String = "", val build: String = "")

/**
 * GET /api/uploads items: a file the user uploaded. [path] is "uploads/<name>"; [kind] is document | image | video | audio;
 * [duration] (seconds) for a video or sound file when the server could read it.
 */
@Serializable
data class Upload(
    val path: String,
    val bytes: WholeLong = 0,
    val modified: Double = 0.0,
    val kind: String = "document",
    val type: String = "",
    val duration: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val name: String get() = path.replace("uploads/", "")
    val isMedia: Boolean get() = kind == "image" || kind == "video"
}

/** GET /api/uploads/{name}/text */
@Serializable
data class UploadText(val name: String = "", val text: String = "")

/** POST /api/devices/pair. [link] is "https://host/#pair=CODE" (missing only when the server knows no address at all). */
@Serializable
data class PairStart(
    val code: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val link: String? = null,
)

/** GET /api/resources. Sizes are bytes, read as [WholeLong] (older servers send some of them as floats, e.g. 536870912.0). */
@Serializable
data class Resources(
    val memory: MemoryUse = MemoryUse(),
    val cpus: Double? = null,
    val disk: DiskUse = DiskUse(),
    val host: HostCapacity = HostCapacity(),
    val profile: String = "",
    val minimum: Sizes = Sizes(),
    val recommended: Sizes = Sizes(),
    /** The storage warning (absent on older servers: nothing to warn about) */
    val alert: StorageAlert = StorageAlert(),
    /** This sign-in may open the cleanup screen (the owner's own) */
    @SerialName("can_clean") val canClean: Boolean = false,
)

/**
 * GET /api/resources "alert": [level] ok | warn (80% of the storage cap) | critical (95%); [percent] of the cap (null without one);
 * [hostLow]: the Kelo server's own disk is almost full; [top]: the part that takes the most room (database, uploads, ...).
 */
@Serializable
data class StorageAlert(
    val level: String = "ok",
    val percent: Int? = null,
    @SerialName("host_low") val hostLow: Boolean = false,
    val top: String? = null,
)

/** GET /api/storage/cleanup: what can safely be removed, grouped. */
@Serializable
data class CleanupList(
    val groups: List<CleanupGroup> = emptyList(),
    val total: WholeLong = 0,
    @SerialName("older_than_days") val olderThanDays: Int = 14,
)

/** One group: uploads | images | browser_history | results | backups; [name] is the server's own words (used for an unknown id). */
@Serializable
data class CleanupGroup(
    val id: String,
    val name: String = "",
    val bytes: WholeLong = 0,
    val count: Int = 0,
    val items: List<CleanupItem> = emptyList(),
)

/**
 * One thing that can go. [date] is unix seconds; [keepsText]: an upload whose read text stays; [trashed]: already in the results'
 * trash; [kind]: a backup's kind (update | memory | migration).
 */
@Serializable
data class CleanupItem(
    val id: String,
    val name: String = "",
    val bytes: WholeLong = 0,
    val date: Double = 0.0,
    @SerialName("keeps_text") val keepsText: Boolean = false,
    val trashed: Boolean = false,
    val kind: String = "",
)

/** POST /api/storage/cleanup {"items", "confirm": true}: what went, what was skipped, the room freed, and the list after. */
@Serializable
data class CleanupResult(
    val removed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val freed: WholeLong = 0,
    val groups: List<CleanupGroup> = emptyList(),
    val total: WholeLong = 0,
)

@Serializable
data class MemoryUse(val used: WholeLong? = null, val limit: WholeLong? = null)

@Serializable
data class DiskUse(
    val used: WholeLong = 0,
    val limit: WholeLong? = null,
    val parts: Map<String, WholeLong> = emptyMap(),
)

@Serializable
data class HostCapacity(
    val memory: WholeLong? = null,
    val cpus: Double? = null,
    @SerialName("disk_free") val diskFree: WholeLong? = null,
)

@Serializable
data class Sizes(val memory: WholeLong = 0, val cpus: Double = 0.0, val disk: WholeLong = 0)

/**
 * What the resources page shows: the last numbers read ([data]), or before any arrived, that it is still loading or that the read
 * failed (then an error with "try again", never a blank page). A refresh that fails after a success keeps the last numbers.
 */
data class ResourcesLoad(val data: Resources? = null, val failed: Boolean = false) {
    val loading: Boolean get() = data == null && !failed
    val showError: Boolean get() = data == null && failed

    fun loaded(r: Resources) = ResourcesLoad(r, failed = false)
    fun readFailed() = if (data != null) this else copy(failed = true)
    fun retrying() = copy(failed = false)
}

/** GET /api/providers items, with everything the model page and the vault's AI key page use. */
@Serializable
data class ProviderDetail(
    val name: String,
    @SerialName("display_name") val displayName: String = "",
    val oauth: Boolean = false,
    @SerialName("logged_in") val loggedIn: Boolean? = null,
    val configured: Boolean? = null,
    val cli: CliStatus? = null,
    @SerialName("effort_levels") val effortLevels: List<String> = emptyList(),
    @SerialName("effort_note") val effortNote: String? = null,
    @SerialName("takes_key") val takesKey: Boolean = false,
    /** "app" (saved in the app), "env" (the server's .env), or null (none) */
    @SerialName("key_source") val keySource: String? = null,
    @SerialName("key_hint") val keyHint: String? = null,
    /** An OpenAI-compatible server the user points it at (custom, lmstudio); absent on older servers */
    @SerialName("takes_url") val takesUrl: Boolean = false,
    /** That server's address, empty when not set */
    @SerialName("base_url") val baseUrl: String? = null,
    val unofficial: Boolean = false,
    val vendor: String = "",
    val consent: chat.kelo.app.chat.ProviderConsentRecord? = null,
    @SerialName("consent_version") val consentVersion: Int = 0,
    /** Signed in now through a CLI or an account sign-in (true), signed out (false), or neither / unknown (null) */
    @SerialName("signed_in") val signedIn: Boolean? = null,
    val account: String? = null,
    /** The app can sign this provider in itself; absent on older servers */
    val signin: Boolean = false,
    /** Where chats go with this provider (the company), or null for the owner's own network ([chat.kelo.app.chat.AiConsent]) */
    val company: String? = null,
    @SerialName("user_endpoint") val userEndpoint: Boolean = false,
) {
    /** The shorter form the chat's ModelPicker takes. */
    fun asInfo() = ProviderInfo(name, displayName, configured, effortLevels, effortNote, unofficial, vendor, consent, consentVersion, signedIn, account, signin, company, userEndpoint)
}

@Serializable
data class CliStatus(
    val installed: Boolean = false,
    val installable: Boolean = false,
    @SerialName("logged_in") val loggedIn: Boolean? = null,
    val hint: String = "",
    val remote: Boolean = false,
    val version: String? = null,
    @SerialName("update_hint") val updateHint: String? = null,
    /** A newer CLI is published than the one installed ([latestVersion]); [updateHint] says how to update */
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("latest_version") val latestVersion: String? = null,
    /** This sign-in may press 업데이트: the owner's, and the CLI runs in a container that updates itself (CliUpdateRules) */
    val updatable: Boolean = false,
)

/** GET /api/vault, with the key placement the item page shows (chat.vault.VaultInfo leaves it out). */
@Serializable
data class VaultState(
    val locked: Boolean = false,
    /** "Use codes from my mail automatically": a code a site mails during a sign-in Kelo started is typed without asking (default on). */
    @SerialName("mail_codes_auto") val mailCodesAuto: Boolean = true,
    val items: List<VaultEntry> = emptyList(),
    val pending: List<VaultEntry> = emptyList(),
    val verifying: List<String> = emptyList(),
)

@Serializable
data class Placement(val type: String? = null, val header: String? = null, val param: String? = null, val via: String? = null)

@Serializable
data class VaultEntry(
    val name: String,
    val label: String = "",
    val kind: String = "",
    val hosts: List<String> = emptyList(),
    val endpoint: String = "",
    @SerialName("has_secret") val hasSecret: Boolean = false,
    @SerialName("auto_allow") val autoAllow: Boolean = false,
    @SerialName("last_used") val lastUsed: Double? = null,
    @SerialName("username_hint") val usernameHint: String? = null,
    val placement: Placement = Placement(),
    @SerialName("last_result") val lastResult: String? = null,
    @SerialName("last_result_at") val lastResultAt: Double? = null,
    @SerialName("last_result_detail") val lastResultDetail: String = "",
    /** Schedules allowed (once, at setup) to sign in with this login without asking */
    @SerialName("schedule_grants") val scheduleGrants: List<chat.kelo.app.agent.ScheduleGrant> = emptyList(),
) {
    val isLogin: Boolean get() = kind == KIND_LOGIN

    /** The last real use failed (a sign-in the site turned down, a key the service refused). */
    val isBad: Boolean get() = lastResult == "failed" || lastResult == "rejected"

    /** Made in the Connectors tab by signing in, not by typing a key. */
    val signedIn: Boolean get() = kind == KIND_EMAIL || placement.via == "oauth" || name == "github-api"

    /** The form the shared SecureEntryForm takes. */
    fun asItem() = VaultItem(
        name = name, label = label, kind = kind, hosts = hosts, endpoint = endpoint, hasSecret = hasSecret, autoAllow = autoAllow,
        lastUsed = lastUsed, usernameHint = usernameHint, lastResult = lastResult, lastResultAt = lastResultAt,
        lastResultDetail = lastResultDetail,
    )

    companion object {
        const val KIND_LOGIN = "login"
        const val KIND_EMAIL = "email"
        const val KIND_API = "api"
    }
}

/** GET /api/vault/log items. */
@Serializable
data class VaultLogRow(val ts: Double = 0.0, val item: String = "", val action: String = "", val detail: String = "", val ok: Boolean = true)

/** POST /api/vault/logins answer. */
@Serializable
data class NewLogin(val name: String)

/**
 * What the My home page shows; each part arrives on its own and keeps its last value when its call fails. Kept on the phone
 * (encrypted) for the next open. [sizes] is the server's size profile name ("minimum", ...), turned into words on screen.
 */
@Serializable
data class MyOverview(
    val usage: Usage? = null,
    val devices: Int? = null,
    val creds: List<VaultEntry> = emptyList(),
    val locked: Boolean = false,
    val documents: Int? = null,
    val sizes: String? = null,
    /** The storage warning (StorageRules.key: warn | critical | host_low), null when there is none */
    val storage: String? = null,
    /** How full the storage cap is (percent), for the 80% line; null without a cap */
    val storagePercent: Int? = null,
    val version: String? = null,
    /** Automatic payments the owner has not looked at yet (the dot on 결제 카드) */
    val walletUnseen: Int? = null,
    val cards: Int? = null,
    /** An owner sign-in on the operator's server (GET /api/operator/status): the 운영자 page shows; null = not known yet */
    val operator: Boolean? = null,
) {
    companion object {
        /** The refresh did not reach the server: every part failed, at least one of them for want of a connection. */
        fun offline(failures: List<Throwable?>): Boolean =
            failures.isNotEmpty() && failures.all { it != null } && failures.any { it !is chat.kelo.app.core.ApiError || it.isNetwork }
    }
}
