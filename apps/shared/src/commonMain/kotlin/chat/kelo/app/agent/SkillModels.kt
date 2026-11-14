package chat.kelo.app.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Custom skills waiting for the owner (src/assistant/custom_skills.py, "Custom skills" in server.py): what Kelo proposed or drafted
 * from a finished task, and skills whose files changed outside the app. Nothing here is used by Kelo until the owner approves it on
 * the Records page. Unknown fields are ignored and missing ones take the defaults, so an older or newer server never breaks the page.
 */

/** One line of a change: add | del | same | gap (lines left out between two changes) | file (which file the next lines are in). */
@Serializable
data class SkillDiffLine(val op: String = "same", val text: String = "")

/** Where a proposal came from: chat | job (Kelo proposed it while working) | draft ("save as skill") | disk (changed outside the app). */
@Serializable
data class SkillOrigin(
    val type: String = "chat",
    @SerialName("task_id") val taskId: Long? = null,
    @SerialName("worker_id") val workerId: String? = null,
)

/** GET /api/skills/proposals → items[]. */
@Serializable
data class SkillProposal(
    val id: String,
    /** The skill's id (lower-case words joined by '-'); never shown */
    val skill: String = "",
    /** What the app calls it, in the owner's language (an older server sends none: [SkillRules.name] falls back to the id in words) */
    val name: String = "",
    /** new | change | edited */
    val kind: String = NEW,
    val description: String = "",
    /** The full text as it will be saved (Markdown, without the front matter) */
    val text: String = "",
    val diff: List<SkillDiffLine> = emptyList(),
    val reason: String = "",
    val origin: SkillOrigin = SkillOrigin(),
    /** Text from web pages, mail or files reached the run that proposed it */
    @SerialName("outside_text") val outsideText: Boolean = false,
    @SerialName("created_at") val createdAt: Double? = null,
    /** An edited skill: the files the owner looked at (sent back with the approval) */
    @SerialName("content_hash") val contentHash: String? = null,
    /** An edited skill that was never approved (a folder put there by hand): discarding removes it */
    @SerialName("never_approved") val neverApproved: Boolean = false,
    @SerialName("secrets_removed") val secretsRemoved: Int = 0,
) {
    companion object {
        const val NEW = "new"
        const val CHANGE = "change"
        const val EDITED = "edited"
    }
}

@Serializable
data class SkillDrafting(@SerialName("task_id") val taskId: Long? = null, @SerialName("worker_id") val workerId: String? = null)

@Serializable
data class SkillProposals(
    val items: List<SkillProposal> = emptyList(),
    val drafting: List<SkillDrafting> = emptyList(),
    /** False when the server has no custom skills (an older server answers 404) */
    val available: Boolean = true,
)

/** The small rules of the skills awaiting approval and of "Save as skill", apart from the UI. */
object SkillRules {
    /** A skill's name to show: its id in words ("renew-library-book" → "Renew library book"); never the raw id. */
    fun title(skill: String): String =
        skill.split('-', '_').filter { it.isNotBlank() }.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { skill }

    /** The name to show: the server's name for it, else its id in words. */
    fun name(p: SkillProposal): String = p.name.trim().ifBlank { title(p.skill) }

    /** Lines of text or of a change shown before "Show all". */
    const val PREVIEW_LINES = 12

    /** While a draft is being written the list is read again this often, at most this many times. */
    const val POLL_MILLIS = 4_000L
    const val POLL_TIMES = 45

    /** How long "approved" / "discarded" stays on a card before it leaves the list. */
    const val DONE_MILLIS = 2_500L

    /** Changes made outside the app first (Kelo is not using those skills now), then the newest proposals. */
    fun ordered(items: List<SkillProposal>): List<SkillProposal> =
        items.filter { it.id.isNotBlank() && it.skill.isNotBlank() }
            .sortedWith(compareBy<SkillProposal> { if (it.kind == SkillProposal.EDITED) 0 else 1 }.thenByDescending { it.createdAt ?: 0.0 })

    /** The list without one entry (once answered). */
    fun without(items: SkillProposals, id: String): SkillProposals = items.copy(items = items.items.filterNot { it.id == id })

    /** A change is shown as its lines; a new skill as its full text. */
    fun showsDiff(p: SkillProposal): Boolean = p.kind != SkillProposal.NEW && p.diff.isNotEmpty()

    /** (added, removed) line counts of a change. */
    fun counts(p: SkillProposal): Pair<Int, Int> = p.diff.count { it.op == "add" } to p.diff.count { it.op == "del" }

    /** The lines shown: all of them when [all], else the first [PREVIEW_LINES]. */
    fun <T> preview(lines: List<T>, all: Boolean): List<T> = if (all) lines else lines.take(PREVIEW_LINES)

    /** Whether there is more than the preview. */
    fun <T> hasMore(lines: List<T>): Boolean = lines.size > PREVIEW_LINES

    /** The text split into lines for the preview (blank lines kept, trailing ones dropped). */
    fun textLines(text: String): List<String> = text.trimEnd().lines()

    /** Whether "Save as skill" shows on a finished chat task or background job: only when it ended well and is not being drafted. */
    fun canSave(status: String): Boolean = status == "done"

    /** Whether a draft from this task or job is being written now. */
    fun drafting(p: SkillProposals?, taskId: Long? = null, workerId: String? = null): Boolean =
        p?.drafting?.any { (taskId != null && it.taskId == taskId && it.workerId == null) || (workerId != null && it.workerId == workerId) } == true

    /** The approve request's path and whether it carries the hash the owner looked at (an edited skill). */
    fun approvePath(p: SkillProposal): String = "/api/skills/proposals/${chat.kelo.app.core.KeloApi.segment(p.id)}/approve"
    fun discardPath(p: SkillProposal): String = "/api/skills/proposals/${chat.kelo.app.core.KeloApi.segment(p.id)}/discard"
    fun draftPath(taskId: Long? = null, workerId: String? = null): String =
        if (workerId != null) "/api/workers/${chat.kelo.app.core.KeloApi.segment(workerId)}/skill-draft" else "/api/tasks/$taskId/skill-draft"
}
