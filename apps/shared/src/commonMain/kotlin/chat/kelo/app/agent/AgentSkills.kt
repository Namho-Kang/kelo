package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Custom skills waiting for the owner's approval, and "Save as skill" on a finished task or job. */
class AgentSkills(private val host: AgentHost) {
    private val _skills = MutableStateFlow(Loadable<SkillProposals>())
    /** Custom skills waiting for the owner's approval (shown on the Records page, next to the learned rules) */
    val skillProposals: StateFlow<Loadable<SkillProposals>> = _skills.asStateFlow()

    // ---- custom skills waiting for approval (GET /api/skills/proposals) and "Save as skill" on a finished task or job
    fun loadSkillProposals() {
        val job = host.refresh(_skills) {
            try {
                host.api().get<SkillProposals>("/api/skills/proposals")
            } catch (e: ApiError) {
                if (e.status == 404) SkillProposals(available = false) else throw e
            }
        }
        // while a draft is being written, look again now and then, so the draft and the button's state show up without a reload
        if (job != null && skillPoll?.isActive != true) {
            skillPoll = host.scope.launch {
                job.join()
                repeat(SkillRules.POLL_TIMES) {
                    if (_skills.value.data?.drafting.isNullOrEmpty()) return@launch
                    delay(SkillRules.POLL_MILLIS)
                    host.refresh(_skills) { host.api().get<SkillProposals>("/api/skills/proposals") }?.join()
                }
            }
        }
    }
    private var skillPoll: kotlinx.coroutines.Job? = null

    /**
     * Approve (written as shown and used from now on) or discard a proposal; an edited skill is approved only as the owner saw it
     * (its hash goes along). [done] runs with true on success; on failure the list loads again and the reason is shown.
     */
    fun answerSkill(p: SkillProposal, approve: Boolean, done: (Boolean) -> Unit) {
        host.scope.launch {
            try {
                if (approve) {
                    host.api().post<JsonElement>(SkillRules.approvePath(p), p.contentHash?.let { h -> buildJsonObject { put("expected_hash", h) } })
                } else {
                    host.api().post<JsonElement>(SkillRules.discardPath(p))
                }
                done(true)
                delay(SkillRules.DONE_MILLIS)
                _skills.update { l -> l.copy(data = l.data?.let { SkillRules.without(it, p.id) }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                done(false)
                if (e.status != 404) host.sayError(e)
                loadSkillProposals()
            }
        }
    }

    /**
     * Ask Kelo to draft a skill from a finished chat task or background job; the draft waits for approval on the Records page.
     * [where]: the names of the tab and page it will be on (마이, 기록), as the app shows them.
     */
    fun saveAsSkill(taskId: Long? = null, workerId: String? = null, where: List<String> = emptyList()) {
        host.scope.launch {
            try {
                host.api().post<JsonElement>(SkillRules.draftPath(taskId, workerId))
                host.say(Res.string.skill_save_started, *where.toTypedArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                when (e.status) {
                    409 -> host.say(Res.string.skill_save_busy)
                    404 -> host.say(Res.string.skill_save_unavailable)
                    503 -> host.say(Res.string.skill_save_no_model)
                    else -> host.sayError(e)
                }
            }
            loadSkillProposals()
        }
    }
}
