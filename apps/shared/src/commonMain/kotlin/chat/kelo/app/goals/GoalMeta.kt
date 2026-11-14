package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A goal's optional due date and progress. The server keeps a goal's title, tag (its "category"), note and status only, so these
 * two ride at the end of the note on a line of their own, "[due:2026-11-20 progress:40]": plain enough for the agent to read
 * (it sees the notes of the goals being tracked), and stripped from the note wherever the app shows it.
 */
data class GoalMeta(val note: String, val due: LocalDate? = null, val progress: Int? = null)

object GoalMetaCodec {
    private val LINE = Regex("""\n?\[due:([0-9-]*)(?: progress:(\d{1,3}))?\]\s*$|\n?\[progress:(\d{1,3})\]\s*$""")

    fun parse(note: String): GoalMeta {
        val m = LINE.find(note) ?: return GoalMeta(note.trim())
        val due = m.groupValues[1].takeIf { it.isNotEmpty() }?.let {
            try { LocalDate.parse(it) } catch (e: DateTimeParseException) { null }
        }
        val progress = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()?.coerceIn(0, 100)
        return GoalMeta(note.substring(0, m.range.first).trim(), due, progress)
    }

    fun encode(meta: GoalMeta): String {
        val text = meta.note.trim()
        val parts = buildList {
            meta.due?.let { add("due:$it") }
            meta.progress?.let { add("progress:${it.coerceIn(0, 100)}") }
        }
        if (parts.isEmpty()) return text
        val line = "[" + parts.joinToString(" ") + "]"
        return if (text.isEmpty()) line else "$text\n$line"
    }
}

/** The goals list's order and small rules, apart from the UI. */
object GoalsRules {
    /** In progress: the nearest due date first, then those without one (newest first, as the server sends them). */
    fun ordered(goals: List<Goal>): List<Goal> {
        val withIndex = goals.withIndex().toList()
        return withIndex.sortedWith(compareBy<IndexedValue<Goal>> { GoalMetaCodec.parse(it.value.note).due ?: LocalDate.MAX }.thenBy { it.index })
            .map { it.value }
    }

    fun overdue(due: LocalDate?, today: LocalDate): Boolean = due != null && due < today

    /** The free-form tag as the server takes it (at most 40 characters). */
    fun tag(text: String): String = text.trim().take(40)

    /** The goal's live state on its row and page: Kelo works on it, or waits for the user's answer (only while nothing runs). */
    fun activity(g: Goal): GoalActivity? = when {
        g.isDone -> null
        g.working -> GoalActivity.WORKING
        g.asking -> GoalActivity.ASKING
        else -> null
    }

    /** How soon the goals are fetched again while on screen: soon while Kelo works on one (it ends, or asks), less often while one waits
     *  for an answer (it may come from another device or the chat), else only when the screen comes back. */
    fun refreshMs(goals: List<Goal>): Long? = when {
        goals.any { activity(it) == GoalActivity.WORKING } -> WORKING_REFRESH_MS
        goals.any { activity(it) == GoalActivity.ASKING } -> ASKING_REFRESH_MS
        else -> null
    }

    const val WORKING_REFRESH_MS = 3_000L
    const val ASKING_REFRESH_MS = 15_000L

    /** Removing a goal first asks "turn off its N schedules too?" only when some linked schedules are on (an older server says 0). */
    fun asksAboutSchedules(g: Goal): Boolean = g.schedulesOn > 0

    /** The real delete after the undo window: the linked schedules deleted (to the archive) or kept running off the goal (keep = what older apps did). */
    fun deletePath(id: Long, deleteSchedules: Boolean): String = "/api/goals/$id?schedules=${if (deleteSchedules) "delete" else "keep"}"
}

enum class GoalActivity { WORKING, ASKING }
