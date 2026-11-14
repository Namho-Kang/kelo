package chat.kelo.app.chat

import chat.kelo.app.my.ContactHoursRules
import chat.kelo.app.res.*
import org.jetbrains.compose.resources.StringResource

/** Where a tap on a briefing item goes (the chat screen does it: [BriefingRules.tap]). */
sealed interface BriefingTap {
    /** The chat at the follow-up's own message */
    data class ChatAt(val messageId: Long) : BriefingTap
    /** The 작업 panel's list of what waits (a follow-up without a message of its own) */
    data object Waiting : BriefingTap
    /** A background job's task */
    data class Task(val id: Long) : BriefingTap
    /** The goals tab */
    data object Goals : BriefingTap
    /** The phone's calendar at that moment (unix ms) */
    data class Event(val atMs: Long) : BriefingTap
    /** A question about that mail written into the composer (nothing is sent until the owner sends it) */
    data class Mail(val subject: String) : BriefingTap
    /** One of Kelo's suggested next steps, a question to the owner, written into the composer the same way (nothing is sent) */
    data class Suggest(val text: String) : BriefingTap
}

/** A small reference under a "worth noticing" note: [label] names the card item, [tap] is what a tap on that item does (null: not clickable). */
data class BriefingChip(val ref: String, val label: String, val tap: BriefingTap?)

/** The briefing card's small rules, apart from the UI. */
object BriefingRules {
    /** Where an item opens; null: it has no place of its own (a promise noted in memory). */
    fun tap(open: BriefingOpen?): BriefingTap? = when (open?.kind) {
        "followup" -> open.messageId?.let { BriefingTap.ChatAt(it) } ?: BriefingTap.Waiting
        "job" -> open.taskId?.let { BriefingTap.Task(it) } ?: BriefingTap.Waiting
        "goal" -> BriefingTap.Goals
        "event" -> open.start?.let { BriefingTap.Event((it * 1000).toLong()) }
        "mail" -> open.subject?.takeIf { it.isNotBlank() }?.let { BriefingTap.Mail(it) }
        else -> null
    }

    /** The day in short: the headline, else the older one-line text; ONE text, never both (the server copies the headline into the line). */
    fun headline(card: BriefingCard): String? =
        card.headline?.trim()?.takeIf { it.isNotEmpty() } ?: card.line?.trim()?.takeIf { it.isNotEmpty() }

    /** The model's notes / next steps without an empty text; everything else is kept as the server sent it. */
    fun connections(card: BriefingCard): List<BriefingLink> = card.connections.filter { it.text.isNotBlank() }
    fun suggestions(card: BriefingCard): List<BriefingLink> = card.suggestions.filter { it.text.isNotBlank() }

    /** The card's sections, in order, that have something: headline, notes, weather, today, ahead (the coming-up line), missed, steps, hours. */
    fun sections(card: BriefingCard): List<String> = buildList {
        if (headline(card) != null) add("headline")
        if (connections(card).isNotEmpty()) add("notes")
        if (card.weather != null) add("weather")
        if (card.events.isNotEmpty()) add("today")
        if (card.ahead.isNotEmpty()) add("ahead")
        if (card.missed.isNotEmpty()) add("missed")
        if (suggestions(card).isNotEmpty()) add("steps")
        if (hoursShown(card.hours)) add("hours")
    }

    /**
     * The chips under a note: one per ref that exists on the card, in the note's order, each id once. Labelled "Weather" ([weather]) for
     * the weather, "10:00 Design review" for an event ([clock] writes the time), the title for an ahead / missed item. A ref that
     * points at nothing (or an item without a title) is skipped; an item with no place of its own gives a chip with a null tap.
     */
    fun chips(card: BriefingCard, refs: List<String>, weather: String, clock: (Double) -> String): List<BriefingChip> =
        refs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().mapNotNull { ref ->
            val w = card.weather
            if (w != null && w.id.isNotEmpty() && w.id == ref) return@mapNotNull BriefingChip(ref, weather, null)
            card.events.firstOrNull { it.id.isNotEmpty() && it.id == ref }?.let { e ->
                val label = listOf(clock(e.start), e.title.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                return@mapNotNull if (e.title.isBlank()) null else BriefingChip(ref, label, tap(e.open))
            }
            card.ahead.firstOrNull { it.id.isNotEmpty() && it.id == ref }?.let { a ->
                return@mapNotNull if (a.title.isBlank()) null else BriefingChip(ref, a.title.trim(), tap(a.open))
            }
            card.missed.firstOrNull { it.id.isNotEmpty() && it.id == ref }?.let { m ->
                return@mapNotNull if (m.title.isBlank()) null else BriefingChip(ref, m.title.trim(), tap(m.open))
            }
            null
        }

    /** A big event's days in the coming-up line: "11월 12일" or "11월 12일 – 11월 14일", each day written by [day]. */
    fun aheadDays(a: BriefingAhead, day: (java.time.LocalDate) -> String): String {
        val first = runCatching { java.time.LocalDate.parse(a.first) }.getOrNull() ?: return ""
        val last = runCatching { java.time.LocalDate.parse(a.last) }.getOrNull()
        return if (last == null || !last.isAfter(first)) day(first) else day(first) + " – " + day(last)
    }

    /** The coming-up line: "상하이 여행 11월 12일 – 11월 14일 · 출국 KE893 11월 16일". */
    fun aheadLine(card: BriefingCard, day: (java.time.LocalDate) -> String): String =
        card.ahead.joinToString(" · ") { a -> listOf(a.title, aheadDays(a, day)).filter { it.isNotBlank() }.joinToString(" ") }

    /** The label of a missed item's kind (followup, job, approval, login, mail, goal, promise). */
    fun kindLabel(kind: String): StringResource = when (kind) {
        "job" -> Res.string.briefing_kind_job
        "approval" -> Res.string.briefing_kind_approval
        "login" -> Res.string.briefing_kind_login
        "mail" -> Res.string.briefing_kind_mail
        "goal" -> Res.string.briefing_kind_goal
        "promise" -> Res.string.briefing_kind_promise
        else -> Res.string.briefing_kind_followup
    }

    /** The hours line can be shown: an id and a real start time. */
    fun hoursShown(h: BriefingHours?): Boolean = h != null && h.id.isNotBlank() && ContactHoursRules.minutes(h.wakeStart) != null
}
