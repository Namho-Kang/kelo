package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import chat.kelo.app.core.ApiError
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** The journal (a month) and the week the 기록 page shows; deleting an entry and writing a day again. */
class AgentJournal(private val host: AgentHost) {
    private val _journal = MutableStateFlow(Loadable<Journal>())
    val journal: StateFlow<Loadable<Journal>> = _journal.asStateFlow()
    private val _month = MutableStateFlow(YearMonth.now())
    /** The month the 일지 tab shows */
    val month: StateFlow<YearMonth> = _month.asStateFlow()
    private val journalMonth: YearMonth get() = _month.value

    private val _rebuilding = MutableStateFlow<Set<String>>(emptySet())
    val rebuilding: StateFlow<Set<String>> = _rebuilding.asStateFlow()

    private var journalJob: kotlinx.coroutines.Job? = null

    fun loadJournal(month: YearMonth) {
        if (month != journalMonth) {
            _month.value = month
            journalJob?.cancel()
            _journal.value = Loadable()
        }
        journalJob = host.refresh(_journal) {
            val m = host.api().get<JournalMonth>("/api/journal?month=$month")
            Journal(month, m.items, m.pending)
        }
    }

    private val _week = MutableStateFlow(Loadable<WeekDigest>())
    /** The week the 기록 page shows: its journal entries and the days still waiting to be written */
    val week: StateFlow<Loadable<WeekDigest>> = _week.asStateFlow()
    private var weekJob: kotlinx.coroutines.Job? = null

    /** The week starting [start] (a Monday): one or two months of the journal, cut to the week. */
    fun loadWeek(start: java.time.LocalDate) {
        if (_week.value.data?.start != start) {
            weekJob?.cancel()
            _week.value = Loadable(loading = false)
        }
        weekJob = host.refresh(_week) {
            val months = DigestRules.months(start).map { m -> host.api().get<JournalMonth>("/api/journal?month=$m") }
            DigestRules.week(start, months.flatMap { it.items }, months.flatMap { it.pending })
        }
    }

    fun reloadJournal() {
        if (_journal.value.data != null) loadJournal(journalMonth)
        _week.value.data?.let { _week.update { l -> l.copy(loading = false) }; loadWeek(it.start) }
    }

    fun deleteEntry(e: JournalEntry) {
        _journal.update { l -> l.copy(data = l.data?.let { d -> d.copy(entries = d.entries.filter { it.id != e.id }) }) }
        _week.update { l -> l.copy(data = l.data?.let { d -> d.copy(entries = d.entries.filter { it.id != e.id }) }) }
        host.scope.launch {
            try {
                host.api().delete<JsonElement>("/api/journal/${e.id}")
                host.say(Res.string.agent_journal_deleted)
            } catch (ex: ApiError) {
                if (ex.status != 404) { host.sayError(ex); reloadJournal() }
            }
        }
    }

    /** POST /api/journal/rebuild?date=: writes that day's entries again with the main model. */
    fun rebuild(date: String) {
        if (date in _rebuilding.value) return
        _rebuilding.update { it + date }
        host.scope.launch {
            try {
                host.api().post<JsonElement>("/api/journal/rebuild?date=$date")
                host.say(Res.string.agent_journal_rebuilt)
            } catch (e: ApiError) {
                if (e.status == 409) host.messages.tryEmit(AgentMessage(Res.string.agent_journal_openai)) else host.sayError(e)
            } finally {
                _rebuilding.update { it - date }
                _journal.update { it.copy(loading = false) }
                reloadJournal()
            }
        }
    }

    /** The chat history was cleared on the server: the day summaries go at once. */
    fun forgetHistory() {
        _journal.update { l -> l.copy(data = l.data?.copy(entries = emptyList(), gaps = emptyList())) }
        _week.update { l -> l.copy(data = l.data?.copy(entries = emptyList(), gaps = emptyList())) }
    }
}
