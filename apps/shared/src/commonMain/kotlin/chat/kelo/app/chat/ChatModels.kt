package chat.kelo.app.chat

import chat.kelo.app.core.WholeLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/messages items (oldest first). role is "user" or "assistant". */
@Serializable
data class StoredMessage(
    val id: Long,
    val role: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
    /** Answers to tap under an agent message (only while the user has not written since) */
    val replies: ReplySet? = null,
    val comparison: ComparisonCard? = null,
    /** The emoji on a user message ([Reactions]): 👀 while Kelo works on it, then one that fits; "" none (older servers send none) */
    val reaction: String = "",
    /** On the morning briefing's message: its card (server: briefing.py); the text is the same in plain words */
    val briefing: BriefingCard? = null,
    /** Screenshots Kelo showed with this reply (server chat_images.py) */
    val images: List<ChatImage> = emptyList(),
    /** On the user's message: the files sent with it (server chat_attachments.py) */
    val attachments: List<MessageFile> = emptyList(),
    /** The text is the app's own "read these" sentence: the bubble shows only [attachments] */
    @SerialName("auto_text") val autoText: Boolean = false,
)

/**
 * A file the user sent with a message: [kind] image | video | audio | document; [url] the file and [thumb] a small picture (pictures
 * and videos only, else null), both read with the signed-in API. [path] is "uploads/<name>" as the upload answered.
 */
@Serializable
data class MessageFile(
    val name: String = "", val path: String = "", val kind: String = "document", val mime: String = "", val size: Long = 0,
    val url: String = "", val thumb: String? = null,
) {
    /** Shown in the picture grid (a thumbnail exists, or will once the server has it) */
    val visual: Boolean get() = kind == "image" || kind == "video"

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        fun parseList(e: kotlinx.serialization.json.JsonElement?): List<MessageFile> =
            (e as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement(serializer(), it) }.getOrNull() }
                .filter { it.name.isNotEmpty() }
    }
}

/** A picture Kelo put in the chat (a screenshot of its browser tab): [url] is the server's (auth), [width] x [height] pixels. */
@Serializable
data class ChatImage(val id: String = "", val url: String = "", val width: Int = 0, val height: Int = 0, val caption: String = "")

/**
 * The morning briefing's card (GET /api/messages "briefing"): the weather, today's events and what may have been missed, each item
 * with where it opens ([BriefingOpen]); [hours]: a pending contact-hours suggestion offered at the bottom. Words come from the server
 * in the app's language; times are unix seconds the app shows in its own clock format.
 */
@Serializable
data class BriefingCard(
    val day: String = "",
    val summary: String = "",
    val line: String? = null,
    /** The day in one or two sentences (the model's; the server copies it into [line] too, so show [BriefingRules.headline], never both) */
    val headline: String? = null,
    /** What the model sees across the sources, each with the ids of card items it is about; empty on older cards */
    val connections: List<BriefingLink> = emptyList(),
    /** Next steps phrased as a question to the owner; a tap writes the text into the composer; empty on older cards */
    val suggestions: List<BriefingLink> = emptyList(),
    val quiet: Boolean = false,
    val place: BriefingPlace? = null,
    val weather: BriefingWeather? = null,
    val events: List<BriefingEvent> = emptyList(),
    /** Big events in the coming week (a trip, a flight, a stay, several days), for one short "coming up" line; empty on older cards */
    val ahead: List<BriefingAhead> = emptyList(),
    val missed: List<BriefingItem> = emptyList(),
    val hours: BriefingHours? = null,
)

/** A note or a suggested step of the model: [text] and [refs], ids of items on this card ("w", "e1", "a1", "m1"); may be empty for a step */
@Serializable
data class BriefingLink(val text: String = "", val refs: List<String> = emptyList())

/** One big event ahead: [kind] flight | stay | trip | days; [first] / [last] its first and last local day (YYYY-MM-DD). */
@Serializable
data class BriefingAhead(
    val title: String = "",
    val start: Double = 0.0,
    val kind: String = "",
    val first: String = "",
    val last: String = "",
    val open: BriefingOpen? = null,
    val id: String = "",
)

/** Where the weather is for: the town the owner's phone was last in */
@Serializable
data class BriefingPlace(val name: String = "")

/** [unit]: "C" | "F"; [headline]: "구름 조금 · 최고 11° · 최저 4°"; [attribution]: the data source's credit line, linked to [attributionUrl] */
@Serializable
data class BriefingWeather(
    val high: Int? = null,
    val low: Int? = null,
    val unit: String = "C",
    val headline: String = "",
    val notes: List<BriefingNote> = emptyList(),
    val provider: String = "",
    val attribution: String = "",
    @SerialName("attribution_url") val attributionUrl: String = "",
    val fallback: Boolean = false,
    val id: String = "",
)

/** [kind]: rain_start | rain_now | snow_start | snow_now | sleet_start | sleet_now | temp_drop | wind | heat | cold | air */
@Serializable
data class BriefingNote(val kind: String = "", val text: String = "", val at: Double? = null)

@Serializable
data class BriefingEvent(
    val title: String = "",
    val start: Double = 0.0,
    val end: Double? = null,
    val where: String = "",
    val open: BriefingOpen? = null,
    /** Which calendar it is from ("Naver", "iCloud", an address when two share a name); only when more than one calendar is read, else "" */
    val source: String = "",
    val id: String = "",
)

/** [kind]: followup | job | approval | login | mail | goal | promise; [source]: the mailbox of a mail item, only when several are read */
@Serializable
data class BriefingItem(
    val kind: String = "", val title: String = "", val detail: String = "", val open: BriefingOpen? = null, val source: String = "",
    val id: String = "",
)

/** Where an item opens. [kind]: followup (id, message_id) | job (worker_id, task_id) | goal (id) | event (start) | mail (account, uid, subject) */
@Serializable
data class BriefingOpen(
    val kind: String = "",
    val id: Long? = null,
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("worker_id") val workerId: String? = null,
    @SerialName("task_id") val taskId: Long? = null,
    val start: Double? = null,
    val account: String? = null,
    val uid: String? = null,
    val subject: String? = null,
)

/** A learned contact-hours suggestion still open (answered with the same accept / keep calls as the Records page). */
@Serializable
data class BriefingHours(
    val id: String = "",
    @SerialName("day_type") val dayType: String = "weekday",
    @SerialName("wake_start") val wakeStart: String = "",
    @SerialName("wake_end") val wakeEnd: String = "",
    @SerialName("current_start") val currentStart: String = "",
    @SerialName("current_end") val currentEnd: String = "",
)

/** Questions in an agent message with answers to tap; [goalId]: the goal the answers are for, if any. */
@Serializable
data class ReplySet(val questions: List<ReplyQuestion> = emptyList(), @SerialName("goal_id") val goalId: Long? = null)

/** [says]: per option, the whole sentence a tap sends (the button shows the option); empty or "" = the option itself. [typed]: the options that mean the user writes the answer (a text box opens); [hint]: what to write there. Both may be missing
 *  (an older server): see [QuickReplyRules.isTyped]. [kind]: the kind of answer (empty = choice; [ReplyFields]); [defaultValue]: where
 *  its picker starts (plain form); [min]/[max]/[unit]: a number's. */
@Serializable
data class ReplyQuestion(
    val question: String = "",
    val options: List<String> = emptyList(),
    val says: List<String> = emptyList(),
    val typed: List<String> = emptyList(),
    val hint: String = "",
    val kind: String = "",
    @SerialName("default") val defaultValue: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val unit: String = "",
)

/** One answered question sent with the chat message (POST /api/turns "answers"): its place and the answer's plain form. */
@Serializable
data class ReplyAnswer(val question: Int, val value: String)

/** A comparison card under an agent message (server comparison.py); [open]: the owner has not written since (the buttons show). */
@Serializable
data class ComparisonCard(
    val title: String = "",
    val basis: String = "",
    val rows: List<String> = emptyList(),
    val options: List<ComparisonOption> = emptyList(),
    val note: String = "",
    val open: Boolean = false,
)

@Serializable
data class ComparisonOption(val name: String = "", val values: List<String> = emptyList(), val says: String = "", val recommended: Boolean = false)

/** An optional admin (billing) key for this provider (server balances.ADMIN): kept in the key container, used only to read spend / balance. */
@Serializable
data class AdminKeyInfo(val supported: Boolean = false, val set: Boolean = false, val hint: String? = null)

/** GET /api/providers items: only what the chat and the model sheet use. */
@Serializable
data class ProviderInfo(
    val name: String,
    @SerialName("display_name") val displayName: String = "",
    /** true: ready; false: needs a key or a sign-in; null: cannot be told */
    val configured: Boolean? = null,
    @SerialName("effort_levels") val effortLevels: List<String> = emptyList(),
    @SerialName("effort_note") val effortNote: String? = null,
    /** Signs in with a consumer account whose maker has not confirmed it allows this use */
    val unofficial: Boolean = false,
    val vendor: String = "",
    /** The user's recorded consent (on the server), or null */
    val consent: ProviderConsentRecord? = null,
    @SerialName("consent_version") val consentVersion: Int = 0,
    /** Reached by signing in (CLI or account) and signed in now: true; signed out: false; null: not a sign-in provider or unknown */
    @SerialName("signed_in") val signedIn: Boolean? = null,
    /** The signed-in account's e-mail when the sign-in says it */
    val account: String? = null,
    /** The app can sign this provider in itself (POST /api/providers/{name}/login/start); absent on older servers */
    val signin: Boolean = false,
    /** Where chats go with this provider (the company), or null when it stays on the owner's own computer or network ([AiConsent]) */
    val company: String? = null,
    @SerialName("user_endpoint") val userEndpoint: Boolean = false,
    @SerialName("admin_key") val adminKey: AdminKeyInfo = AdminKeyInfo(),
)

/** PUT /api/providers/{name}/consent was answered with this. */
@Serializable
data class ProviderConsentRecord(val version: Int = 0)

/** GET /api/models?provider= */
@Serializable
data class ModelList(
    val provider: String = "",
    val models: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    /** What the server can say about each model without guessing (no entry: nothing known) */
    val info: Map<String, ModelFacts> = emptyMap(),
    val error: String? = null,
    /** The list could not be fetched just now: this is the last good one */
    val stale: Boolean = false,
)

/**
 * One model's facts from GET /api/models: can it be shown pictures, its context window when the provider or the table says, and the
 * thinking levels the provider's own list states for it (null: the list does not say, the provider's levels apply; empty: none).
 */
@Serializable
data class ModelFacts(
    val images: Boolean = false,
    val context: WholeLong? = null,
    @SerialName("effort_levels") val effortLevels: List<String>? = null,
    /** With empty [effortLevels]: why there are none (a custom server that turned the level away) */
    @SerialName("effort_note") val effortNote: String? = null,
)

/** How much chat history is kept on the phone for the next open: the newest messages, within a size budget. */
object ChatHistoryCache {
    const val MAX_MESSAGES = 200
    /** Characters of message text in all (a few long replies must not make the file huge) */
    const val MAX_CHARS = 300_000

    fun trim(messages: List<StoredMessage>): List<StoredMessage> {
        val last = if (messages.size > MAX_MESSAGES) messages.takeLast(MAX_MESSAGES) else messages
        var total = last.sumOf { it.content.length.toLong() }
        var from = 0
        while (total > MAX_CHARS && from < last.size - 1) total -= last[from++].content.length
        return if (from == 0) last else last.subList(from, last.size).toList()
    }
}
