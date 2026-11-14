package chat.kelo.app.chat

import chat.kelo.app.agent.PendingApproval
import chat.kelo.app.wallet.CardSendError

/*
 * Questions waiting on the user that the chat is not following live: a background job's approval (the reply only handed the job
 * off), a scheduled run's, or a reply this phone stopped following. They come from GET /api/approvals/pending, are shown at the
 * bottom of the chat as live cards with the same answers as the 작업 page, and leave when answered there or here. Kelo has one
 * conversation per assistant, so every waiting approval belongs in it; a card the followed reply already shows is not shown twice.
 */

/** What asked, for the small line on top of the card. */
enum class AskOrigin { JOB, SCHEDULE, AUTO, CHAT }

/** How a question answered from the chat ended. SOLVED / SKIPPED: a human check (solved in the live view, or the site skipped). PROGRESSED: a sign-in's step done, the sign-in goes on. */
enum class AskEnd { ALLOWED, ALWAYS, KEPT_FOR_SCHEDULE, DENIED, GONE, SOLVED, SKIPPED, PROGRESSED }

/**
 * One waiting question in the chat. [working]: the answer (or the card for a payment) is on its way. [end]: answered here; its short
 * line stays while [endedAfter] (the chat's newest row then) is still the newest row, so it goes once the conversation moves on.
 */
data class ChatAsk(
    val approval: PendingApproval,
    val working: Boolean = false,
    val cardError: CardSendError? = null,
    val end: AskEnd? = null,
    val endedAfter: String? = null,
) {
    val id: String get() = approval.id
    val open: Boolean get() = end == null
}

object ChatAsks {
    fun origin(a: PendingApproval): AskOrigin = when {
        !a.workerId.isNullOrBlank() || a.source == "worker" -> AskOrigin.JOB
        a.source.startsWith("schedule") -> AskOrigin.SCHEDULE
        a.unattended -> AskOrigin.AUTO
        else -> AskOrigin.CHAT
    }

    /**
     * A fresh pending list joined with what the chat shows: new questions join, open ones answered elsewhere (or expired) leave, one
     * whose answer is on its way stays until that settles, ones answered here keep their line while [newestKey] has not changed.
     * Oldest question first.
     */
    fun merge(
        current: List<ChatAsk>, pending: List<PendingApproval>, newestKey: String?, resolved: Map<String, String> = emptyMap(),
    ): List<ChatAsk> {
        val ids = pending.mapTo(HashSet()) { it.id }
        // a human check that ended on the server (solved in the live view, skipped elsewhere, ran out) folds to its outcome here
        val folded = current.map { a ->
            val out = resolved[a.id]
            if (a.open && a.id !in ids && out != null) a.copy(working = false, cardError = null, end = resolvedEnd(out), endedAfter = newestKey) else a
        }
        val known = folded.associateBy { it.id }
        val live = pending.map { p -> known[p.id]?.let { if (it.open) it.copy(approval = p) else it } ?: ChatAsk(p) }
        val rest = folded.filter { it.id !in ids && ((it.open && it.working) || (!it.open && it.endedAfter == newestKey)) }
        return (live + rest).sortedBy { it.approval.askedAt }
    }

    /** What the chat draws: not a question the followed reply shows as its own card, and an ended line only until the chat moves on. */
    fun visible(asks: List<ChatAsk>, items: List<ChatItem>): List<ChatAsk> {
        if (asks.isEmpty()) return asks
        val own = items.mapNotNullTo(HashSet()) { (it as? ChatItem.Approval)?.id }
        val newest = items.lastOrNull()?.key
        return asks.filter { it.id !in own && (it.open || it.endedAfter == newest) }
    }

    fun working(asks: List<ChatAsk>, id: String, on: Boolean, error: CardSendError? = null): List<ChatAsk> =
        asks.map { if (it.id == id) it.copy(working = on, cardError = error) else it }

    fun ended(asks: List<ChatAsk>, id: String, end: AskEnd, newestKey: String?): List<ChatAsk> =
        asks.map { if (it.id == id) it.copy(working = false, cardError = null, end = end, endedAfter = newestKey) else it }

    /** A hand-over's outcome from the server as the card's last line. */
    fun resolvedEnd(outcome: String): AskEnd = when (outcome) {
        "solved" -> AskEnd.SOLVED
        "skipped" -> AskEnd.SKIPPED
        "progressed" -> AskEnd.PROGRESSED
        else -> AskEnd.GONE
    }

    /** How an answer given here ended: a human check's "skip" is SKIPPED (its "done" from an older card, SOLVED). */
    fun answerEnd(a: PendingApproval, approve: Boolean, rememberSite: Boolean, rememberSchedule: Boolean): AskEnd = when {
        a.isHumanCheck -> if (approve) (if (a.isVerification) AskEnd.PROGRESSED else AskEnd.SOLVED) else AskEnd.SKIPPED
        approve -> allowEnd(a, rememberSite, rememberSchedule)
        else -> AskEnd.DENIED
    }

    /** The server's whole question for an approval card's title, or null (an older server: build it from the label). */
    fun question(ask: String): String? = ask.trim().ifEmpty { null }

    /** How an allow ended: remembered for the site or the scheduled job only when the card offered it. */
    fun allowEnd(a: PendingApproval, rememberSite: Boolean, rememberSchedule: Boolean): AskEnd = when {
        rememberSite && a.canRemember -> AskEnd.ALWAYS
        rememberSchedule && a.canRememberSchedule -> AskEnd.KEPT_FOR_SCHEDULE
        else -> AskEnd.ALLOWED
    }

    /**
     * When the pending list is asked again: the status poll's count of waiting approvals or of unseen notifications changed (an
     * unattended approval adds one). Null before the first poll.
     */
    fun pollKey(s: AgentStatus?): Pair<Int, Int>? = s?.let { it.pendingApprovals to it.unseenNotifications }

    /** Nothing waits and nothing open is shown: the list need not be asked for, it is simply empty. */
    fun skipFetch(s: AgentStatus?, asks: List<ChatAsk>): Boolean = s != null && s.pendingApprovals == 0 && asks.none { it.open }
}
