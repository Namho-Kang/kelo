package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one question before chats first go to an AI service ([AiConsent]): Agree sends the held message, Not now keeps it in the composer. */
internal class ChatConsent(private val host: ChatHost, private val turns: ChatTurns) {
    // ---- asking once before chats go to the AI service ([AiConsent])
    /** The consent call is on its way (the Agree button waits) */
    private val _consentBusy = MutableStateFlow(false)
    val consentBusy: StateFlow<Boolean> = _consentBusy.asStateFlow()
    private val _consentError = MutableStateFlow<String?>(null)
    val consentError: StateFlow<String?> = _consentError.asStateFlow()

    /** "Agree": the server keeps it (once, for every device), then the very same message goes. */
    fun consentAgree() {
        val a = host.state.value.consentAsk ?: return
        if (_consentBusy.value) return
        _consentBusy.value = true
        _consentError.value = null
        host.scope.launch {
            try {
                host.api().put<kotlinx.serialization.json.JsonElement>("/api/ai-consent", kotlinx.serialization.json.buildJsonObject {
                    put("accepted", kotlinx.serialization.json.JsonPrimitive(true))
                })
                host.state.update { it.copy(consentAsk = null) }
                if (!host.state.value.busy) turns.start(a.text, a.fromComposer, a.tookAttachments, a.answers, a.secrets, turns.trayFiles(a.tookAttachments))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _consentError.value = (e as? ApiError)?.detail ?: e.message ?: e.javaClass.simpleName
            } finally {
                _consentBusy.value = false
            }
        }
    }

    /** "Not now" (or the question dismissed): nothing is sent; the text stays in the composer (a button's text is put there). */
    fun consentNotNow() {
        val a = host.state.value.consentAsk ?: return
        _consentError.value = null
        host.state.update { it.copy(consentAsk = null, input = if (!a.fromComposer && it.input.isBlank()) a.text else it.input) }
    }
}
