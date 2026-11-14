package chat.kelo.app.chat

import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One event of a reply. Unknown types are kept as [Unknown] and ignored. */
sealed interface TurnEvent {
    data object Thinking : TurnEvent
    data class Text(val text: String) : TurnEvent
    /**
     * [label]: the short words to show instead of [name] (empty from an older server); [hidden]: show nothing for it. [group]: calls
     * of one group in a row fold into one chip ("" = the name); [done]: that chip's words with {n} ("Read {n} messages").
     */
    data class ToolStarted(
        val id: String, val name: String, val arguments: JsonObject, val label: String = "", val hidden: Boolean = false,
        val group: String = "", val done: String = "",
    ) : TurnEvent {
        /** arguments.service when it is a string (connect_service names the connector there). */
        val service: String? get() = (arguments["service"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
    data class ToolResult(
        val id: String, val name: String, val output: String, val isError: Boolean, val approved: Boolean?,
        val label: String = "", val hidden: Boolean = false, val group: String = "", val done: String = "",
    ) : TurnEvent
    /** Confirm_purchase also carries [payment] and, when a wallet card was named, [card]. */
    data class ApprovalRequest(
        val approvalId: String, val name: String, val arguments: JsonObject, val detail: String?,
        val label: String = "", val payment: PaymentInfo? = null, val card: ApprovalCardInfo? = null,
        /** The card may offer "keep allowing this in that scheduled job" (remember "schedule") */
        val canRememberSchedule: Boolean = false,
        /** Up to four readable label / value pairs (hosts rather than URLs); the raw arguments are never shown */
        val lines: List<ApprovalLine> = emptyList(),
        /** The card's title as a whole question in the user's language ("로그인할까요?"); "" from an older server or a tool without one */
        val ask: String = "",
        /** "human_check": a site's check whether a person is there, handed to the user (solve it in the live view, or skip the site) */
        val kind: String = "",
        /** The browser tab it is about (a human check) */
        val tab: String = "",
        /** The setup card of a "when X happens, do Y" task (task_allow): trigger, action, limits, the card's own auto-pay limit */
        val grant: chat.kelo.app.agent.TaskGrantAsk? = null,
        /** Asked in a run of a task marked urgent at setup: time matters; [missed] says what a late answer would miss */
        val urgent: Boolean = false,
        val missed: String = "",
        /** The items a permission for many covers, one short line each (the card's "see the list"); empty for most cards */
        val items: List<String> = emptyList(),
        /** A payment whose site has a saved payment PIN: Allow also releases it from this phone (fingerprint / face) */
        val pin: PinAsk? = null,
        /** What a human check is ([kind] "human_check"): a person check, or a sign-in verification ([CheckInfo.isVerification]) */
        val check: CheckInfo? = null,
        /** The site the step is on (the rule's scope when remembered) and whether "don't ask again on this site" may be offered */
        val site: String = "",
        val canRemember: Boolean = false,
    ) : TurnEvent
    /**
     * A picture of the assistant's browser tab; one card per tool call [id], updated in place. [image] is a data: URL or null.
     * [tab]: the tab it shows (this phone's, "owner", a job's "task:…", a run's "schedule:…"); "" from an older server.
     */
    data class Browser(
        val id: String, val title: String, val url: String, val host: String, val status: String, val image: String?,
        val width: Int = 0, val height: Int = 0, val at: Double = 0.0, val tab: String = "",
    ) : TurnEvent
    /** An allowed payment waits for this phone to send an end-to-end card before [expiresAt] (unix seconds). */
    data class CardNeeded(
        val id: String, val approvalId: String, val card: CardBrief, val site: String, val amount: Double, val currency: String,
        val amountText: String, val expiresAt: Long,
    ) : TurnEvent
    /** A payment went ahead without asking, under the user's auto-pay rules. */
    /** [payment]: the whole payment part (an order's items and where it goes, when the server sends them) */
    data class PaymentAuto(
        val id: String, val card: CardBrief?, val site: String, val amountText: String, val summary: String, val payment: PaymentInfo? = null,
    ) : TurnEvent
    /**
     * The reply used its step budget. [next] from a newer server: "background" (the rest goes on in a job), "continue" or "stopped"
     * (a Continue button follows); the server then also writes a line saying so. "" = an older server.
     */
    data class StepLimit(val steps: Int, val next: String = "", val progress: Int = 0) : TurnEvent
    data class Fallback(val provider: String, val model: String, val reason: String) : TurnEvent
    data class Retry(val reason: String) : TurnEvent
    data object Reconnecting : TurnEvent
    data class Done(val inputTokens: Long, val outputTokens: Long, val steps: Int) : TurnEvent
    data class Error(val message: String) : TurnEvent
    /** The agent opened, snoozed or dropped a follow-up during the reply: the chat loads the follow-ups again. */
    data class Followup(val followupId: String, val status: String) : TurnEvent
    /** The agent put answer buttons under the message it is writing: the chat loads the messages again once the reply is over. */
    data object Replies : TurnEvent
    /** A comparison card was attached; the app loads the messages again when the reply ends. */
    data object Comparison : TurnEvent
    /** A picture Kelo shows in the chat (send_screenshot) */
    data class Image(val image: ChatImage) : TurnEvent
    /** The emoji on the user's message this reply answers ([messageId]): 👀 when it starts, the chosen one when the work is over. */
    data class Reaction(val messageId: Long, val emoji: String) : TurnEvent
    /** A human check was handed to the user ([state] "waiting"), or that hand-over ended: solved | skipped | expired | cancelled. */
    data class HumanCheck(val approvalId: String, val state: String, val site: String, val tab: String, val check: CheckInfo? = null) : TurnEvent
    /**
     * "location_request" (server LOCATION REQUEST): the reply waits until this phone shares its place or says no. [state] waiting |
     * allowed | refused | expired | cancelled; [settings]: the owner refused before, so the card offers the phone's settings instead
     * of the system prompt; [reason]: the agent's words (may be empty).
     */
    data class LocationRequest(val approvalId: String, val state: String, val reason: String, val settings: Boolean, val expiresAt: Long) : TurnEvent
    /**
     * "user_message": the first event of a reply to a message that carried files: the stored message's id, the files as the server
     * keeps them (with their thumbnails) and whether the text is the app's own sentence ([autoText]: the bubble shows only the files).
     */
    data class UserMessage(val messageId: Long, val attachments: List<MessageFile>, val autoText: Boolean) : TurnEvent
    /** The agent asks where an order goes (ask_shipping, which waits for the answer): the address form card in the chat. */
    data class ShippingForm(val ask: chat.kelo.app.wallet.ShippingAsk) : TurnEvent
    /** A secure entry the agent asked for (a key, a login, a card), or the same one again with its new state once answered */
    data class SecretRequested(val request: chat.kelo.app.chat.vault.SecretRequest) : TurnEvent
    /** A payment step that needs the user (approve in another app, a code, the PIN keypad), or the same one again after a change */
    data class PaymentStepChanged(val step: PaymentStep) : TurnEvent
    /** A payment's PIN waits for this phone to release it */
    data class PinNeeded(val need: PinNeed) : TurnEvent
    data class Unknown(val type: String) : TurnEvent

    companion object {
        fun parse(e: JsonElement): TurnEvent {
            val o = e as? JsonObject ?: return Unknown("")
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            fun obj(k: String) = o[k] as? JsonObject ?: JsonObject(emptyMap())
            fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
            fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            return when (val type = s("type")) {
                "thinking" -> Thinking
                "text" -> Text(s("text"))
                "tool_started" -> ToolStarted(s("id"), s("name"), obj("arguments"), s("label"), bool("hidden") == true, s("group"), s("done"))
                "tool_result" -> ToolResult(s("id"), s("name"), s("output"), bool("is_error") == true, bool("approved"), s("label"), bool("hidden") == true,
                    s("group"), s("done"))
                "approval_request" -> ApprovalRequest(
                    s("approval_id"), s("name"), obj("arguments"), o["detail"]?.let { s("detail") }?.ifEmpty { null }, s("label"),
                    (o["payment"] as? JsonObject)?.let(PaymentInfo::parse), (o["card"] as? JsonObject)?.let(ApprovalCardInfo::parse),
                    (o["can_remember_schedule"] as? JsonPrimitive)?.booleanOrNull == true,
                    ApprovalLine.parseList(o["lines"]), s("ask"), s("kind"), s("tab"),
                    chat.kelo.app.agent.TaskGrantAsk.parse(o["grant"]), bool("urgent") == true, s("missed"),
                    (o["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.ifEmpty { null } },
                    PinAsk.parse(o["pin"]),
                    CheckInfo.parse(o["check"]),
                    s("site"), bool("can_remember") == true,
                )
                "payment_step" -> PaymentStep.parse(o)?.let { PaymentStepChanged(it) } ?: Unknown(type)
                "pin_needed" -> PinNeed.parse(o)?.let { PinNeeded(it) } ?: Unknown(type)
                "location_request" -> LocationRequest(s("approval_id"), s("state").ifEmpty { "waiting" }, s("reason"), bool("settings") == true, long("expires_at"))
                "user_message" -> UserMessage(long("message_id"), MessageFile.parseList(o["attachments"]), bool("auto_text") == true)
                "human_check" -> HumanCheck(s("approval_id"), s("state"), s("site"), s("tab"), CheckInfo.parse(o["check"]))
                "shipping_form" -> ShippingForm(chat.kelo.app.wallet.ShippingAsk.parse(o))
                "secret_request" -> chat.kelo.app.chat.vault.SecretRequest.parse(o["request"])?.let { SecretRequested(it) } ?: Unknown(type)
                "browser" -> Browser(
                    s("id"), s("title"), s("url"), s("host"), s("status"), s("image").takeIf { it.startsWith("data:image/") },
                    int("width"), int("height"), (o["at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, s("tab"),
                )
                "card_needed" -> CardNeeded(
                    s("id"), s("approval_id"), CardBrief.parse(obj("card")), s("site"), (o["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    s("currency"), s("amount_text"), long("expires_at"),
                )
                "payment_auto" -> {
                    val p = obj("payment")
                    fun ps(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
                    PaymentAuto(s("id"), (o["card"] as? JsonObject)?.let(CardBrief::parse), ps("site").ifEmpty { ps("host") }, ps("amount_text"), ps("summary"),
                        (o["payment"] as? JsonObject)?.let(PaymentInfo::parse))
                }
                "step_limit" -> StepLimit(int("steps"), s("next"), int("progress"))
                "fallback" -> Fallback(s("provider"), s("model"), s("reason"))
                "retry" -> Retry(s("reason"))
                "reconnecting" -> Reconnecting
                "done" -> Done(long("input_tokens"), long("output_tokens"), int("steps"))
                "error" -> Error(s("message"))
                "followup" -> Followup(s("followup_id"), s("status"))
                "replies" -> Replies
                "comparison" -> Comparison
                "image" -> s("url").takeIf { it.startsWith("/api/") }?.let { u ->
                    Image(ChatImage(s("image_id"), u, int("width"), int("height"), s("caption")))
                } ?: Unknown(type)
                "reaction" -> Reaction(long("message_id"), s("emoji"))
                else -> Unknown(type)
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** JSON.stringify(args, null, 2) */
        fun prettyArgs(args: JsonObject): String = pretty.encodeToString(JsonElement.serializer(), args)
    }
}

private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
private fun JsonObject.flag(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true

/**
 * Approval_request.payment of confirm_purchase: what will be paid, where. An order also carries its [items] (empty: [summary] says
 * what it is), where it goes ([shipping], null when nothing is shipped or not known), a [coupon] ("" for none) and [orderWhat].
 */
data class PaymentInfo(
    val site: String, val summary: String, val amount: Double, val currency: String, val amountText: String,
    val items: List<chat.kelo.app.wallet.OrderItem> = emptyList(),
    val shipping: chat.kelo.app.wallet.OrderShipping? = null,
    val coupon: String = "",
    val orderWhat: String = "",
) {
    companion object {
        fun parse(o: JsonObject) = PaymentInfo(
            o.str("site"), o.str("summary"), (o["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, o.str("currency").ifEmpty { "KRW" }, o.str("amount_text"),
            items = chat.kelo.app.wallet.OrderItem.parseList(o["items"]),
            shipping = chat.kelo.app.wallet.OrderShipping.parse(o["shipping"]),
            coupon = o.str("coupon").trim(),
            orderWhat = (o["order"] as? JsonObject)?.str("what").orEmpty(),
        )
    }
}

/** A wallet card as chat events name it (never a number). */
data class CardBrief(val id: String, val label: String, val brand: String, val last4: String) {
    companion object {
        fun parse(o: JsonObject) = CardBrief(o.str("id"), o.str("label"), o.str("brand"), o.str("last4"))
    }
}

/**
 * Approval_request.card: the card named for a payment. [needsCard]: an end-to-end card, so Allow also asks for the owner's check
 * and sends the card from this phone. [reason] / [reasonText]: why it asks instead of paying automatically.
 */
data class ApprovalCardInfo(
    val card: CardBrief, val autoPay: Boolean, val needsCard: Boolean, val reason: String, val reasonText: String,
) {
    companion object {
        fun parse(o: JsonObject) = ApprovalCardInfo(CardBrief.parse(o), o.flag("auto_pay"), o.flag("needs_card"), o.str("reason"), o.str("reason_text"))
    }
}

/** GET /api/turns/{id}?after=n&wait=20 */
data class TurnPoll(val events: List<TurnEvent>, val next: Int, val done: Boolean) {
    companion object {
        fun parse(e: JsonElement): TurnPoll {
            val o = e as? JsonObject ?: JsonObject(emptyMap())
            val events = (o["events"] as? JsonArray).orEmpty().map(TurnEvent::parse)
            return TurnPoll(events, (o["next"] as? JsonPrimitive)?.intOrNull ?: 0, (o["done"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }
}

/** The three calls a reply needs; [KeloTurnApi] is the real one, tests use a fake. */
interface TurnApi {
    /** POST /api/turns {text, timezone} -> id */
    suspend fun start(text: String, timezone: String): String
    /** The same with "answers": the quick-reply fields [text] answers (their plain forms; the server checks them). */
    suspend fun start(text: String, timezone: String, answers: List<ReplyAnswer>): String = start(text, timezone)
    /** The same with "secrets": "allow" (the owner chose to send a message the server held back for a secret; [SecretGuard]). */
    suspend fun start(text: String, timezone: String, answers: List<ReplyAnswer>, secrets: String?): String =
        if (secrets == null) start(text, timezone, answers) else throw UnsupportedOperationException("secrets")
    /**
     * The same with the files sent with it ([attachments]: "uploads/<name>" paths, kept with the message by the server) and
     * [autoText]: the text is the app's own sentence (nothing was typed), so the bubbles show only the files.
     */
    suspend fun start(text: String, timezone: String, answers: List<ReplyAnswer>, secrets: String?, attachments: List<String>, autoText: Boolean): String =
        start(text, timezone, answers, secrets)
    suspend fun poll(id: String, after: Int): TurnPoll
    /** DELETE /api/turns/{id} */
    suspend fun stop(id: String)
    /**
     * Follows reply [id] over its WebSocket from event [after]: [onEvent] gets (seq, event) in order. Answers how it ended. The
     * default (fakes, older code) says the socket is not available, so the follower polls.
     */
    suspend fun socket(id: String, after: Int, onEvent: (Int, TurnEvent) -> Unit): SocketEnd = SocketEnd.Unavailable
}

/** How a reply's WebSocket ended. */
sealed interface SocketEnd {
    /** The reply is over; [next] = the count of its events. */
    data class Done(val next: Int) : SocketEnd
    /** The server does not know the reply (GET would answer 404). */
    data object Gone : SocketEnd
    /** Closed or failed before the reply was over (no signal, a proxy cut it, 4401 …). */
    data object Broken : SocketEnd
    /** No socket here (a fake, or the route refused the upgrade at once). */
    data object Unavailable : SocketEnd
}

class KeloTurnApi(private val api: KeloApi) : TurnApi {
    override suspend fun start(text: String, timezone: String): String = start(text, timezone, emptyList())

    override suspend fun start(text: String, timezone: String, answers: List<ReplyAnswer>): String = start(text, timezone, answers, null)

    override suspend fun start(text: String, timezone: String, answers: List<ReplyAnswer>, secrets: String?): String =
        start(text, timezone, answers, secrets, emptyList(), false)

    override suspend fun start(
        text: String, timezone: String, answers: List<ReplyAnswer>, secrets: String?, attachments: List<String>, autoText: Boolean,
    ): String {
        val answer = api.request("POST", "/api/turns", kotlinx.serialization.json.buildJsonObject {
            if (attachments.isNotEmpty()) {
                put("attachments", kotlinx.serialization.json.JsonArray(attachments.map { p ->
                    kotlinx.serialization.json.buildJsonObject { put("path", JsonPrimitive(p)) }
                }))
                if (autoText) put("auto_text", JsonPrimitive(true))
            }
            put("text", JsonPrimitive(text)); put("timezone", JsonPrimitive(timezone))
            put("ai_consent", JsonPrimitive(AiConsent.CHECK))   // held back (428) until someone agreed on this server (AiConsent)
            if (secrets != null) put("secrets", JsonPrimitive(secrets))
            if (answers.isNotEmpty()) put("answers", kotlinx.serialization.json.JsonArray(answers.map { a ->
                kotlinx.serialization.json.buildJsonObject { put("question", JsonPrimitive(a.question)); put("value", JsonPrimitive(a.value)) }
            }))
        })
        return ((answer as? JsonObject)?.get("id") as? JsonPrimitive)?.content.orEmpty()
    }

    override suspend fun poll(id: String, after: Int): TurnPoll =
        TurnPoll.parse(api.request("GET", "/api/turns/${KeloApi.segment(id)}?after=$after&wait=20"))

    override suspend fun stop(id: String) {
        api.request("DELETE", "/api/turns/${KeloApi.segment(id)}")
    }

    override suspend fun socket(id: String, after: Int, onEvent: (Int, TurnEvent) -> Unit): SocketEnd {
        val events = Channel<Pair<Int, TurnEvent>>(Channel.UNLIMITED)
        val ended = CompletableDeferred<SocketEnd>()
        val ws = api.newWebSocket("/api/turns/${KeloApi.segment(id)}/ws?after=$after", object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                val seq = (o["seq"] as? JsonPrimitive)?.intOrNull
                val ev = o["event"] as? JsonObject
                when {
                    seq != null && ev != null -> events.trySend(seq to TurnEvent.parse(ev))
                    (o["done"] as? JsonPrimitive)?.booleanOrNull == true ->
                        ended.complete(SocketEnd.Done((o["next"] as? JsonPrimitive)?.intOrNull ?: 0))
                    (o["type"] as? JsonPrimitive)?.contentOrNull == "gone" -> ended.complete(SocketEnd.Gone)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(SocketEnd.Broken) }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.complete(if (response != null) SocketEnd.Unavailable else SocketEnd.Broken)
            }
        }, withAuth = true)
        // Whatever the listener finished with is read after every event it queued before: the queue is drained first.
        ended.invokeOnCompletion { events.close() }
        try {
            for ((seq, ev) in events) onEvent(seq, ev)
            return ended.await()
        } catch (e: Throwable) {
            ws.cancel()   // cancelled, or the caller threw out of onEvent (a gap): close the socket
            throw e
        } finally {
            if (!ended.isCompleted) ws.cancel()
        }
    }
}

/** One readable pair of an approval card ("사이트" · "github.com"). [full]: shown whole, never cut (a text message the phone will send). */
data class ApprovalLine(val label: String, val value: String, val full: Boolean = false) {
    companion object {
        const val MAX = 4

        fun parseList(e: JsonElement?): List<ApprovalLine> {
            val arr = e as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                fun t(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                val label = t("label")
                val value = t("value")
                val full = (o["full"] as? JsonPrimitive)?.content == "true"
                if (value.isEmpty()) null else ApprovalLine(label, value, full)
            }.take(MAX)
        }
    }
}

// ---- payment steps (server payment_steps.py) and payment PIN releases (payment_pins.py)

/**
 * A payment step that needs the user (server payment_steps.py): approve in another app ([KIND_APP], with a [code], a [qr] picture and
 * a countdown to [expiresAt]), a one-time code ([KIND_OTP]), a passkey ([KIND_PASSKEY]), or the page's PIN keypad shown in the card
 * ([KIND_PIN_PAD], tapped there). [state]: waiting | expired | renewing | checking | finishing, then approved | done | cancelled |
 * timeout | closed. The same event comes again with each change (same [stepId]).
 */
data class PaymentStep(
    val stepId: String,
    val kind: String,
    val state: String,
    val site: String = "",
    val summary: String = "",
    val amountText: String = "",
    val payWith: String = "",
    val items: List<String> = emptyList(),
    val code: String? = null,
    val codeShown: String? = null,
    val qr: String? = null,
    val expiresAt: Long? = null,
    val secondsTotal: Int? = null,
    val instruction: String = "",
    val canResend: Boolean = false,
    val needsCode: Boolean = false,
    val taps: Int = 0,
    val canClear: Boolean = false,
    val tab: String = "",
    val openedAt: Double = 0.0,
) {
    val open: Boolean get() = state in OPEN
    val isPad: Boolean get() = kind == KIND_PIN_PAD

    companion object {
        const val KIND_APP = "app"
        const val KIND_OTP = "otp"
        const val KIND_PASSKEY = "passkey"
        const val KIND_PIN_PAD = "pin_pad"
        val OPEN = setOf("waiting", "expired", "renewing", "checking", "finishing")

        fun parse(e: JsonElement?): PaymentStep? {
            val o = e as? JsonObject ?: return null
            fun s(k: String, from: JsonObject = o) = (from[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            val id = s("step_id").ifEmpty { return null }
            val order = o["order"] as? JsonObject ?: JsonObject(emptyMap())
            val items = (order["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { i ->
                val q = (i["quantity"] as? JsonPrimitive)?.intOrNull ?: 1
                val name = s("name", i)
                if (q > 1) "$name × $q" else name
            }.filter { it.isNotBlank() }
            return PaymentStep(
                stepId = id, kind = s("kind"), state = s("state").ifEmpty { "waiting" }, site = s("site"),
                summary = s("summary", order), amountText = s("amount_text", order), payWith = s("pay_with", order), items = items,
                code = s("code").ifEmpty { null }, codeShown = s("code_shown").ifEmpty { null },
                qr = s("qr").takeIf { it.startsWith("data:image/") },
                expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull, secondsTotal = (o["seconds_total"] as? JsonPrimitive)?.intOrNull,
                instruction = s("instruction"), canResend = (o["can_resend"] as? JsonPrimitive)?.booleanOrNull == true,
                needsCode = (o["needs_code"] as? JsonPrimitive)?.booleanOrNull == true, taps = (o["taps"] as? JsonPrimitive)?.intOrNull ?: 0,
                canClear = (o["can_clear"] as? JsonPrimitive)?.booleanOrNull == true, tab = s("tab"),
                openedAt = (o["opened_at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }
    }
}

/** A payment's PIN waits for this phone to release it (fingerprint / face) until [expiresAt]. [urgent]: nobody asked on a card. */
data class PinNeed(
    val slotId: String, val pinId: String, val pinLabel: String, val site: String, val amountText: String, val digits: Int,
    val expiresAt: Long, val urgent: Boolean, val send: CardSend = CardSend.Idle,
) {
    companion object {
        fun parse(o: JsonObject): PinNeed? {
            fun s(k: String, from: JsonObject = o) = (from[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            val pin = o["pin"] as? JsonObject ?: return null
            val slot = s("slot_id").ifEmpty { s("approval_id") }.ifEmpty { return null }
            return PinNeed(slot, s("id", pin), s("label", pin), s("site"), s("amount_text"), (pin["digits"] as? JsonPrimitive)?.intOrNull ?: 0,
                (o["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0L, (o["urgent"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }
}

/** Approval_request.pin: Allow also releases the site's payment PIN from this phone (after the owner's check). */
data class PinAsk(val id: String, val label: String, val site: String, val digits: Int) {
    companion object {
        fun parse(e: JsonElement?): PinAsk? {
            val o = e as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            if ((o["needs_pin"] as? JsonPrimitive)?.booleanOrNull != true) return null
            return PinAsk(s("id").ifEmpty { return null }, s("label"), s("site"), (o["digits"] as? JsonPrimitive)?.intOrNull ?: 0)
        }
    }
}

object PaymentStepRules {
    /** The list after an event or a fresh load: the same step is replaced in place, a new one is added; ended ones stay folded. */
    fun upsert(steps: List<PaymentStep>, step: PaymentStep): List<PaymentStep> {
        val i = steps.indexOfFirst { it.stepId == step.stepId }
        if (i < 0) return steps + step
        val old = steps[i]
        // an older copy (a load that crossed an event) never reopens a step this phone saw end
        if (!old.open && step.open) return steps
        return steps.toMutableList().also { it[i] = step }
    }

    /** GET /api/payment-steps: what the server lists, merged into what this phone has. */
    fun refresh(steps: List<PaymentStep>, fresh: List<PaymentStep>): List<PaymentStep> {
        var out = steps.filter { s -> fresh.any { it.stepId == s.stepId } || !s.open }
        for (f in fresh) out = upsert(out, f)
        return out.takeLast(MAX)
    }

    /** Steps the chat shows: the open ones, then the last one that ended (folded), newest last. */
    fun visible(steps: List<PaymentStep>): List<PaymentStep> {
        val open = steps.filter { it.open }
        val ended = steps.filterNot { it.open }.takeLast(1)
        return (ended + open).sortedBy { it.openedAt }
    }

    fun pinUpsert(needs: List<PinNeed>, need: PinNeed): List<PinNeed> {
        val i = needs.indexOfFirst { it.slotId == need.slotId }
        return if (i < 0) needs + need else needs.toMutableList().also { it[i] = need.copy(send = needs[i].send) }
    }

    /** "482 913" as shown on the page, else the code in groups of three. */
    fun codeText(step: PaymentStep): String {
        val shown = step.codeShown?.trim().orEmpty()
        if (shown.isNotEmpty()) return shown
        val c = step.code.orEmpty()
        return if (c.length in 6..9 && c.all { it.isDigit() }) c.chunked(3).joinToString(" ") else c
    }

    /** Seconds left (never below 0), or null when the page gave no time. */
    fun secondsLeft(step: PaymentStep, nowS: Long): Long? = step.expiresAt?.let { (it - nowS).coerceAtLeast(0) }

    /** Share of the time left for the bar under the countdown (0..1), or null. */
    fun progress(step: PaymentStep, nowS: Long): Float? {
        val total = step.secondsTotal ?: return null
        val left = secondsLeft(step, nowS) ?: return null
        return if (total <= 0) null else (left.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Whether the card shows the "expired, get a new one?" choice: the server said so, or the countdown reached zero here. */
    fun expired(step: PaymentStep, nowS: Long): Boolean =
        step.state == "expired" || (step.state == "waiting" && step.expiresAt != null && nowS >= step.expiresAt)

    /** A tap at pixel ([x], [y]) of a picture shown [w] x [h] px: fractions 0..1 of it, or null outside. */
    fun tapFraction(x: Float, y: Float, w: Int, h: Int): Pair<Float, Float>? {
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x > w || y > h) return null
        return (x / w) to (y / h)
    }

    /** The dots under the keypad picture: one per tap counted, at most 8 shown. */
    fun dots(taps: Int): String = "●".repeat(taps.coerceIn(0, 8))

    private const val MAX = 12
}

/**
 * The "check" of a human check: [vendor] (cloudflare, recaptcha, …), [name] (the server's words for it, in the owner's language) and
 * [kind]: "interstitial" or "widget" (a person check), or "verification": a sign-in's second step (approve the sign-in on your phone,
 * or type the code), where [name] is that step ("휴대폰에서 로그인을 승인해 주세요").
 */
data class CheckInfo(val vendor: String = "", val name: String = "", val kind: String = "", val step: String = "") {
    val isVerification: Boolean get() = kind == KIND_VERIFICATION
    /** A sign-in verification the owner approves on the phone (an app's push), not a code typed in the page */
    val isApproveStep: Boolean get() = isVerification && step == STEP_APPROVE

    companion object {
        const val KIND_VERIFICATION = "verification"
        const val STEP_APPROVE = "approve"

        fun parse(e: kotlinx.serialization.json.JsonElement?): CheckInfo? {
            val o = e as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            return CheckInfo(s("vendor"), s("name"), s("kind"), s("step"))
        }
    }
}
