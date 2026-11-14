package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** The agent's browser tabs: the list, a screenshot, closing a tab and starting a browser job. */
class AgentBrowser(private val host: AgentHost, private val tasks: AgentTasks) {
    private val _browser = MutableStateFlow(Loadable<BrowserTabs>())
    val browser: StateFlow<Loadable<BrowserTabs>> = _browser.asStateFlow()

    fun loadBrowser() { host.refresh(_browser) { host.api().get<BrowserTabs>("/api/browser/sessions?thumbnails=true") } }

    suspend fun screenshot(tabId: String): ByteArray = host.api().download("/api/browser/sessions/${KeloApi.segment(tabId)}/screenshot")

    fun closeTab(t: BrowserTab, then: () -> Unit) {
        host.scope.launch {
            try {
                host.api().delete<JsonElement>("/api/browser/sessions/${KeloApi.segment(t.id)}")
                host.say(Res.string.agent_tab_closed)
                then()
                loadBrowser()
            } catch (e: ApiError) {
                when (e.status) {
                    409 -> host.say(Res.string.agent_tab_busy)
                    404 -> { then(); loadBrowser() }
                    else -> host.sayError(e)
                }
            }
        }
    }

    /** The chat history was cleared on the server: ended tabs and the tabs of runs go at once. */
    fun forgetHistory() {
        _browser.update { l -> l.copy(data = l.data?.let { d -> d.copy(items = d.items.filter { it.status != "ended" && !it.id.startsWith("task:") && !it.id.startsWith("schedule:") }) }) }   // runs' tabs are closed
    }
}
