package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Secure entries the agent asked for (answered in the chat), and the sheet for a message held back because it holds a secret. */
internal class ChatSecrets(private val host: ChatHost, private val turns: ChatTurns) {
    /**
     * The entry [req] was saved here (the value went straight to the vault or the wallet). [answer]'s turn is a reply the server
     * started to carry on, followed here like this phone's own; its end clears the entry's line. Without a reply on this phone
     * (none follows, or the reply already ended or failed before this answer came) the line goes at once ([SecretRules.followsHere]).
     */
    fun secretSaved(req: chat.kelo.app.chat.vault.SecretRequest, answer: chat.kelo.app.chat.vault.SaveAnswer, result: chat.kelo.app.chat.vault.SecretResult?) {
        settleSecret(req, chat.kelo.app.chat.vault.SecretRequest.STATE_SAVED, result, answer)
        answer.turnId?.takeIf { answer.reply != chat.kelo.app.chat.vault.SaveAnswer.REPLY_NONE }?.let { turns.followTurn(it) }
    }

    /** [Cancel] on an entry: the agent is told (it does not ask again); the card folds at once (or goes, when no reply runs here). */
    fun secretCancelled(req: chat.kelo.app.chat.vault.SecretRequest) {
        settleSecret(req, chat.kelo.app.chat.vault.SecretRequest.STATE_CANCELLED, null, null)
        host.scope.launch {
            try {
                val answer = chat.kelo.app.chat.vault.SecretCalls.cancel(host.api(), req.id)
                answer.turnId?.takeIf { answer.reply != chat.kelo.app.chat.vault.SaveAnswer.REPLY_NONE }?.let { turns.followTurn(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status != 404) host.addError(if (e.isNetwork) ChatError.Network else ChatError.Message(e.detail))
            } catch (_: Exception) {
            }
        }
    }

    /** The entry is no longer open on the server (answered on another phone, or it ran out). */
    fun secretGone(req: chat.kelo.app.chat.vault.SecretRequest) = settleSecret(req, chat.kelo.app.chat.vault.SecretRequest.STATE_EXPIRED, null, null)

    private fun settleSecret(
        req: chat.kelo.app.chat.vault.SecretRequest, state: String, result: chat.kelo.app.chat.vault.SecretResult?,
        answer: chat.kelo.app.chat.vault.SaveAnswer?,
    ) {
        host.state.update { s ->
            val done = req.copy(state = state, result = result ?: req.result)
            s.copy(
                items = ChatReducer.afterAnswer(s.items, done, chat.kelo.app.chat.vault.SecretRules.followsHere(answer, s.busy)),
                // the server lists only open entries: an answered one leaves the kept list
                secrets = s.secrets.filterNot { it.id == req.id },
            )
        }
    }

    // ---- the secret sheet (a message held back for a key or password; SecretGuard)
    /** Waits for the wallet's card form after "Save in Keychain" chose a card ([secretToCard]); memory only */
    private var cardRef: SecretAsk? = null

    /**
     * The save step kept [value] in the Keychain (straight from the app to the vault): in the composer, every span holding that value
     * becomes [reference] ("(saved to Keychain: GitHub)"), so the rest of the message can still be sent; the sheet closes.
     */
    fun secretSaved(value: String, reference: String) {
        val a = host.state.value.secretAsk ?: return
        host.state.update { it.copy(secretAsk = null, input = referencedInput(it.input, a, reference, value)) }
    }

    /** A card number goes on to the wallet's card form, filled in there from [handoff] (memory only); the sheet closes. */
    fun secretToCard(handoff: SecretHandoff) {
        val a = host.state.value.secretAsk ?: return
        handoff.offer(a.values.take(1))
        cardRef = a
        host.state.update { it.copy(secretAsk = null) }
    }

    /** The card form kept the card: the composer gets [reference] in place of the number. */
    fun secretCardSaved(reference: String) {
        val a = cardRef ?: return
        cardRef = null
        host.state.update { it.copy(input = referencedInput(it.input, a, reference, null)) }
    }

    /** "Send as is": the very same text again, with `"secrets": "allow"` (only ever after this tap). */
    fun secretSendAsIs() {
        val a = host.state.value.secretAsk ?: return
        host.state.update { it.copy(secretAsk = null) }
        if (host.state.value.busy) return
        turns.start(a.text, a.fromComposer, a.tookAttachments, a.answers, SecretGuard.ALLOW, turns.trayFiles(a.tookAttachments), a.tookAttachments && a.composer.isBlank())
    }

    /** "Edit" (or the sheet dismissed): back to the composer with the text intact; a button's text is put there to be changed. */
    fun secretEdit() {
        val a = host.state.value.secretAsk ?: return
        host.state.update { it.copy(secretAsk = null, input = if (!a.fromComposer && it.input.isBlank()) a.text else it.input) }
    }
}

/** The composer is changed only while it still holds the text that was put back (the owner may have rewritten it since). */
internal fun referencedInput(input: String, a: SecretAsk, reference: String, value: String?): String =
    if (!a.fromComposer || input != a.composer) input else SecretGuard.replace(input, a.composerSpans, reference, value)
