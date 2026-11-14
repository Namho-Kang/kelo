package chat.kelo.app.chat

import chat.kelo.app.core.DraftStore
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the helpers of [ChatViewModel] share: its scope, the state and the one-off events, the session, and the row keys. The view
 * model makes one and hands it to each helper ([ChatSync], [ChatHistory], [ChatTurns], [ChatUploads], [ChatCardActions],
 * [ChatSecrets], [ChatConsent], [ChatDraft]).
 */
internal class ChatHost(
    val scope: CoroutineScope,
    val sessions: Sessions,
    val state: MutableStateFlow<ChatState>,
    val events: MutableSharedFlow<ChatEvent>,
    val drafts: DraftStore?,
    /** The line in the header's name pill: what the reply being followed is doing right now */
    val activity: AgentActivity?,
    /** "reply done" / "failed" notifications while the app is away */
    val watch: chat.kelo.app.chat.TurnWatch?,
    val platform: ChatPlatform,
) {
    private var seq = 0
    /** Rows made in this session (not loaded from history) that have not risen into view yet */
    val fresh = HashSet<String>()
    fun key(): String = "k${++seq}".also { fresh += it }
    /** An id for a chip in the attachment tray (shares the counter with the row keys) */
    fun attachmentId(): String = "a${++seq}"

    fun api(): KeloApi = sessions.requireApi()
    fun now() = System.currentTimeMillis()

    /** Something the chat could not do, shown as an error row. */
    fun addError(e: ChatError) {
        state.update { it.copy(items = it.items + ChatItem.Error(key(), e)) }
        events.tryEmit(ChatEvent.ERROR)
    }

    /** Something happened in the chat (typing, a message sent or come in, a reply ending): the character stays awake a while. */
    fun stir() {
        activity?.touch(platform.uptimeMs())
    }
}
