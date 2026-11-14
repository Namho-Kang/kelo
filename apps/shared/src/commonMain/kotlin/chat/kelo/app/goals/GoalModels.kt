package chat.kelo.app.goals

import chat.kelo.app.core.Goal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/goals/{id}: one goal with everything Kelo and the user built around it. */
@Serializable
data class GoalWorkspace(
    val goal: Goal,
    val facts: List<GoalFact> = emptyList(),
    val steps: List<GoalStep> = emptyList(),
    val metrics: List<GoalMetric> = emptyList(),
    val log: List<GoalLogEntry> = emptyList(),
    val documents: List<GoalDocument> = emptyList(),
    val schedules: List<GoalSchedule> = emptyList(),
)

@Serializable
data class GoalFact(val key: String, val value: String = "", val skipped: Boolean = false, val source: String = "agent")

@Serializable
data class GoalStep(
    val id: Long,
    val title: String,
    val detail: String = "",
    /** todo | doing | done */
    val status: String = "todo",
    /** user | agent */
    val owner: String = "user",
    /** YYYY-MM-DD */
    val due: String? = null,
) {
    val isDone: Boolean get() = status == "done"
    val mine: Boolean get() = owner != "agent"
}

@Serializable
data class GoalMetricValue(val value: Double, val note: String = "", val at: Double = 0.0)

@Serializable
data class GoalMetric(
    val id: Long,
    val name: String,
    val unit: String = "",
    val start: Double? = null,
    val target: Double? = null,
    val values: List<GoalMetricValue> = emptyList(),
)

@Serializable
data class GoalLogEntry(val id: Long, val at: Double = 0.0, val text: String = "", val kind: String = "note", val by: String = "agent")

@Serializable
data class GoalDocument(val id: Long, val path: String, val title: String = "", val exists: Boolean = true)

@Serializable
data class GoalSchedule(val id: Long, val name: String = "", val description: String = "", val enabled: Boolean = true)

/** GET /api/goals/suggestions items. */
@Serializable
data class GoalSuggestion(val id: Long, val title: String, val reason: String = "", val evidence: String = "", val source: String = "nightly")

@Serializable
data class GoalSuggestions(val items: List<GoalSuggestion> = emptyList())

/** Small rules of the goal page, apart from the UI. */
object GoalDetailRules {
    /** A number as people write it: 72, 72.5 (never 72.0 or 1.0E3). */
    fun number(v: Double?): String {
        if (v == null) return "-"
        return if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString()
        else java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun withUnit(v: Double?, unit: String): String = if (unit.isBlank() || v == null) number(v) else number(v) + " " + unit

    /** The user's own steps can be ticked; Kelo's show their state only. */
    fun canTick(step: GoalStep): Boolean = step.mine

    /** The status a tick moves a step to. */
    fun toggled(step: GoalStep): String = if (step.isDone) "todo" else "done"

    /** Points of a small chart, scaled to 0..1 on both axes (oldest first); the target, when known, is part of the range. */
    fun chart(m: GoalMetric): List<Pair<Float, Float>> {
        val vs = m.values.map { it.value }
        if (vs.isEmpty()) return emptyList()
        val all = vs + listOfNotNull(m.target)
        val lo = all.min()
        val hi = all.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        return vs.mapIndexed { i, v -> (if (vs.size == 1) 0.5f else i.toFloat() / (vs.size - 1)) to ((v - lo) / span).toFloat() }
    }

    /** Where the target line sits on the same 0..1 scale as [chart], or null. */
    fun targetLevel(m: GoalMetric): Float? {
        val t = m.target ?: return null
        val vs = m.values.map { it.value }
        if (vs.isEmpty()) return null
        val all = vs + t
        val span = (all.max() - all.min()).takeIf { it > 0 } ?: return 0.5f
        return ((t - all.min()) / span).toFloat()
    }
}
