package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Approvals and permission rules: waiting approvals, the history, answering one, revoking a rule or a task's permission. */
class AgentPermissions(private val host: AgentHost) {
    private val _perms = MutableStateFlow(Loadable<Permissions>())
    val perms: StateFlow<Loadable<Permissions>> = _perms.asStateFlow()

    fun loadPerms() { host.refresh(_perms) {
        val api = host.api()
        val pending = PendingApproval.parseList(api.request("GET", "/api/approvals/pending"))
        val rules = api.get<RuleList>("/api/approvals/rules").items
        val history = api.get<ApprovalHistory>("/api/approvals/history?limit=${AgentViewModel.PAGE}")
        val grants = try { api.get<TaskPermissionList>("/api/task-permissions").items } catch (e: ApiError) { if (e.status == 404) emptyList() else throw e }
        Permissions(pending, rules, history.items, history.nextBefore, taskGrants = grants)
    } }

    fun loadMoreHistory() {
        val cur = _perms.value.data ?: return
        val before = cur.historyNext ?: return
        host.scope.launch {
            runCatching { host.api().get<ApprovalHistory>("/api/approvals/history?limit=${AgentViewModel.PAGE}&before=$before") }.onSuccess { h ->
                _perms.update { l -> l.copy(data = l.data?.let { d -> d.copy(history = d.history + h.items, historyNext = h.nextBefore) }) }
            }
        }
    }

    /**
     * Answers a waiting approval ([remember]: allow this kind of step on this site from now on). [beforeAllow] runs first on Allow
     * (a payment with an end-to-end card sends the card from this phone); when it fails the question stays open.
     */
    fun answer(
        a: PendingApproval, approve: Boolean, remember: Boolean = false, beforeAllow: (suspend () -> Boolean)? = null,
        rememberSchedule: Boolean = false,
    ) {
        val cur = _perms.value.data ?: return
        if (a.id in cur.answering) return
        _perms.update { l -> l.copy(data = l.data?.copy(answering = l.data.answering + a.id)) }
        host.scope.launch {
            try {
                if (approve && beforeAllow != null && !beforeAllow()) return@launch
                host.api().post<JsonElement>("/api/approvals/${KeloApi.segment(a.id)}", buildJsonObject {
                    put("approve", approve)
                    if (approve && remember && a.canRemember) put("remember", "site")
                    else if (approve && rememberSchedule && a.canRememberSchedule) put("remember", "schedule")
                })
                if (approve) host.platform.afterApproval()   // a text, call or calendar change may now wait for this phone
                host.say(when {
                    !approve -> Res.string.agent_approval_denied
                    remember && a.canRemember -> Res.string.agent_approval_always
                    rememberSchedule && a.canRememberSchedule -> Res.string.approval_kept_for_schedule
                    else -> Res.string.agent_approval_allowed
                })
            } catch (e: ApiError) {
                if (e.status == 404) host.say(Res.string.agent_approval_gone) else host.sayError(e)
            } finally {
                _perms.update { l -> l.copy(data = l.data?.copy(answering = l.data.answering - a.id)) }
                _perms.update { it.copy(loading = false) }
                loadPerms()
            }
        }
    }

    fun revokeRule(r: ApprovalRule) {
        _perms.update { l -> l.copy(data = l.data?.let { d -> d.copy(rules = d.rules.filter { it.id != r.id }) }) }
        host.scope.launch {
            try {
                host.api().delete<JsonElement>("/api/approvals/rules/${r.id}")
                host.say(Res.string.agent_rule_revoked, r.site)
            } catch (e: ApiError) {
                if (e.status != 404) { host.sayError(e); loadPerms() }
            }
        }
    }

    /** Stops a task's permission at once: its next such step asks again. */
    fun revokeTaskPermission(g: TaskPermission) {
        _perms.update { l -> l.copy(data = l.data?.let { d -> d.copy(taskGrants = d.taskGrants.filter { it.id != g.id }) }) }
        host.scope.launch {
            try {
                host.api().delete<JsonElement>("/api/task-permissions/${KeloApi.segment(g.id)}")
                host.say(Res.string.work_grant_revoked)
            } catch (e: ApiError) {
                if (e.status != 404) { host.sayError(e); loadPerms() }
            }
        }
    }

    /** The chat history was cleared on the server: the approval history and waiting approvals go at once. */
    fun forgetHistory() {
        _perms.update { l -> l.copy(data = l.data?.copy(pending = emptyList(), history = emptyList(), historyNext = null, answering = emptySet())) }
    }
}
