package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The lists the chat does not follow by itself, asked for again when the status poll says they changed: waiting approvals and
 * questions, payment steps, secure entries, briefing cards and follow-ups.
 */
internal class ChatSync(private val host: ChatHost) {
    // ---- questions from work the chat is not following (a background job, a scheduled run)
    /** GET /api/approvals/pending joined into the chat's waiting cards; nothing is asked when the poll says nothing waits. */
    fun refreshAsks(force: Boolean = false) {
        if (!force && ChatAsks.skipFetch(host.activity?.status?.value, host.state.value.asks)) {
            // not fetched: the location cards stay as they are (only a fetched list says one has ended)
            host.state.update { it.copy(asks = ChatAsks.merge(it.asks, emptyList(), it.items.lastOrNull()?.key)) }
            return
        }
        host.scope.launch {
            var locations = emptyList<TurnEvent.LocationRequest>()
            val pending = try {
                host.api().request("GET", "/api/approvals/pending").let {
                    // location cards are not approvals: listed so a reloaded chat shows the waiting one again
                    locations = ((it as? kotlinx.serialization.json.JsonObject)?.get("items") as? kotlinx.serialization.json.JsonArray).orEmpty()
                        .mapNotNull { e -> TurnEvent.parse(e) as? TurnEvent.LocationRequest }
                    chat.kelo.app.agent.PendingApproval.parseList(it) to chat.kelo.app.agent.PendingApproval.parseResolved(it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch   // not reachable now: the cards stay as they are until the next poll
            }
            host.state.update {
                it.copy(asks = ChatAsks.merge(it.asks, pending.first, it.items.lastOrNull()?.key, pending.second),
                    items = ChatReducer.withPendingLocations(it.items, locations, it.busy))
            }
        }
    }

    /** The follow-ups and the messages they belong to (also after a reply, and when a follow-up notification is opened). */
    fun loadFollowups() {
        host.scope.launch { runCatching { loadFollowupsNow() } }
    }

    suspend fun loadFollowupsNow() {
        val list = try { chat.kelo.app.followup.FollowupCalls(host.api()).list() } catch (e: ApiError) { return }
        host.state.update { it.copy(followups = chat.kelo.app.followup.FollowupRules.byMessage(list.items)) }
    }

    // ---- the briefing cards on screen (server briefing.py): asked for again when GET /api/agent/status "briefing_rev" changes
    fun refreshBriefings() {
        if (host.state.value.items.none { it is ChatItem.Msg && it.briefing != null }) return
        host.scope.launch {
            runCatching {
                val ms = host.api().get<List<StoredMessage>>("/api/messages?limit=${ChatViewModel.PAGE}")
                val fresh = ms.associate { it.id to it.briefing }
                host.state.update { it.copy(items = ChatReducer.withBriefings(it.items, fresh)) }
            }
        }
    }

    // ---- secure entries the agent asked for (a key, a login, a card; server secret_requests.py)
    fun refreshSecrets() {
        host.scope.launch { runCatching { refreshSecretsNow() } }
        refreshPaymentSteps()
    }

    // ---- payment steps (server payment_steps.py): approve in another app, a one-time code, the page's PIN keypad in the card
    fun refreshPaymentSteps() {
        host.scope.launch {
            val fresh = try { PaymentStepCalls(host.api()).list() } catch (e: Exception) { if (e is CancellationException) throw e; return@launch }
            host.state.update { it.copy(paymentSteps = PaymentStepRules.refresh(it.paymentSteps, fresh)) }
        }
    }

    suspend fun refreshSecretsNow() {
        val fresh = try { chat.kelo.app.chat.vault.SecretCalls.list(host.api()) } catch (e: ApiError) { return }
        // the server lists only open entries: answered ones leave the kept list, and their lines leave the rows unless a reply runs
        // here (its end clears them)
        host.state.update { it.copy(secrets = chat.kelo.app.chat.vault.SecretRules.refresh(it.secrets, fresh),
            items = if (it.busy) it.items else ChatReducer.dropSettled(it.items)) }
    }

    /**
     * Watches the status poll ([AgentActivity.status]) and asks for the lists it says changed: waiting approvals, payment steps,
     * secure entries, briefing cards.
     */
    fun watchStatus() {
        // the status poll (every few seconds in the foreground) says when the waiting approvals changed: ask for the list then
        host.activity?.let { a ->
            host.scope.launch {
                var before: Pair<Int, Int>? = null
                a.status.collect { s ->
                    val k = ChatAsks.pollKey(s) ?: return@collect
                    if (k != before) { before = k; refreshAsks() }
                }
            }
            // a payment step opened by work the chat is not following (a job, a scheduled run, another phone): looked for while the
            // agent works, and while one is open here
            host.scope.launch {
                a.status.collect { s ->
                    if (s?.busy == true || host.state.value.paymentSteps.any { it.open }) refreshPaymentSteps()
                }
            }
            // a secure entry asked for or answered elsewhere (another phone, the Keychain page): the chat's list follows
            host.scope.launch {
                var before: Int? = null
                a.status.collect { s ->
                    val n = s?.secretRequests ?: return@collect
                    if (n != before) { val first = before == null; before = n; if (!first || n > 0) refreshSecrets() }
                }
            }
            // a briefing card on screen changed elsewhere (its hours line answered on another device, or here): the card follows. The
            // first poll counts too: the card kept from last time may have changed while the app was closed.
            host.scope.launch {
                var before: String? = null
                a.status.collect { s ->
                    val rev = s?.briefingRev ?: return@collect
                    if (rev != before) { before = rev; refreshBriefings() }
                }
            }
        }
    }
}
