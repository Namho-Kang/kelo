package chat.kelo.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.chat.AgentActivity
import chat.kelo.app.chat.AgentActivityRules
import chat.kelo.app.chat.AgentStatus
import chat.kelo.app.chat.BrowserTabsBrief
import chat.kelo.app.chat.ChatPlatform
import chat.kelo.app.chat.SleepHours
import chat.kelo.app.chat.TasksBrief
import chat.kelo.app.chat.TurnKind
import chat.kelo.app.chat.WorkersBrief
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.whileObserved
import chat.kelo.app.my.ToneRules
import chat.kelo.app.platform.PhoneLocation
import chat.kelo.app.ui.character.KeloMood
import chat.kelo.app.work.WorkBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * What the shell's header and tabs show: the newest agent [status], the second [line] (the followed reply's own, else the polled one),
 * the character's [mood], the 작업 [badge] and whether anything waits for the owner ([news]).
 */
data class ShellUiState(
    val status: AgentStatus? = null,
    val line: String? = null,
    val mood: KeloMood = KeloMood.Idle,
    val badge: WorkBadge.Shown? = null,
) {
    val news: Boolean get() = badge != null
}

/**
 * The signed-in shell's view of the agent: polls GET /api/agent/status (and the few follow-up questions it leads to) and turns what
 * [AgentActivity] holds into the header's line and character. Everything runs only while [state] is collected (the window in front,
 * the app started), so nothing is asked in the background. The app root keeps the profile in step through [bind].
 */
class ShellViewModel(
    private val sessions: Sessions,
    private val activity: AgentActivity,
    private val avatar: AgentAvatar,
    private val platform: ChatPlatform = ChatPlatform.Default,
    private val location: PhoneLocation? = null,
    private val pollMs: Long = AgentActivityRules.POLL_MS,
) : ViewModel() {
    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()
    private var profile: Profile? = null
    private var onProfile: (Profile) -> Unit = {}

    init {
        _state.whileObserved(viewModelScope) {
            coroutineScope {
                launch { poll() }
                launch { watch() }
                launch { touchOnChange() }
            }
        }
    }

    /** The profile as the app holds it now, and how to change it (the status carries a new name or tone). Call on every composition. */
    fun bind(profile: Profile?, onProfile: (Profile) -> Unit) {
        this.profile = profile
        this.onProfile = onProfile
    }

    // the header's line and the character, recomputed when what they read changes and every second for the clocks (cheer, sleep, night)
    private suspend fun watch() {
        val flags = combine(activity.offline, activity.status, activity.stuck, activity.listening, activity.following) { offline, status, stuck, listening, following ->
            Flags(offline, status, stuck, listening, following)
        }
        val times = combine(activity.turnKind, activity.polledBrowsing, activity.doneAt, activity.activeAt, activity.sleepHours) { kind, browsing, doneAt, activeAt, hours ->
            Times(kind, browsing, doneAt, activeAt, hours)
        }
        val lines = combine(activity.turnLine, activity.polledLine) { turn, polled -> turn to polled }
        val clock = flow { while (true) { emit(Unit); delay(CLOCK_MS) } }
        combine(flags, times, lines, clock) { f, t, l, _ -> Triple(f, t, l) }.collect { (f, t, l) ->
            val now = platform.uptimeMs()
            val sinceDone = if (t.doneAt > 0L) (now - t.doneAt).takeIf { it in 0 until AgentActivityRules.DONE_MS } else null
            val idle = AgentActivityRules.idleMs(now, t.activeAt)?.coerceAtMost(AgentActivityRules.SLEEP_AFTER_IDLE_MS)
            val night = AgentActivityRules.nightAt(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), t.hours)
            val mood = AgentActivityRules.mood(
                AgentActivityRules.MoodInput(f.offline, f.status, f.stuck, f.listening, f.following, t.kind, t.browsing, sinceDone, night, idle),
            )
            _state.value = ShellUiState(f.status, AgentActivityRules.shown(f.following, l.first, l.second), mood, WorkBadge.of(f.status))
        }
    }

    private class Flags(val offline: Boolean, val status: AgentStatus?, val stuck: Boolean, val listening: Boolean, val following: Boolean)
    private class Times(val kind: TurnKind, val browsing: Boolean, val doneAt: Long, val activeAt: Long, val hours: SleepHours?)

    // a reply, work, an approval or the microphone starting or ending counts as something happening
    private suspend fun touchOnChange() {
        combine(activity.following, activity.status, activity.listening) { following, status, listening ->
            listOf(following, status?.busy == true, (status?.pendingApprovals ?: 0) > 0, listening)
        }.collect { activity.touch(platform.uptimeMs()) }
    }

    private suspend fun poll() = coroutineScope {
        // the owner opened the app: the character is awake
        activity.touch(platform.uptimeMs())
        // the phone's location for the weather and "near me" questions: only when allowed, only now that the app is open
        val place = location
        if (place != null) sessions.api.value?.let { api -> launch { runCatching { place.send(api, force = false) } } }
        var tabsBefore = emptyMap<String, Double>()
        var before: AgentStatus? = null
        var failures = 0
        var round = 0
        while (true) {
            val api = sessions.api.value
            if (api != null) {
                try {
                    val s = api.get<AgentStatus>("/api/agent/status")
                    sessions.setLocked(s.locked)   // Keyguard: "Kelo is locked" comes and goes with the server's own word
                    failures = 0
                    activity.offline.value = false
                    activity.status.value = s
                    // work or a question waiting keeps the character up (it sleeps only after a quiet while)
                    if (s.busy || s.pendingApprovals > 0) activity.touch(platform.uptimeMs())
                    // the owner's contact hours decide the character's night; asked when the app comes up and every few minutes
                    if (round % HOURS_EVERY == 0) runCatching { api.get<SleepHours>("/api/proactive") }.onSuccess { activity.sleepHours.value = it }
                    if (!activity.following.value) activity.polledLine.value = AgentActivityRules.line(s)
                    // a run the chat is not following: is it in the browser? (asked only while the agent is busy)
                    if (s.busy && !activity.following.value) {
                        runCatching { api.get<BrowserTabsBrief>("/api/browser/sessions?thumbnails=false") }.onSuccess { t ->
                            activity.polledBrowsing.value = AgentActivityRules.polledBrowsing(s, t.items, tabsBefore, System.currentTimeMillis() / 1000.0)
                            tabsBefore = t.items.mapNotNull { b -> b.lastActivity?.let { b.id to it } }.toMap()
                        }
                    } else {
                        activity.polledBrowsing.value = false
                        tabsBefore = emptyMap()
                    }
                    // is a job stuck on the user, or did work just finish? Asked on the first poll, when a run ends, when unseen
                    // tasks change, and every half minute; two small requests.
                    val ended = before?.busy == true && !s.busy
                    if (before == null || ended || before.unseenTasks != s.unseenTasks || round % STUCK_EVERY == 0) {
                        val newest = runCatching { api.get<TasksBrief>("/api/tasks?limit=1").items.firstOrNull() }.getOrNull()
                        val waiting = runCatching { api.get<WorkersBrief>("/api/workers?status=needs_input&limit=5").items }.getOrDefault(emptyList())
                        activity.stuck.value = AgentActivityRules.needsUser(newest, waiting, System.currentTimeMillis() / 1000.0)
                        if (AgentActivityRules.finished(before, s, newest) && !activity.following.value) activity.doneAt.value = platform.uptimeMs()
                    }
                    before = s
                    round++
                    // the picture changed (on another device, or here)
                    if (s.avatarVersion >= 0) runCatching { avatar.sync(s.avatarVersion) }
                    // the name or tone changed in the chat ("반말로 해 줘") or on another device: 마이 → 에이전트 shows it without a reload
                    adopt(s)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (e.isNetwork) {
                        failures++
                        if (failures >= AgentActivityRules.OFFLINE_AFTER) activity.offline.value = true
                    }
                    if (e.status == 404) { activity.polledLine.value = null; return@coroutineScope }   // an older server: no status
                } catch (e: Exception) {
                    failures++
                    if (failures >= AgentActivityRules.OFFLINE_AFTER) activity.offline.value = true
                }
            }
            delay(pollMs)
        }
    }

    private fun adopt(s: AgentStatus) {
        s.agentName?.takeIf { it.isNotBlank() && it != profile?.agentName }?.let { name -> profile?.let { p -> profile = p.copy(agentName = name).also(onProfile) } }
        profile?.let { p -> ToneRules.synced(p, s.tone)?.let { profile = it; onProfile(it) } }
    }

    private companion object {
        /** How often the clocks of the character (the cheer, sleep, night) are looked at again. */
        const val CLOCK_MS = 1_000L
        /** How often (in polls) the stuck-job check runs on its own. */
        const val STUCK_EVERY = 10
        /** How often (in polls, about five minutes) the contact hours are asked for again. */
        const val HOURS_EVERY = 100
    }
}
