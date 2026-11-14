package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A reply: sending a message, following the turn on the server (events, phase, the end) and the Stop button. */
internal class ChatTurns(
    private val host: ChatHost, private val sync: ChatSync, private val history: ChatHistory, private val draft: ChatDraft,
) {

    private var turn: Job? = null

    // ---- a reply
    /** The reply being followed put answer buttons under its message */
    private var repliesCame = false

    private fun apply(ev: TurnEvent) {
        host.state.update {
            it.copy(
                phase = if (it.phase == null) null else ChatReducer.phase(it.phase, ev, host.now()),
                items = ChatReducer.apply(it.items, ev, host.now(), host::key),
            )
        }
        if (ev is TurnEvent.Error || ev is TurnEvent.StepLimit) host.events.tryEmit(ChatEvent.ERROR)
        if (ev is TurnEvent.PaymentAuto) host.events.tryEmit(ChatEvent.PAYMENT_AUTO)
        if (ev is TurnEvent.Followup) sync.loadFollowups()
        if (ev is TurnEvent.HumanCheck) sync.refreshAsks(force = true)   // the hand-over card (or its folded line) at the bottom, at once
        if (ev is TurnEvent.Replies || ev is TurnEvent.Comparison) repliesCame = true
        if (ev is TurnEvent.PaymentStepChanged) host.state.update { it.copy(paymentSteps = PaymentStepRules.upsert(it.paymentSteps, ev.step)) }
        if (ev is TurnEvent.PinNeeded) host.state.update { it.copy(pinNeeds = PaymentStepRules.pinUpsert(it.pinNeeds, ev.need)) }
        host.activity?.let {
            it.turnLine.value = AgentActivityRules.next(it.turnLine.value, ev)
            it.turnKind.value = AgentActivityRules.turnKind(it.turnKind.value, ev)
        }
        host.watch?.event(ev)
    }

    /**
     * Sends [given] (a card's "에이전트에게 알리기", or 다시 시도) or the typed text; the typed text takes the uploaded attachments
     * along ([words] names them in the message), and they leave the tray. [answers]: the quick-reply fields [given] answers.
     */
    fun send(given: String? = null, words: AttachmentWords? = null, answers: List<ReplyAnswer> = emptyList()) {
        val s = host.state.value
        if (s.busy || s.secretAsk != null || s.consentAsk != null || (given == null && s.uploading)) return
        val sending = if (given == null && words != null) s.ready.filter { it.stored != null } else emptyList()
        val names = sending.mapNotNull { it.stored }
        val text = if (given != null) given.trim() else if (words != null) AttachmentText.compose(s.input, names, words) else s.input.trim()
        if (text.isEmpty()) return
        // the files go with the message (the server keeps them with it, chat_attachments.py); nothing typed = the app's own sentence
        val files = sending.map { a -> MessageFile(name = a.name, path = a.stored.orEmpty(), kind = if (a.image) "image" else "document") }
        start(text, fromComposer = given == null, tookAttachments = given == null && words != null, answers = answers, secrets = null,
            files = files, autoText = files.isNotEmpty() && s.input.isBlank())
    }

    /** The uploaded files waiting in the tray, as a message's files (a held-back message sent again takes them along). */
    fun trayFiles(took: Boolean): List<MessageFile> = if (!took) emptyList() else
        host.state.value.ready.filter { it.stored != null }.map { a -> MessageFile(name = a.name, path = a.stored.orEmpty(), kind = if (a.image) "image" else "document") }

    /**
     * Starts the reply to [text]. [secrets] = [SecretGuard.ALLOW] only when the owner chose "Send as is" on the secret sheet. When
     * the server holds the message back for a secret (409), its bubble goes again, the composer and the attachments come back as
     * they were, and [ChatState.secretAsk] opens the sheet.
     */
    fun start(
        text: String, fromComposer: Boolean, tookAttachments: Boolean, answers: List<ReplyAnswer>, secrets: String?,
        files: List<MessageFile> = emptyList(), autoText: Boolean = false,
    ) {
        val api = runCatching { host.api() }.getOrNull() ?: return
        val before = host.state.value
        val bubble = host.key()
        host.state.update {
            it.copy(
                input = if (fromComposer) "" else it.input,
                attachments = if (tookAttachments) emptyList() else it.attachments,
                busy = true,
                phase = Phase(PhaseKind.WAITING, since = host.now()),
                items = it.items + ChatItem.Msg(bubble, mine = true, text = text, at = host.now(), files = files, autoText = autoText),
            )
        }
        if (fromComposer) draft.cleared()
        host.events.tryEmit(ChatEvent.SENT)
        host.activity?.following?.value = true
        host.stir()
        host.activity?.turnLine?.value = null
        host.activity?.turnKind?.value = TurnKind.Thinking
        host.watch?.started()
        host.watch?.stopWith { stop() }
        var finished = false
        var failed = false
        turn = host.scope.launch {
            try {
                TurnFollower(KeloTurnApi(api)).talk(text, TimeZone.getDefault().id, answers, secrets, files.map { it.path }, autoText) { ev -> apply(ev) }
                finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val held = if (secrets == null) SecretGuard.of(e) else null
                if (held != null) {   // nothing was sent to the model or stored: no bubble left behind, the text back where it was
                    host.fresh -= bubble
                    host.state.update { st ->
                        st.copy(
                            items = st.items.filterNot { it.key == bubble },
                            input = if (fromComposer) before.input else st.input,
                            attachments = if (tookAttachments) before.attachments + st.attachments else st.attachments,
                            secretAsk = SecretAsk(
                                held, text, answers, fromComposer, tookAttachments,
                                composer = if (fromComposer) before.input else "",
                                composerSpans = if (fromComposer) SecretGuard.composerSpans(before.input, held.spans) else emptyList(),
                            ),
                        )
                    }
                    return@launch
                }
                val consent = AiConsent.of(e)
                if (consent != null) {   // nobody agreed yet: nothing was stored or sent; the text goes back and the question opens
                    host.fresh -= bubble
                    host.state.update { st ->
                        st.copy(
                            items = st.items.filterNot { it.key == bubble },
                            input = if (fromComposer) before.input else st.input,
                            attachments = if (tookAttachments) before.attachments + st.attachments else st.attachments,
                            consentAsk = ConsentAsk(consent, text, answers, fromComposer, tookAttachments, secrets),
                        )
                    }
                    return@launch
                }
                failed = true
                host.addError(
                    when {
                        e.isNetwork -> ChatError.Network
                        // only the server's "no model chosen" answer; any other 400 says its own words
                        e.status == 400 && (e.detailJson?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content == "model_required" ->
                            ChatError.PickModel(e.detail)
                        else -> ChatError.Message(e.detail)
                    },
                )
            } catch (e: Exception) {
                failed = true
                host.addError(ChatError.Message(e.message ?: e.javaClass.simpleName))
            } finally {
                when {
                    finished -> host.watch?.finished(true)
                    failed -> host.watch?.finished(false)
                    else -> host.watch?.cancelled()
                }
                turn = null
                host.activity?.turnLine?.value = null
                host.activity?.turnKind?.value = TurnKind.Thinking
                host.activity?.following?.value = false
                host.stir()   // the reply's end starts the quiet time before the character may sleep
                // the reply came in: the character cheers for a moment
                if (finished) host.activity?.doneAt?.value = host.platform.uptimeMs()
                // questions nobody answered are denied by the server once the reply is over
                host.state.update { it.copy(busy = false, phase = null, items = ChatReducer.endTurn(it.items), secrets = chat.kelo.app.chat.vault.SecretRules.settled(it.secrets)) }
                if (finished) host.events.tryEmit(ChatEvent.REPLY_DONE)
                if (repliesCame) {   // the answer buttons ride on the stored message: load the messages again to show them
                    repliesCame = false
                    host.scope.launch { runCatching { history.reloadNow() } }
                } else {
                    history.remember()   // this phone's own messages are already on screen: do not reload them
                }
                sync.loadFollowups()
                sync.refreshAsks()
                sync.refreshSecrets()
            }
        }
    }

    /**
     * Follows a reply the server started by itself for this phone (a secure entry saved after the reply that asked had ended): the
     * same status line, events and end as a reply sent from here, without a bubble of the user's own.
     */
    fun followTurn(turnId: String) {
        val api = runCatching { host.api() }.getOrNull() ?: return
        if (host.state.value.busy) return
        host.state.update { it.copy(busy = true, phase = Phase(PhaseKind.WAITING, since = host.now())) }
        host.activity?.following?.value = true
        host.activity?.turnLine?.value = null
        host.activity?.turnKind?.value = TurnKind.Thinking
        host.stir()
        var finished = false
        turn = host.scope.launch {
            try {
                TurnFollower(KeloTurnApi(api)).follow(turnId) { ev -> apply(ev) }
                finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status != 404) host.addError(if (e.isNetwork) ChatError.Network else ChatError.Message(e.detail))
            } catch (e: Exception) {
                host.addError(ChatError.Message(e.message ?: e.javaClass.simpleName))
            } finally {
                turn = null
                host.activity?.turnLine?.value = null
                host.activity?.following?.value = false
                host.stir()
                if (finished) host.activity?.doneAt?.value = host.platform.uptimeMs()
                host.state.update { it.copy(busy = false, phase = null, items = ChatReducer.endTurn(it.items), secrets = chat.kelo.app.chat.vault.SecretRules.settled(it.secrets)) }
                if (finished) host.events.tryEmit(ChatEvent.REPLY_DONE)
                history.remember()
                sync.loadFollowups()
                sync.refreshSecrets()
            }
        }
    }

    /** The Stop button: cancelling the reply tells the server to stop (DELETE /api/turns/{id}). */
    fun stop() {
        turn?.cancel()
    }
}
