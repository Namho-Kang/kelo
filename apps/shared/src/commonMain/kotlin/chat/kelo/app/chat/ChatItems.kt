package chat.kelo.app.chat

import chat.kelo.app.wallet.CardSendError

/** What the chat shows, one entry per row. Text that comes from the app itself is a kind, resolved in the UI. */
sealed interface ChatItem {
    val key: String

    /** A message; [id] is the server's (null while a reply is still being written here). [at]: when it was written (unix ms; the
     *  server's created_at, or this device's clock for a message made here; 0 = not known, then no time line shows for it). */
    data class Msg(
        override val key: String, val mine: Boolean, val text: String, val id: Long? = null, val replies: ReplySet? = null, val at: Long = 0L,
        val reaction: String = "",
        /** The morning briefing's card: shown instead of the plain bubble */
        val briefing: BriefingCard? = null,
        val comparison: ComparisonCard? = null,
        /** Screenshots Kelo showed with this reply, under its text */
        val images: List<ChatImage> = emptyList(),
        /** The user's message: the files sent with it (pictures as thumbnails, other files as chips) */
        val files: List<MessageFile> = emptyList(),
        /** The text is the app's own sentence ("read and summarise …"): only [files] show */
        val autoText: Boolean = false,
    ) : ChatItem
    /**
     * The reply needs this phone's place (location_request): Share / Not now, or the phone's settings when the owner refused before
     * ([settings]). [state]: waiting, sending (this phone is reading and sending it), allowed, refused, expired, cancelled.
     */
    data class LocationAsk(
        override val key: String, val approvalId: String, val reason: String, val settings: Boolean, val state: String = WAITING,
    ) : ChatItem {
        val open: Boolean get() = state == WAITING || state == SENDING

        companion object {
            const val WAITING = "waiting"
            const val SENDING = "sending"
            const val ALLOWED = "allowed"
            const val REFUSED = "refused"
            const val EXPIRED = "expired"
        }
    }
    /**
     * [label]: the words shown for the tool (the server's tool_labels); an older server sends none, then the raw [name] shows.
     * [group] / [done]: calls of one group in a row fold into one chip ([ChatReducer.foldTools]) whose words, once more than one is
     * done, are [done] with {n} filled in. [detail]: what the call was about, in a few words (a search, a site), for the folded list.
     * [members]: on a folded chip only, the calls it stands for (oldest first); empty on a plain chip.
     */
    data class Tool(
        override val key: String, val id: String, val name: String, val state: ToolState, val service: String? = null, val label: String = "",
        val group: String = "", val done: String = "", val detail: String = "", val members: List<Tool> = emptyList(),
    ) : ChatItem {
        val shown: String get() = label.ifEmpty { name }
        val groupKey: String get() = group.ifEmpty { name }
        val folded: Boolean get() = members.size > 1
    }
    /**
     * A tool waiting for the user's yes or no. [answer] is what this phone said (true = allowed), or what the tool's result shows
     * when the question was answered somewhere else. [expired]: nobody answered and it can no longer be answered (the reply ended,
     * which makes the server deny it, or the server's wait ran out).
     */
    data class Approval(
        override val key: String, val id: String, val name: String, val args: String, val detail: String?,
        val answer: Boolean? = null, val expired: Boolean = false,
        val label: String = "", val payment: PaymentInfo? = null, val card: ApprovalCardInfo? = null,
        /** An end-to-end card on Allow: how sending it from this phone went */
        val send: CardSend = CardSend.Idle,
        /** The card may offer "keep allowing this in that scheduled job" */
        val canRememberSchedule: Boolean = false,
        val site: String = "",
        val canRemember: Boolean = false,
        /** The readable pairs the card shows (never the raw arguments) */
        val lines: List<ApprovalLine> = emptyList(),
        /** The server's whole question for the title ("로그인할까요?"), or "" (then it is built from [shown]) */
        val ask: String = "",
        /** The setup card of a "when X happens, do Y" task (its own layout: trigger, action, limits, the card's auto-pay limit) */
        val grant: chat.kelo.app.agent.TaskGrantAsk? = null,
        /** Asked in a run of a task marked urgent at setup; [missed]: what a late answer would miss */
        val urgent: Boolean = false,
        val missed: String = "",
        /** A permission for many items: the items it covers (behind "see the list") */
        val items: List<String> = emptyList(),
        /** The site's saved payment PIN that Allow also releases from this phone */
        val pin: PinAsk? = null,
    ) : ChatItem {
        val answered: Boolean get() = answer != null
        val open: Boolean get() = answer == null && !expired
        val shown: String get() = label.ifEmpty { name }
        /** Allow must also send the card from this phone (after the owner's check). */
        val needsCard: Boolean get() = card?.needsCard == true
    }
    /**
     * The assistant's browser tab: ONE live row per reply and tab, updated in place as the agent works (the newest step's [status],
     * site and picture; [id] is the newest step's tool call). [recent]: the last few different pictures, oldest first (the newest is
     * [image]). [tab]: the tab the pictures came from ("" = an older server: this phone's own tab). [hosts]: the sites it went
     * through in this reply, in order.
     */
    data class Browser(
        override val key: String, val id: String, val title: String, val host: String, val url: String, val status: String,
        val image: String?, val width: Int = 0, val height: Int = 0, val recent: List<String> = emptyList(), val tab: String = "",
        val hosts: List<String> = emptyList(),
    ) : ChatItem
    /** An allowed payment waits for this phone's card until [expiresAt] (unix seconds); [ended]: the reply is over, nothing waits. */
    data class CardNeeded(
        override val key: String, val approvalId: String, val card: CardBrief, val site: String, val amountText: String,
        val expiresAt: Long, val send: CardSend = CardSend.Idle, val ended: Boolean = false,
    ) : ChatItem
    /** "자동결제했어요": a payment that went ahead under the user's auto-pay rules. */
    data class PaymentAuto(
        override val key: String, val site: String, val amountText: String, val cardLabel: String,
        /** The order's items and where it goes, when the server sent them */
        val order: PaymentInfo? = null,
    ) : ChatItem
    /**
     * The agent asks where an order goes ([ask]): the address form, prefilled. Once answered it folds into one line ([doneLabel] ·
     * [doneLine]); a form whose time ran out or whose reply ended is EXPIRED; one answered elsewhere (another phone) is CLOSED.
     */
    data class ShippingForm(
        override val key: String, val ask: chat.kelo.app.wallet.ShippingAsk, val state: ShippingFormState = ShippingFormState.OPEN,
        val doneLabel: String = "", val doneLine: String = "",
    ) : ChatItem {
        val open: Boolean get() = state == ShippingFormState.OPEN
    }
    /**
     * A secure entry the agent asked for, right where it asked ([request]: a key, a login or a card, with where it will be sent).
     * It stays open after the reply ends (the server keeps it until it is answered); once answered it folds into one line.
     */
    data class Secret(override val key: String, val request: chat.kelo.app.chat.vault.SecretRequest) : ChatItem
    data class Vault(override val key: String, val name: String, val state: CardState = CardState.OPEN) : ChatItem
    data class Connect(override val key: String, val service: String, val state: CardState = CardState.OPEN) : ChatItem
    data class KeySaved(override val key: String, val name: String, val hosts: List<String>) : ChatItem
    data class Notice(override val key: String, val notice: ChatNotice) : ChatItem
    data class Error(override val key: String, val error: ChatError) : ChatItem
}

enum class ToolState { RUNNING, OK, ERROR, DENIED }

/** Where an in-chat address form is: waiting for the answer, sent, cancelled, timed out (or the reply ended), answered elsewhere. */
enum class ShippingFormState { OPEN, DONE, CANCELLED, EXPIRED, CLOSED }

/** What a follow-up button under a message did. */
enum class ActionOutcome { APPROVED, DENIED, RESUMED, DROPPED, SNOOZED, SIGNED_IN, GONE, RESOLVED }

/** Sending an end-to-end card for a payment: not yet, on its way (the check, then the upload), there, or why not. */
sealed interface CardSend {
    data object Idle : CardSend
    data object Working : CardSend
    data object Sent : CardSend
    data class Failed(val error: CardSendError) : CardSend
}

/**
 * A secure or connect card in the chat. OPEN takes input; DONE (the user told the agent it is done) and CANCELLED fold into one line;
 * EXPIRED: the assistant asked again for the same thing further down, so this older card is only a record.
 */
enum class CardState { OPEN, DONE, CANCELLED, EXPIRED }

sealed interface ChatNotice {
    data class Fallback(val model: String, val reason: String) : ChatNotice
    data object Retry : ChatNotice
    data object Reconnecting : ChatNotice
    data class Uploaded(val name: String) : ChatNotice
}

sealed interface ChatError {
    data object StepLimit : ChatError
    data class Message(val text: String) : ChatError
    /** A 400 when starting a reply: no usable model */
    data class PickModel(val detail: String) : ChatError
    data object Network : ChatError
    /** A shared file the server does not take (it reads PDF, Word and Excel) */
    data class Unsupported(val name: String) : ChatError
    /** A shared file that could not be read */
    data object ShareFailed : ChatError
    /** The camera permission was refused, so no photo can be taken */
    data object CameraDenied : ChatError
    /** No camera app takes the picture */
    data object CameraMissing : ChatError
}

enum class PhaseKind { WAITING, THINKING, WRITING, TOOL, APPROVAL, SWITCHING }

/** What the status line says while a reply is in progress; [since] (ms) drives the elapsed seconds. */
data class Phase(val kind: PhaseKind, val label: String? = null, val since: Long)

object ChatReducer {
    /** Names a vault entry quoted in request_login / request_api_access output, e.g. 'login-example-com'. */
    private val VAULT_NAME = Regex("'([a-z0-9][a-z0-9._-]{1,62}[a-z0-9])'")

    /** A finished tool's chip: denied only when the user said no (approved == false), otherwise error or ok. */
    fun toolState(ev: TurnEvent.ToolResult): ToolState = when {
        ev.approved == false -> ToolState.DENIED
        ev.isError -> ToolState.ERROR
        else -> ToolState.OK
    }

    fun vaultName(output: String): String? = VAULT_NAME.find(output)?.groupValues?.get(1)

    /** How long a reply may stay in its reasoning before the status line says it is a long think (the server's own line agrees) */
    const val LONG_THINKING_MS = 60_000L

    /** Still reasoning [LONG_THINKING_MS] after the reasoning began: "오래 생각하고 있어요" instead of "생각 중" */
    fun longThinking(p: Phase, now: Long): Boolean = p.kind == PhaseKind.THINKING && now - p.since >= LONG_THINKING_MS

    /** The status line after [ev]; the same phase (kind and label) keeps its start time. */
    fun phase(current: Phase?, ev: TurnEvent, now: Long): Phase? {
        fun to(kind: PhaseKind, label: String? = null) =
            if (current != null && current.kind == kind && current.label == label) current else Phase(kind, label, now)
        return when (ev) {
            is TurnEvent.Thinking -> to(PhaseKind.THINKING)
            is TurnEvent.Text -> to(PhaseKind.WRITING)
            is TurnEvent.ToolStarted -> if (ev.hidden) to(PhaseKind.THINKING) else to(PhaseKind.TOOL, ev.label.ifEmpty { ev.name })
            is TurnEvent.ToolResult -> to(PhaseKind.WAITING)
            is TurnEvent.ApprovalRequest -> to(PhaseKind.APPROVAL, ev.label.ifEmpty { ev.name })
            is TurnEvent.Fallback -> to(PhaseKind.SWITCHING, ev.model)
            is TurnEvent.Retry -> to(PhaseKind.THINKING)
            else -> current
        }
    }

    /** The list after one event (a message it starts has no time). */
    fun apply(items: List<ChatItem>, ev: TurnEvent, newKey: () -> String): List<ChatItem> = apply(items, ev, 0L, newKey)

    /** The list after one event; [now] (unix ms) dates a message this event starts. */
    fun apply(items: List<ChatItem>, ev: TurnEvent, now: Long, newKey: () -> String): List<ChatItem> {
        val next = items.toMutableList()
        when (ev) {
            is TurnEvent.Text -> {
                val last = next.lastOrNull()
                if (last is ChatItem.Msg && !last.mine) next[next.lastIndex] = last.copy(text = last.text + ev.text)
                else next += ChatItem.Msg(newKey(), mine = false, text = ev.text, at = now)
            }
            // a hidden call (the assistant reading its own notes) shows nothing at all
            is TurnEvent.ToolStarted -> if (ev.hidden) return items else next += ChatItem.Tool(
                newKey(), ev.id, ev.name, ToolState.RUNNING, ev.service, ev.label, ev.group, ev.done, detailOf(ev.arguments),
            )
            is TurnEvent.ToolResult -> {
                // the newest chip with this id that is still running (some CLIs reuse ids from one step to the next)
                val i = next.indexOfLast { it is ChatItem.Tool && it.id == ev.id && it.state == ToolState.RUNNING }
                val chip = if (i >= 0) (next[i] as ChatItem.Tool).copy(state = toolState(ev)) else null
                if (chip != null) next[i] = chip
                // the assistant asked the user to connect a service: the card appears right here
                if (ev.name == "connect_service" && !ev.isError && !ev.output.contains("already connected")) {
                    chip?.service?.let { service ->
                        supersede(next) { it is ChatItem.Connect && it.service == service }
                        next += ChatItem.Connect(newKey(), service)
                    }
                }
                // a key the assistant found on a page went into the keychain: a small card says so (the value never reaches the app)
                if (ev.name == "save_found_secret" && !ev.isError) {
                    KeySavedRules.parse(ev.output)?.let { s ->
                        supersede(next) { it is ChatItem.KeySaved && it.name == s.name }
                        next += ChatItem.KeySaved(newKey(), s.name, s.hosts)
                    }
                }
                // the assistant asked for a login or a key: the secure form appears right here (the value goes to the vault, not the
                // chat). A login is no longer asked about first (approved is null then); only a refused request shows no form.
                if ((ev.name == "request_login" || ev.name == "request_api_access") && ev.approved != false && !ev.isError) {
                    // a server that shows secure entries sent a secret_request for it already: no second form
                    vaultName(ev.output)?.takeIf { name -> next.none { it is ChatItem.Secret && it.request.name == name } }?.let { name ->
                        supersede(next) { it is ChatItem.Vault && it.name == name }
                        next += ChatItem.Vault(newKey(), name)
                    }
                }
                // the address question's tool finished while its form is still open here: it was answered elsewhere (or gave up)
                val f = next.indexOfLast { it is ChatItem.ShippingForm && it.open && it.ask.id == ev.id }
                if (f >= 0 && ev.id.isNotEmpty()) next[f] = (next[f] as ChatItem.ShippingForm).copy(state = ShippingFormState.CLOSED)
                // a question still open here was settled elsewhere (another phone) or ran out: its tool's result says which
                if (ev.approved != null) {
                    val q = next.indexOfLast { it is ChatItem.Approval && it.open && it.name == ev.name }
                    if (q >= 0) {
                        val a = next[q] as ChatItem.Approval
                        next[q] = if (ev.approved) a.copy(answer = true) else a.copy(expired = true)
                    }
                }
            }
            // a screenshot Kelo shows: under the reply's text so far, or as the reply's first row when nothing was written yet
            is TurnEvent.Image -> {
                val last = next.lastOrNull()
                if (last is ChatItem.Msg && !last.mine && last.briefing == null) next[next.lastIndex] = last.copy(images = last.images + ev.image)
                else next += ChatItem.Msg(newKey(), mine = false, text = "", images = listOf(ev.image), at = now)
            }
            is TurnEvent.Reaction -> {
                val i = Reactions.target(next, ev.messageId)
                if (i < 0) return items
                next[i] = (next[i] as ChatItem.Msg).copy(reaction = ev.emoji)
            }
            is TurnEvent.ApprovalRequest ->
                next += ChatItem.Approval(
                    newKey(), ev.approvalId, ev.name, TurnEvent.prettyArgs(ev.arguments), ev.detail, label = ev.label, payment = ev.payment, card = ev.card,
                    canRememberSchedule = ev.canRememberSchedule && ev.payment == null,
                    site = ev.site, canRemember = ev.canRemember && ev.payment == null && ev.grant == null,
                    lines = ev.lines, ask = ev.ask,
                    grant = ev.grant, urgent = ev.urgent, missed = ev.missed, items = ev.items, pin = ev.pin,
                )
            is TurnEvent.Browser -> {
                // one live card per reply and tab: a new step, or another site in the same tab, updates it; only a new tab adds one
                val start = next.indexOfLast { it is ChatItem.Msg && it.mine } + 1
                val i = (next.lastIndex downTo start).firstOrNull { j ->
                    val b = next[j] as? ChatItem.Browser
                    b != null && (b.id == ev.id || b.tab == ev.tab || (ev.tab.isEmpty() && b.tab.isNotEmpty()) || (b.tab.isEmpty() && ev.tab.isNotEmpty()))
                } ?: -1
                val old = next.getOrNull(i) as? ChatItem.Browser
                val hosts = old?.hosts.orEmpty().let { h -> if (ev.host.isEmpty() || ev.host in h) h else h + ev.host }
                val card = ChatItem.Browser(
                    old?.key ?: newKey(), ev.id, ev.title, ev.host, ev.url, ev.status,
                    ev.image ?: old?.image, if (ev.image != null) ev.width else old?.width ?: 0, if (ev.image != null) ev.height else old?.height ?: 0,
                    recent = filmstrip(old?.recent.orEmpty(), ev.image), tab = ev.tab.ifEmpty { old?.tab.orEmpty() }, hosts = hosts,
                )
                if (old != null) next[i] = card else next += card
            }
            is TurnEvent.CardNeeded -> {
                // this phone already sent the card on Allow: nothing more to ask
                if (next.any { it is ChatItem.Approval && it.id == ev.approvalId && it.send == CardSend.Sent }) return items
                val i = next.indexOfFirst { it is ChatItem.CardNeeded && it.approvalId == ev.approvalId }
                if (i >= 0) next[i] = (next[i] as ChatItem.CardNeeded).copy(expiresAt = ev.expiresAt)
                else next += ChatItem.CardNeeded(newKey(), ev.approvalId, ev.card, ev.site, ev.amountText, ev.expiresAt)
            }
            is TurnEvent.PaymentAuto -> next += ChatItem.PaymentAuto(newKey(), ev.site, ev.amountText, ev.card?.label.orEmpty(), ev.payment)
            is TurnEvent.SecretRequested -> {
                val req = ev.request
                if (!req.fromAgent) return items
                val i = next.indexOfFirst { it is ChatItem.Secret && it.request.id == req.id }
                if (i >= 0) {
                    val old = next[i] as ChatItem.Secret
                    next[i] = old.copy(request = chat.kelo.app.chat.vault.SecretRules.merge(old.request, req))
                } else if (req.open) {
                    // a newer entry for the same thing: the older one folds (the server replaced it)
                    for (j in next.indices) {
                        val o = next[j]
                        if (o is ChatItem.Secret && o.request.open && o.request.kind == req.kind && o.request.name == req.name) {
                            next[j] = o.copy(request = o.request.copy(state = chat.kelo.app.chat.vault.SecretRequest.STATE_REPLACED))
                        }
                    }
                    next += ChatItem.Secret(newKey(), req)
                } else {
                    return items
                }
            }
            is TurnEvent.ShippingForm -> {
                // the same form again (a resend): it keeps its row and its state
                val i = next.indexOfFirst { it is ChatItem.ShippingForm && it.ask.formId == ev.ask.formId }
                if (i >= 0) next[i] = (next[i] as ChatItem.ShippingForm).copy(ask = ev.ask)
                else next += ChatItem.ShippingForm(newKey(), ev.ask)
            }
            is TurnEvent.Fallback -> next += ChatItem.Notice(newKey(), ChatNotice.Fallback(ev.model, ev.reason))
            is TurnEvent.Retry -> next += ChatItem.Notice(newKey(), ChatNotice.Retry)
            is TurnEvent.Reconnecting -> next += ChatItem.Notice(newKey(), ChatNotice.Reconnecting)
            // a newer server says what follows in a line of its own (and a Continue button, or a background job): no error bubble
            is TurnEvent.StepLimit -> if (ev.next.isNotEmpty()) return items else next += ChatItem.Error(newKey(), ChatError.StepLimit)
            is TurnEvent.Error -> next += ChatItem.Error(newKey(), ChatError.Message(ev.message))
            is TurnEvent.LocationRequest -> {
                val i = next.indexOfFirst { it is ChatItem.LocationAsk && it.approvalId == ev.approvalId }
                if (i >= 0) {
                    val old = next[i] as ChatItem.LocationAsk
                    // the server's end state wins; a repeated "waiting" does not undo this phone's "sending"
                    if (ev.state != ChatItem.LocationAsk.WAITING) next[i] = old.copy(state = ev.state)
                } else if (ev.state == ChatItem.LocationAsk.WAITING) {
                    next += ChatItem.LocationAsk(newKey(), ev.approvalId, ev.reason, ev.settings)
                } else {
                    return items
                }
            }
            is TurnEvent.UserMessage -> {
                // the message this reply answers: the newest of the user's own (the bubble this phone just added)
                val i = next.indexOfLast { it is ChatItem.Msg && it.mine }
                if (i < 0) return items
                val m = next[i] as ChatItem.Msg
                next[i] = m.copy(id = m.id ?: ev.messageId.takeIf { it > 0 }, files = ev.attachments.ifEmpty { m.files }, autoText = ev.autoText)
            }
            is TurnEvent.Comparison -> return items
            else -> return items
        }
        return next
    }

    /** What a call was about, in a few words, from its arguments (a search, a site, a name), for a folded chip's list. */
    fun detailOf(args: kotlinx.serialization.json.JsonObject): String {
        fun str(k: String) = (args[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        for (k in listOf("query", "q", "url", "site", "subject", "name", "service", "path", "title", "uid")) {
            val v = str(k)
            if (v.isEmpty()) continue
            val shown = if (k == "url" || k == "site") v.substringAfter("://").substringBefore('/').removePrefix("www.") else v
            return if (shown.length > 60) shown.take(59) + "…" else shown
        }
        val many = args["uids"] as? kotlinx.serialization.json.JsonArray
        return if (many != null && many.isNotEmpty()) many.size.toString() else ""
    }

    /**
     * The rows the chat draws: tool chips of one group in a row fold into ONE chip that grows in place ([ChatItem.Tool.members]; its
     * key is the first call's, so the row stays put). Browser steps are not shown as chips at all in a reply that has a browser card
     * (the card shows them); without one they fold like any group. Everything else passes through as it is.
     */
    fun foldTools(items: List<ChatItem>): List<ChatItem> {
        if (items.none { it is ChatItem.Tool }) return items
        val out = ArrayList<ChatItem>(items.size)
        var replyStart = 0
        val cardInReply = BooleanArray(items.size)
        // which rows belong to a reply that shows a browser card (a reply runs from the user's message to the next one)
        for (i in items.indices) {
            val it = items[i]
            if (it is ChatItem.Msg && it.mine || i == items.lastIndex) {
                val end = if (it is ChatItem.Msg && it.mine) i else i + 1
                if ((replyStart until end).any { j -> items[j] is ChatItem.Browser }) for (j in replyStart until end) cardInReply[j] = true
                replyStart = i + 1
            }
        }
        for (i in items.indices) {
            val it = items[i]
            if (it !is ChatItem.Tool) { out += it; continue }
            if (groupKeyOf(it) == BROWSER_GROUP && cardInReply[i]) continue
            // the chip this call joins: the last row, or the last chip before approval cards that came between the calls (adding
            // two events asks twice: one chip, the cards stay under it in their order)
            var j = out.lastIndex
            while (j >= 0 && out[j] is ChatItem.Approval) j--
            val last = out.getOrNull(j) as? ChatItem.Tool
            if (last != null && groupKeyOf(it) == groupKeyOf(last)) {
                val members = last.members.ifEmpty { listOf(last) } + it
                out[j] = last.copy(members = members, done = it.done.ifEmpty { last.done }, label = it.label.ifEmpty { last.label })
            } else {
                out += it
            }
        }
        return out
    }

    private const val BROWSER_GROUP = "browser"
    private fun groupKeyOf(t: ChatItem.Tool): String = if (t.group.isEmpty() && t.name.startsWith("browser_")) BROWSER_GROUP else t.groupKey

    /**
     * How many things the calls of a folded chip that did their work were about: the same photo (or page, message, event) looked at
     * twice counts once (a retry, or another model taking over the reply, repeats the call). Calls with nothing to tell them apart
     * each count.
     */
    fun doneCount(t: ChatItem.Tool): Int {
        val ok = t.members.ifEmpty { listOf(t) }.filter { it.state == ToolState.OK }
        val (named, bare) = ok.partition { it.detail.isNotEmpty() }
        return named.distinctBy { it.name + "\u0000" + it.detail }.size + bare.size
    }

    /** The words of a folded chip that is done: the server's words with the count, or "label · n" from an older server. */
    fun foldedText(t: ChatItem.Tool, n: Int): String =
        if (t.done.contains("{n}")) t.done.replace("{n}", n.toString()) else "${t.shown} · $n"

    /** Older open cards for the same thing become a record only. */
    private fun supersede(items: MutableList<ChatItem>, same: (ChatItem) -> Boolean) {
        for (i in items.indices) {
            when (val it = items[i]) {
                is ChatItem.Vault -> if (it.state == CardState.OPEN && same(it)) items[i] = it.copy(state = CardState.EXPIRED)
                is ChatItem.Connect -> if (it.state == CardState.OPEN && same(it)) items[i] = it.copy(state = CardState.EXPIRED)
                else -> {}
            }
        }
    }

    /** This phone answered the question [approvalId]. An expired or already answered one stays as it is. */
    fun answer(items: List<ChatItem>, approvalId: String, ok: Boolean): List<ChatItem> =
        items.map { if (it is ChatItem.Approval && it.id == approvalId && it.open) it.copy(answer = ok) else it }

    /**
     * The reply is over (done, failed or stopped): the server denies every question still waiting, so they expire; no card is waited
     * for any more; and the one-line status rows of answered secure entries ("로그인 정보를 넣었어요: …", cancelled, gone, replaced)
     * leave the chat, as they do after a reload (the stored history has no such row). An entry still open stays: the server keeps it.
     */
    fun endTurn(items: List<ChatItem>): List<ChatItem> =
        if (items.none {
                (it is ChatItem.Approval && it.open) || (it is ChatItem.CardNeeded && !it.ended && it.send != CardSend.Sent) ||
                    (it is ChatItem.ShippingForm && it.open) || isSettledEntry(it) || (it is ChatItem.LocationAsk && it.open)
            }) items
        else items.filterNot(::isSettledEntry).map {
            when {
                it is ChatItem.LocationAsk && it.open -> it.copy(state = ChatItem.LocationAsk.EXPIRED)
                it is ChatItem.Approval && it.open -> it.copy(expired = true)
                it is ChatItem.ShippingForm && it.open -> it.copy(state = ShippingFormState.EXPIRED)
                it is ChatItem.CardNeeded && !it.ended && it.send != CardSend.Sent -> it.copy(ended = true)
                else -> it
            }
        }

    /** A secure entry that is no longer open: only its one status line is left ([endTurn] removes it). */
    fun isSettledEntry(it: ChatItem): Boolean = it is ChatItem.Secret && !it.request.open

    /** Every answered entry's line out of the rows (no reply runs here that would end and clear them). */
    fun dropSettled(items: List<ChatItem>): List<ChatItem> = if (items.none(::isSettledEntry)) items else items.filterNot(::isSettledEntry)

    /**
     * The rows after the entry [done] (its new state) was answered here. [followsHere] (see SecretRules.followsHere): its line
     * folds in place, or joins the rows when only the server's list showed it ([placeAnswered]), and the reply's end clears it.
     * Otherwise the line goes at once, and is never added back (an answer that arrives after the reply already ended).
     */
    fun afterAnswer(items: List<ChatItem>, done: chat.kelo.app.chat.vault.SecretRequest, followsHere: Boolean): List<ChatItem> = when {
        !followsHere -> items.filterNot { it is ChatItem.Secret && it.request.id == done.id }
        items.any { it is ChatItem.Secret && it.request.id == done.id } -> settleSecret(items, done.id, done.state, done.result)
        else -> placeAnswered(items, done)
    }

    /**
     * The address form [formId] was answered here ([ShippingFormState.DONE] with the label and one-line address it went with),
     * cancelled, or found gone (EXPIRED). Only an open form changes, except that this phone's own answer (DONE or CANCELLED) also
     * replaces CLOSED: the agent resumes at once, so its tool's result can arrive before the answer's 200 does.
     */
    fun settleShipping(items: List<ChatItem>, formId: String, state: ShippingFormState, label: String = "", line: String = ""): List<ChatItem> =
        items.map {
            val mine = state == ShippingFormState.DONE || state == ShippingFormState.CANCELLED
            if (it is ChatItem.ShippingForm && it.ask.formId == formId && (it.open || (mine && it.state == ShippingFormState.CLOSED)))
                it.copy(state = state, doneLabel = label, doneLine = line)
            else it
        }

    /**
     * The briefing cards on screen after GET /api/messages again ([fresh]: message id -> that message's card now): a card already shown
     * takes its new state (its hours line goes once answered on another device); every other row, and the order, stay as they are.
     */
    fun withBriefings(items: List<ChatItem>, fresh: Map<Long, BriefingCard?>): List<ChatItem> =
        items.map {
            if (it is ChatItem.Msg && it.briefing != null && it.id != null && fresh.containsKey(it.id) && fresh[it.id] != it.briefing) {
                it.copy(briefing = fresh[it.id] ?: it.briefing)
            } else it
        }

    /**
     * The location cards still waiting on the server (GET /api/approvals/pending, items of type location_request): after a reload or a
     * restart the chat shows them again at its end. A card shown here that the server no longer lists has ended (answered on
     * another device, or run out) unless this phone is sending it right now; [busy]: a reply this phone follows will say how it
     * ended, so open cards are left to it.
     */
    fun withPendingLocations(items: List<ChatItem>, pending: List<TurnEvent.LocationRequest>, busy: Boolean): List<ChatItem> {
        val waiting = pending.filter { it.state == ChatItem.LocationAsk.WAITING && it.approvalId.isNotEmpty() }
        val ids = waiting.mapTo(HashSet()) { it.approvalId }
        val shown = items.mapNotNullTo(HashSet()) { (it as? ChatItem.LocationAsk)?.approvalId }
        var changed = false
        val out = items.map {
            if (!busy && it is ChatItem.LocationAsk && it.state == ChatItem.LocationAsk.WAITING && it.approvalId !in ids) {
                changed = true
                it.copy(state = ChatItem.LocationAsk.EXPIRED)
            } else it
        }.toMutableList()
        for (p in waiting) if (p.approvalId !in shown) {
            out += ChatItem.LocationAsk("loc:" + p.approvalId, p.approvalId, p.reason, p.settings)
            changed = true
        }
        return if (changed) out else items
    }

    /** The location card [approvalId] in [state] (this phone is sending, or what the server said). Only an open card changes. */
    fun locationState(items: List<ChatItem>, approvalId: String, state: String): List<ChatItem> =
        items.map { if (it is ChatItem.LocationAsk && it.approvalId == approvalId && it.open) it.copy(state = state) else it }

    /** The secure entry [requestId] was answered here (or found gone): its card folds with [state] (and [result] when saved). */
    fun settleSecret(items: List<ChatItem>, requestId: String, state: String, result: chat.kelo.app.chat.vault.SecretResult? = null): List<ChatItem> =
        items.map {
            if (it is ChatItem.Secret && it.request.id == requestId && it.request.open) it.copy(request = it.request.copy(state = state, result = result ?: it.request.result))
            else it
        }

    /**
     * The rows the chat shows: [items] plus the OPEN secure entries from GET /api/secret-requests ([pending]) that no row shows yet,
     * each right under the agent message it belongs to ([chat.kelo.app.chat.vault.SecretRequest.messageId]), or at the end while that
     * message is not on screen (the reply asking is still being written, or it is older than the loaded page). A reload, another
     * phone or a lost connection thus still shows them. Requests the Keychain made from a waiting vault item never show here.
     */
    fun withPending(items: List<ChatItem>, pending: List<chat.kelo.app.chat.vault.SecretRequest>): List<ChatItem> =
        place(items, pending.filter { it.open })

    /**
     * An entry answered here that no row showed yet (it came from the server's list): it joins the rows (under its message, else
     * at the end), so a reply that carries on starts below it. Only [settleSecret]'s caller uses this; [withPending] never places
     * an answered entry, so one can never stick at the end of the chat (an answered entry whose asking reply was never stored, a
     * failed purchase run, used to stay above the composer for good).
     */
    fun placeAnswered(items: List<ChatItem>, answered: chat.kelo.app.chat.vault.SecretRequest): List<ChatItem> = place(items, listOf(answered))

    private fun place(items: List<ChatItem>, pending: List<chat.kelo.app.chat.vault.SecretRequest>): List<ChatItem> {
        val shown = items.mapNotNullTo(HashSet()) { (it as? ChatItem.Secret)?.request?.id }
        val extra = pending.filter { it.fromAgent && it.id !in shown }
        if (extra.isEmpty()) return items
        val ids = items.mapNotNullTo(HashSet()) { (it as? ChatItem.Msg)?.takeIf { m -> !m.mine }?.id }
        val (anchored, loose) = extra.partition { it.messageId != null && it.messageId in ids }
        val under = anchored.groupBy { it.messageId }
        val out = ArrayList<ChatItem>(items.size + extra.size)
        for (it in items) {
            out += it
            val id = (it as? ChatItem.Msg)?.id ?: continue
            under[id]?.forEach { r -> out += ChatItem.Secret("secret:" + r.id, r) }
        }
        loose.sortedBy { it.createdAt }.forEach { r -> out += ChatItem.Secret("secret:" + r.id, r) }
        return out
    }

    /** How sending the card for [approvalId] goes, on its question and on a card_needed row for it. */
    fun cardSend(items: List<ChatItem>, approvalId: String, send: CardSend): List<ChatItem> = items.map {
        when {
            it is ChatItem.Approval && it.id == approvalId -> it.copy(send = send)
            it is ChatItem.CardNeeded && it.approvalId == approvalId -> it.copy(send = send)
            else -> it
        }
    }

    /** The newest browser status while a reply runs (for the line under the agent's name), or null. */
    /** How many pictures a browser row keeps for its filmstrip. */
    const val FILMSTRIP = 3

    /** The filmstrip after a new picture: added at the end unless it is the same as the last, at most [FILMSTRIP]. */
    fun filmstrip(recent: List<String>, image: String?): List<String> =
        if (image == null || recent.lastOrNull() == image) recent else (recent + image).takeLast(FILMSTRIP)

    /** What a finished step means for the line that replaces the buttons. */
    fun outcome(step: chat.kelo.app.followup.FollowupStep, result: chat.kelo.app.followup.StepResult): ActionOutcome? = when (result) {
        chat.kelo.app.followup.StepResult.FAILED -> null
        chat.kelo.app.followup.StepResult.GONE -> ActionOutcome.GONE
        chat.kelo.app.followup.StepResult.DONE -> when (step) {
            is chat.kelo.app.followup.FollowupStep.Answer -> if (step.approve) ActionOutcome.APPROVED else ActionOutcome.DENIED
            is chat.kelo.app.followup.FollowupStep.ResumeWorker, is chat.kelo.app.followup.FollowupStep.Retry -> ActionOutcome.RESUMED
            is chat.kelo.app.followup.FollowupStep.Drop -> ActionOutcome.DROPPED
            is chat.kelo.app.followup.FollowupStep.Snooze -> ActionOutcome.SNOOZED
            is chat.kelo.app.followup.FollowupStep.OpenLogin -> ActionOutcome.SIGNED_IN
        }
    }

    /**
     * The line under a message whose follow-up is no longer open: what this phone did, else what the server says happened
     * (dropped, or settled). Null while it is open and nothing was pressed here.
     */
    fun closedOutcome(item: chat.kelo.app.followup.Followup, local: ActionOutcome?): ActionOutcome? = when {
        local != null -> local
        item.open -> null
        item.status == chat.kelo.app.followup.Followup.DROPPED -> ActionOutcome.DROPPED
        else -> ActionOutcome.RESOLVED
    }

    /** The user finished ([CardState.DONE]) or closed ([CardState.CANCELLED]) the card [key]; only an open card changes. */
    fun settleCard(items: List<ChatItem>, key: String, state: CardState): List<ChatItem> = items.map {
        when {
            it.key != key -> it
            it is ChatItem.Vault && it.state == CardState.OPEN -> it.copy(state = state)
            it is ChatItem.Connect && it.state == CardState.OPEN -> it.copy(state = state)
            else -> it
        }
    }
}
