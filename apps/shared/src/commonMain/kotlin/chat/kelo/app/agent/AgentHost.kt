package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import org.jetbrains.compose.resources.StringResource
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the parts of [AgentViewModel] share: its scope, the session, the snackbar messages and the one way a list is loaded
 * ([refresh]: the last good data stays when a load fails).
 */
class AgentHost(val scope: CoroutineScope, private val sessions: Sessions, val platform: chat.kelo.app.chat.ChatPlatform = chat.kelo.app.chat.ChatPlatform.Default) {
    val messages = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 4)

    fun api(): KeloApi = sessions.requireApi()

    fun say(text: StringResource, vararg args: Any) { messages.tryEmit(AgentMessage(text, args.toList())) }

    fun sayError(e: Throwable) {
        val detail = (e as? ApiError)?.takeIf { !it.isNetwork }?.detail
        messages.tryEmit(AgentMessage(if (detail != null) Res.string.agent_error_detail else Res.string.connect_failed, detail = detail))
    }

    fun <T> refresh(flow: MutableStateFlow<Loadable<T>>, block: suspend () -> T): kotlinx.coroutines.Job? {
        if (flow.value.loading) return null
        flow.update { it.copy(loading = true) }
        return scope.launch {
            try {
                val v = block()
                flow.update { Loadable(v) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}
