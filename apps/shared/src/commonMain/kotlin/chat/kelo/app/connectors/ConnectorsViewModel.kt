package chat.kelo.app.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.Caches
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Sessions
import chat.kelo.app.my.VaultEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

data class ConnectorsState(
    val list: List<Connector> = emptyList(),
    val loaded: Boolean = false,
    val failed: Boolean = false,
    /** The last refresh did not reach the server: the list shown is the one kept from before */
    val offline: Boolean = false,
    val query: String = "",
    /** The chosen filter chip: a group key, or null for all */
    val filter: String? = null,
    /** The full list with its group chips (else: connected and popular services only) */
    val showAll: Boolean = false,
    /** The connector whose detail is open (follows reloads; null shows the list) */
    val openId: String? = null,
    /** The vault's items (never a value): who is connected, and which connections need doing again */
    val creds: List<VaultEntry> = emptyList(),
    /** Google Calendar's "connect with the phone's calendar": the list scrolls down to this device's rows and opens the calendar row */
    val phoneCalendar: Boolean = false,
) {
    val shown: List<Connector> get() = ConnectorList.search(list, query)
    val open: Connector? get() = ConnectorRules.find(list, openId)
}

/** The 커넥터 tab: the list, the search and the filter, and which detail is open. */
class ConnectorsViewModel(private val sessions: Sessions, initialOpen: String?) : ViewModel() {
    // the list kept from last time shows at once (no placeholder) and is refreshed in place
    private val _state = MutableStateFlow(
        runCatching { sessions.cached(Caches.CONNECTORS) }.getOrNull().let { kept ->
            ConnectorsState(list = kept.orEmpty(), loaded = kept != null, openId = initialOpen)
        },
    )
    val state: StateFlow<ConnectorsState> = _state.asStateFlow()
    private var loading: Job? = null

    /** GET /api/connectors again (on start, on returning to the app, after a panel changed something). */
    fun reload() {
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val api = sessions.requireApi()
                val creds = async { runCatching { api.get<List<VaultEntry>>("/api/credentials") }.getOrNull() }
                val l = ConnectorCalls.list(api)
                sessions.keep(api, Caches.CONNECTORS, l)
                val c = creds.await()
                _state.update { s ->
                    // an open connector the server no longer lists closes
                    s.copy(list = l, loaded = true, failed = false, offline = false, openId = s.openId?.takeIf { id -> l.any { it.id == id } },
                        creds = c ?: s.creds)
                }
            } catch (e: ApiError) {
                _state.update { it.copy(loaded = true, failed = it.list.isEmpty(), offline = it.list.isNotEmpty()) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setFilter(f: String?) = _state.update { it.copy(filter = f) }
    fun setShowAll(on: Boolean) = _state.update { it.copy(showAll = on, filter = if (on) it.filter else null) }
    fun open(id: String?) = _state.update { it.copy(openId = id) }

    /** Back to the list, unfiltered, with this device's calendar row to open ([phoneCalendarShown] says it was). */
    fun showPhoneCalendar() = _state.update { it.copy(query = "", filter = null, openId = null, phoneCalendar = true) }
    fun phoneCalendarShown() = _state.update { it.copy(phoneCalendar = false) }
}
