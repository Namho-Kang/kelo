package chat.kelo.desktop.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chat.kelo.app.chat.ChatPlatform
import chat.kelo.app.chat.ChatViewModel
import chat.kelo.app.chat.ReplyLive
import chat.kelo.app.chat.ReplyLiveRules
import chat.kelo.app.chat.TurnEvent
import chat.kelo.app.chat.TurnWatch
import chat.kelo.app.notify.OpenTarget
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.loadString
import chat.kelo.desktop.core.AppContainer
import chat.kelo.desktop.core.DesktopNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What only the desktop chat keeps beside the shared [ChatViewModel] (the shared state has no field for it): the address forms
 * answered with "add a card".
 */
class ChatSide {
    private val _shippingCard = MutableStateFlow<Set<String>>(emptySet())
    /** In-chat address forms answered here with "add a card" as the way to pay (form ids): the folded line says where cards are added */
    val shippingCard: StateFlow<Set<String>> = _shippingCard.asStateFlow()

    fun cardChosen(formId: String) { _shippingCard.update { it + formId } }
}

val LocalChatSide = compositionLocalOf { ChatSide() }

/**
 * Tells the tray: the reply the chat followed is done (or failed) while the window is hidden.
 */
class TrayTurnWatch(private val app: AppContainer, private val side: ChatSide) : TurnWatch {
    private var live = ReplyLive()

    override fun started() {
        live = ReplyLive()
        app.liveStep.value = null
    }

    override fun stopWith(stop: () -> Unit) {
        app.stopReply.value = stop
    }

    override fun event(ev: TurnEvent) {
        ReplyLiveRules.next(live, ev, System.currentTimeMillis())?.let { live = it; app.liveStep.value = ReplyLiveRules.trayLine(it) }
    }

    override fun cancelled() {
        app.liveStep.value = null
        app.stopReply.value = null
    }

    override fun finished(ok: Boolean) {
        app.liveStep.value = null
        app.stopReply.value = null
        if (app.windowShown.value) return
        app.scope.launch {
            app.notify(
                if (ok) DesktopNote(loadString(Res.string.notify_reply_done), app.profile.value?.agentName.orEmpty(), target = OpenTarget.Chat)
                else DesktopNote(loadString(Res.string.notify_reply_failed), loadString(Res.string.notify_reply_failed_body), DesktopNote.Kind.WARNING, target = OpenTarget.Chat),
            )
        }
    }
}

/**
 * The desktop's chat: one shared [ChatViewModel] per signed-in session, kept in its own [ViewModelStore] (a desktop window has no
 * ViewModelStoreOwner); [close] clears it, which saves the draft. The log and the clock come from the shared defaults.
 */
class DesktopChat(app: AppContainer) : ViewModelStoreOwner {
    val side = ChatSide()
    private val store = ViewModelStore()
    /** The sign-in's view models (the chat's, the work screen's): a desktop window has no owner of its own, so this is it; [close] clears them. */
    override val viewModelStore: ViewModelStore get() = store
    val vm: ChatViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { ChatViewModel(app.sessions, app.store, app.activity, TrayTurnWatch(app, side), ChatPlatform.Default) } },
    )[ChatViewModel::class]

    fun close() = store.clear()
}
