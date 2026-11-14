package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Secure entries waiting for a value and the agent's follow-ups: what waits for the owner's word besides approvals (the 답이 필요해요 rows). */
class AgentFollowups(private val host: AgentHost, private val jobs: AgentJobs, private val permissions: AgentPermissions) {
    private val _entries = MutableStateFlow(Loadable<List<chat.kelo.app.chat.vault.SecretRequest>>())
    /** Secure entries waiting for a value (GET /api/secret-requests): rows in 답이 필요해요; an older server without them counts as none */
    val entries: StateFlow<Loadable<List<chat.kelo.app.chat.vault.SecretRequest>>> = _entries.asStateFlow()

    fun loadEntries() {
        host.refresh(_entries) {
            try { chat.kelo.app.chat.vault.SecretCalls.list(host.api()) } catch (e: ApiError) { if (e.status == 404) emptyList() else throw e }
        }
    }

    /** 그만두기 on an entry's row: the agent is told and does not ask again; the row and the badge go at once. */
    fun cancelEntry(r: chat.kelo.app.chat.vault.SecretRequest, onClosed: (String) -> Unit = {}) {
        _entries.update { l -> l.copy(data = l.data?.filterNot { it.id == r.id }) }
        onClosed(r.kind)
        host.scope.launch {
            try {
                chat.kelo.app.chat.vault.SecretCalls.cancel(host.api(), r.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status != 404) host.sayError(e)
            } finally {
                loadEntries()
            }
        }
    }

    private val _followups = MutableStateFlow(Loadable<chat.kelo.app.followup.FollowupList>())
    /** Things the agent asks about again (GET /api/followups); the ones on hold go in 답이 필요해요 */
    val followups: StateFlow<Loadable<chat.kelo.app.followup.FollowupList>> = _followups.asStateFlow()
    private val _followupBusy = MutableStateFlow(setOf<String>())
    /** Follow-ups with a button being done right now */
    val followupBusy: StateFlow<Set<String>> = _followupBusy.asStateFlow()

    /** A server without follow-ups (404) counts as none. */
    fun loadFollowups() { host.refresh(_followups) { chat.kelo.app.followup.FollowupCalls(host.api()).list() } }

    /** One button of a follow-up card (다시 시작, 그만 묻기, …); the list loads again after it, and the jobs too when one went on. */
    fun runFollowup(f: chat.kelo.app.followup.Followup, step: chat.kelo.app.followup.FollowupStep) {
        if (f.id in _followupBusy.value || step is chat.kelo.app.followup.FollowupStep.OpenLogin) return
        _followupBusy.update { it + f.id }
        host.scope.launch {
            try {
                val (result, detail) = chat.kelo.app.followup.FollowupCalls(host.api()).run(step)
                when (result) {
                    chat.kelo.app.followup.StepResult.DONE -> host.say(when (step) {
                        is chat.kelo.app.followup.FollowupStep.Drop -> Res.string.followup_done_dropped
                        is chat.kelo.app.followup.FollowupStep.Snooze -> Res.string.followup_done_snoozed
                        is chat.kelo.app.followup.FollowupStep.Answer -> if (step.approve) Res.string.followup_done_approved else Res.string.followup_done_denied
                        else -> Res.string.followup_done_resumed
                    })
                    chat.kelo.app.followup.StepResult.GONE -> {}   // already closed: the list refresh drops it
                    chat.kelo.app.followup.StepResult.FAILED -> if (detail.isBlank()) host.say(Res.string.connect_failed) else host.messages.tryEmit(AgentMessage(Res.string.agent_error_detail, detail = detail))
                }
            } catch (e: ApiError) {
                host.sayError(e)
            } finally {
                _followupBusy.update { it - f.id }
                loadFollowups()
                if (step is chat.kelo.app.followup.FollowupStep.ResumeWorker || step is chat.kelo.app.followup.FollowupStep.Retry) { jobs.loadWorkers(); permissions.loadPerms() }
                if (step is chat.kelo.app.followup.FollowupStep.Answer) permissions.loadPerms()
            }
        }
    }

    /** The chat history was cleared on the server: the follow-ups go at once. */
    fun forgetHistory() {
        _followups.update { l -> l.copy(data = l.data?.copy(items = emptyList())) }
    }
}
