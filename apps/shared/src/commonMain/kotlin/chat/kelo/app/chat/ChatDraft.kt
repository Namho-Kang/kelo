package chat.kelo.app.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The unsent text kept in [ChatHost.drafts] (encrypted on the phone): written a moment after typing stops (each write is an
 * encryption), and right away when the screen goes.
 */
internal class ChatDraft(private val host: ChatHost) {
    private var saving: Job? = null

    /** Written a moment after typing stops (each write is an encryption), and right away when the screen goes. */
    fun saveDraftSoon() {
        val d = host.drafts ?: return
        saving?.cancel()
        saving = host.scope.launch {
            delay(ChatViewModel.DRAFT_DELAY_MS)
            val text = host.state.value.input
            withContext(Dispatchers.IO) { runCatching { d.saveDraft(text) } }
        }
    }

    fun saveDraftNow() {
        val d = host.drafts ?: return
        saving?.cancel()
        if (host.sessions.api.value == null) return   // signed out: the draft was cleared with the session, do not write it back
        runCatching { d.saveDraft(host.state.value.input) }
    }

    /** The message was sent from the composer: the saved draft is emptied. */
    fun cleared() {
        saving?.cancel()
        host.drafts?.let { d -> host.scope.launch(Dispatchers.IO) { runCatching { d.saveDraft("") } } }
    }
}
