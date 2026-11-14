package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the buttons on the chat's cards do: approvals (also with an end-to-end card), payment PINs, the location card, the secure /
 * connect / address cards folding into one line, and a follow-up's buttons.
 */
internal class ChatCardActions(private val host: ChatHost, private val sync: ChatSync) {
    // ---- the location card (location_request): the screen asks the phone (permission, place) and hands the answer here
    /** The card shows "getting your location" while the screen reads the place. */
    fun locationReading(approvalId: String) =
        host.state.update { it.copy(items = ChatReducer.locationState(it.items, approvalId, ChatItem.LocationAsk.SENDING)) }

    /**
     * POST /api/location-requests/{id}: [place] (lat, lon, name?, country_code?, accuracy?, sublocality?) when shared, null for "no"
     * (the system prompt refused, Not now, or no place could be read). The reply that waits on it goes on by itself.
     */
    fun answerLocation(approvalId: String, place: kotlinx.serialization.json.JsonObject?) {
        val api = runCatching { host.api() }.getOrNull() ?: return
        host.state.update { it.copy(items = ChatReducer.locationState(it.items, approvalId, ChatItem.LocationAsk.SENDING)) }
        host.scope.launch {
            val end = try {
                api.post<kotlinx.serialization.json.JsonElement>("/api/location-requests/${KeloApi.segment(approvalId)}", kotlinx.serialization.json.buildJsonObject {
                    put("allowed", kotlinx.serialization.json.JsonPrimitive(place != null))
                    if (place != null) put("place", place)
                })
                if (place != null) ChatItem.LocationAsk.ALLOWED else ChatItem.LocationAsk.REFUSED
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status == 404 || e.status == 409) ChatItem.LocationAsk.EXPIRED else ChatItem.LocationAsk.WAITING   // try again
            } catch (e: Exception) {
                ChatItem.LocationAsk.WAITING
            }
            host.state.update { s ->
                s.copy(items = s.items.map { if (it is ChatItem.LocationAsk && it.approvalId == approvalId && it.state == ChatItem.LocationAsk.SENDING) it.copy(state = end) else it })
            }
        }
    }

    /** Answers an approval; [rememberSchedule]: keep allowing this step in the scheduled job it came from. */
    fun approve(id: String, ok: Boolean, rememberSite: Boolean = false, rememberSchedule: Boolean = false) {
        val open = host.state.value.items.any { it is ChatItem.Approval && it.id == id && it.open }
        if (!open) return
        host.state.update { s ->
            s.copy(
                items = ChatReducer.answer(s.items, id, ok),
                phase = if (s.busy) Phase(PhaseKind.WAITING, since = host.now()) else s.phase,
            )
        }
        host.scope.launch {
            try {
                host.api().post<JsonElement>("/api/approvals/${KeloApi.segment(id)}", buildJsonObject {
                    put("approve", ok)
                    if (ok && rememberSite) put("remember", "site")
                    else if (ok && rememberSchedule) put("remember", "schedule")
                })
                if (ok) host.platform.afterApproval()   // a text, call or calendar change may now wait for this phone
            } catch (_: ApiError) {
                // expired: the reply reports the denial
            }
        }
    }

    /**
     * Answers a waiting card the same way the 작업 page does ([rememberSite]: allow this kind of step on that site from now on;
     * [rememberSchedule]: in that scheduled job). [sendCard] runs first on Allow for a payment with an end-to-end card; when it
     * fails the question stays open and says why.
     */
    fun answerAsk(
        id: String, approve: Boolean, rememberSite: Boolean = false, rememberSchedule: Boolean = false,
        sendCard: (suspend () -> chat.kelo.app.wallet.CardSendError?)? = null,
    ) {
        val ask = host.state.value.asks.firstOrNull { it.id == id } ?: return
        if (!ask.open || ask.working) return
        val a = ask.approval
        host.state.update { it.copy(asks = ChatAsks.working(it.asks, id, true)) }
        host.scope.launch {
            try {
                if (approve && sendCard != null) {
                    val err = try { sendCard() } catch (e: CancellationException) { throw e } catch (e: Exception) { chat.kelo.app.wallet.CardSendError.OTHER }
                    if (err != null) {
                        host.state.update { s ->
                            s.copy(asks = if (err == chat.kelo.app.wallet.CardSendError.NOT_WAITING) ChatAsks.ended(s.asks, id, AskEnd.GONE, s.items.lastOrNull()?.key)
                                else ChatAsks.working(s.asks, id, false, err.takeIf { it != chat.kelo.app.wallet.CardSendError.CANCELLED }))
                        }
                        if (err != chat.kelo.app.wallet.CardSendError.CANCELLED) host.events.tryEmit(ChatEvent.ERROR)
                        return@launch
                    }
                }
                host.api().post<JsonElement>("/api/approvals/${KeloApi.segment(id)}", buildJsonObject {
                    put("approve", approve)
                    if (approve && rememberSite && a.canRemember) put("remember", "site")
                    else if (approve && rememberSchedule && a.canRememberSchedule) put("remember", "schedule")
                })
                if (approve) host.platform.afterApproval()   // a text, call or calendar change may now wait for this phone
                val end = ChatAsks.answerEnd(a, approve, rememberSite, rememberSchedule)
                host.state.update { s -> s.copy(asks = ChatAsks.ended(s.asks, id, end, s.items.lastOrNull()?.key)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status == 404) host.state.update { s -> s.copy(asks = ChatAsks.ended(s.asks, id, AskEnd.GONE, s.items.lastOrNull()?.key)) }
                else {
                    host.state.update { s -> s.copy(asks = ChatAsks.working(s.asks, id, false)) }
                    host.addError(if (e.isNetwork) ChatError.Network else ChatError.Message(e.detail))
                }
            } catch (e: Exception) {
                host.state.update { s -> s.copy(asks = ChatAsks.working(s.asks, id, false)) }   // signed out meanwhile
            }
        }
    }

    /** [answer]: approved | cancel | new_code | code | done. A step that is gone is folded by the next load. */
    fun answerPaymentStep(stepId: String, answer: String, code: String? = null) {
        host.scope.launch {
            try {
                PaymentStepCalls(host.api()).answer(stepId, answer, code)
                host.state.update { s -> s.copy(paymentSteps = s.paymentSteps.map { if (it.stepId == stepId && answer == "new_code") it.copy(state = "renewing") else it }) }
            } catch (e: ApiError) {
                sync.refreshPaymentSteps()
            }
        }
    }

    /**
     * Allow on a payment with an end-to-end card: [send] asks for the owner's check, decrypts the card and sends it to the
     * approval's slot; only when it got there is the payment allowed. A failure leaves the question open (the user can try again
     * or say no) and says why; a slot that is gone means the question is gone too.
     */
    fun approveWithCard(id: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?) {
        val item = host.state.value.items.firstOrNull { it is ChatItem.Approval && it.id == id } as? ChatItem.Approval ?: return
        if (!item.open || item.send == CardSend.Working) return
        runCardSend(id, send) { approve(id, true) }
    }

    /** "카드 보내기" on a card_needed row (the payment was allowed already). */
    fun sendCard(approvalId: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?) {
        val item = host.state.value.items.firstOrNull { it is ChatItem.CardNeeded && it.approvalId == approvalId } as? ChatItem.CardNeeded ?: return
        if (item.ended || item.send == CardSend.Working || item.send == CardSend.Sent) return
        runCardSend(approvalId, send) {}
    }

    private fun runCardSend(id: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?, then: () -> Unit) {
        host.state.update { it.copy(items = ChatReducer.cardSend(it.items, id, CardSend.Working)) }
        host.scope.launch {
            val err = try { send() } catch (e: CancellationException) { throw e } catch (e: Exception) { chat.kelo.app.wallet.CardSendError.OTHER }
            if (err == null) {
                host.state.update { it.copy(items = ChatReducer.cardSend(it.items, id, CardSend.Sent)) }
                then()
            } else {
                host.state.update { s ->
                    var items = ChatReducer.cardSend(s.items, id, CardSend.Failed(err))
                    if (err == chat.kelo.app.wallet.CardSendError.NOT_WAITING) {
                        items = items.map { if (it is ChatItem.Approval && it.id == id && it.open) it.copy(expired = true) else it }
                    }
                    s.copy(items = items)
                }
                if (err != chat.kelo.app.wallet.CardSendError.CANCELLED) host.events.tryEmit(ChatEvent.ERROR)
            }
        }
    }

    /** Approvals this phone allowed whose site has a saved PIN: their pin_needed row releases it at once (one more check). */
    private val pinOnAllow = mutableSetOf<String>()

    fun pinAllowed(approvalId: String) { pinOnAllow += approvalId }

    /** True once for a slot this phone allowed: the screen then starts the release by itself. */
    fun takePinOnAllow(slotId: String): Boolean = pinOnAllow.remove(slotId)

    /** "결제 비밀번호 보내기": the owner's check, then the PIN goes once to the payment's slot. */
    fun releasePin(slotId: String, send: suspend () -> chat.kelo.app.wallet.CardSendError?) {
        host.state.update { s -> s.copy(pinNeeds = s.pinNeeds.map { if (it.slotId == slotId) it.copy(send = CardSend.Working) else it }) }
        host.scope.launch {
            val err = send()
            val result = if (err == null) CardSend.Sent else CardSend.Failed(err)
            host.state.update { s -> s.copy(pinNeeds = s.pinNeeds.map { if (it.slotId == slotId) it.copy(send = result) else it }) }
        }
    }

    /** The user finished or closed a secure / connect card in the chat: it folds into one line. */
    fun settleCard(key: String, state: CardState) = host.state.update { it.copy(items = ChatReducer.settleCard(it.items, key, state)) }

    /** The in-chat address form [formId] was answered, cancelled or found gone here: its card folds into one line. */
    fun shippingAnswered(formId: String, outcome: ShippingOutcome) {
        host.state.update {
            it.copy(
                items = when (outcome) {
                    is ShippingOutcome.Sent -> ChatReducer.settleShipping(it.items, formId, ShippingFormState.DONE, outcome.label, outcome.line)
                    ShippingOutcome.Cancelled -> ChatReducer.settleShipping(it.items, formId, ShippingFormState.CANCELLED)
                    ShippingOutcome.Gone -> ChatReducer.settleShipping(it.items, formId, ShippingFormState.EXPIRED)
                },
                phase = if (it.busy && outcome !is ShippingOutcome.Gone) Phase(PhaseKind.WAITING, since = host.now()) else it.phase,
            )
        }
    }

    // ---- follow-up buttons under the agent's messages
    /**
     * Does one button of the follow-up [item] ([step] from [chat.kelo.app.followup.FollowupRules.step]). Done or already settled
     * elsewhere: the buttons give way to one line and the list loads again. Failed: they stay, and the reason shows in the chat.
     */
    fun runAction(item: chat.kelo.app.followup.Followup, step: chat.kelo.app.followup.FollowupStep) {
        val s = host.state.value
        if (item.id in s.acting || item.id in s.outcomes) return
        if (step is chat.kelo.app.followup.FollowupStep.OpenLogin) return   // the screen opens the sign-in sheet
        val api = runCatching { host.api() }.getOrNull() ?: return
        host.state.update { it.copy(acting = it.acting + item.id) }
        host.scope.launch {
            val (result, detail) = chat.kelo.app.followup.FollowupCalls(api).run(step)
            val outcome = ChatReducer.outcome(step, result)
            host.state.update { st -> st.copy(acting = st.acting - item.id, outcomes = if (outcome != null) st.outcomes + (item.id to outcome) else st.outcomes) }
            if (result == chat.kelo.app.followup.StepResult.FAILED) host.addError(if (detail.isBlank()) ChatError.Network else ChatError.Message(detail))
            else runCatching { sync.loadFollowupsNow() }
        }
    }

    /** The sign-in sheet opened from a button was filled in: its buttons give way to one line. */
    fun actionSignedIn(item: chat.kelo.app.followup.Followup) =
        host.state.update { it.copy(outcomes = it.outcomes + (item.id to ActionOutcome.SIGNED_IN)) }
}
