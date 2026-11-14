package chat.kelo.app.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.Caches
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.DeviceList
import chat.kelo.app.core.Sessions
import chat.kelo.app.wallet.WalletInfo
import chat.kelo.app.wallet.WalletNotifications
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** The My home page's numbers. [overview] is what was kept from last time until the fresh answers replace it part by part. */
data class MyUiState(val overview: MyOverview = MyOverview(), val offline: Boolean = false)

/**
 * The numbers on My home. The ones kept from last time (on the phone, encrypted) show at once, and coming back to My shows the last
 * ones instead of an empty page that then grows (which made the page jump under the finger); they are refreshed in place.
 */
class MyOverviewViewModel(private val sessions: Sessions, val server: ServerUpdateViewModel) : ViewModel() {
    private val _state = MutableStateFlow(MyUiState(runCatching { sessions.cached(Caches.MY) }.getOrNull() ?: MyOverview()))
    val state: StateFlow<MyUiState> = _state.asStateFlow()

    private fun change(f: (MyOverview) -> MyOverview) = _state.update { it.copy(overview = f(it.overview)) }

    /** The home page was shown: every part replaces its old value when it arrives, and the whole is kept for next time once all answered. */
    suspend fun refresh() {
        val api = runCatching { sessions.requireApi() }.getOrNull() ?: return
        val parts = kotlinx.coroutines.coroutineScope {
            listOf(
                async { runCatching { api.get<Usage>("/api/usage") }.onSuccess { u -> change { it.copy(usage = u) } } },
                async { runCatching { api.get<DeviceList>("/api/devices") }.onSuccess { d -> change { it.copy(devices = d.devices.size) } } },
                async { runCatching { api.get<VaultState>("/api/vault") }.onSuccess { v -> change { it.copy(creds = v.items, locked = v.locked) } } },
                async { runCatching { api.get<JsonArray>("/api/uploads") }.onSuccess { d -> change { it.copy(documents = d.size) } } },
                async {
                    runCatching { api.get<Resources>("/api/resources") }
                        .onSuccess { r -> change { it.copy(sizes = r.profile, storage = StorageRules.key(StorageRules.state(r.alert)), storagePercent = r.alert.percent) } }
                },
                async { runCatching { api.get<About>("/api/about") }.onSuccess { a -> change { it.copy(version = a.version) } } },
                // 서버 업데이트: an owner sign-in on a server whose updater is set up (403, 404 and "unavailable": nothing shows)
                async { runCatching { server.loadForHome() } },
                async { runCatching { api.get<WalletNotifications>("/api/wallet/notifications?limit=1") }.onSuccess { n -> change { it.copy(walletUnseen = n.unseen) } } },
                async { runCatching { api.get<WalletInfo>("/api/wallet/cards") }.onSuccess { w -> change { it.copy(cards = w.cards.size) } } },
                // the operator section: only for an owner sign-in (the access key, or a phone marked as the owner's) on the operator's own
                // server; a self-hosted copy says operator=false, any other phone gets 403. A server from before the call (404): the older rule
                async {
                    runCatching { api.get<OperatorStatus>("/api/operator/status").operator }
                        .map { OperatorRules.operatorFrom(null, it) }
                        .recoverCatching { e ->
                            val status = (e as? ApiError)?.status?.takeIf { it == 403 || it == 404 } ?: throw e
                            val older = if (status == 404) runCatching { api.get<JsonElement>("/api/operator/firebase"); null }
                                .recoverCatching { f -> if (f is ApiError && f.status == 403) 403 else throw f }.getOrElse { -1 } else -1
                            OperatorRules.operatorFrom(status, null) { OperatorRules.ownerFrom(older) }
                        }
                        .onSuccess { shown -> shown?.let { v -> change { it.copy(operator = v) } } }
                },
            ).awaitAll()
        }
        _state.update { it.copy(offline = MyOverview.offline(parts.map { p -> p.exceptionOrNull() })) }
        if (parts.any { it.isSuccess }) sessions.keep(api, Caches.MY, _state.value.overview)
    }

    /** The usage page's refresh (the number on the page and on home change together). */
    fun refreshUsage(refresh: Boolean = false) {
        viewModelScope.launch {
            runCatching { sessions.requireApi().get<Usage>("/api/usage${if (refresh) "?refresh=true" else ""}") }.onSuccess { u -> change { it.copy(usage = u) } }
        }
    }

    /** The wallet page showed its payments: the dot on 결제 카드 goes. */
    fun walletSeen() = change { it.copy(walletUnseen = 0) }

    /**
     * 마이 → 대화 기록 삭제: the server deletes the chat and what came of it, then this device forgets the same. Returns null when it
     * worked, else the server's message.
     */
    suspend fun clearChat(cancelNotifications: () -> Unit): String? = try {
        sessions.requireApi().delete<JsonElement>("/api/messages")   // the chat and what came of it (ClearHistory)
        ClearHistory.forgetLocally(sessions, cancelNotifications)
        null
    } catch (e: ApiError) {
        e.detail
    }
}
