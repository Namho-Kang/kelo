package chat.kelo.app.notify

import chat.kelo.app.agent.ServerNotification

/** The notification channels, by what they are about. [id] is the system channel id (never shown). */
/**
 * The phone's notification channels. Only the channels of what waits for the owner (approvals and follow-ups, urgent cards) may put a
 * dot on the launcher icon; finished replies, schedule results and payment news do not ([badges]). A channel's badge setting cannot be
 * changed once made, so the quiet ones got new ids ("_v2") and the old ones are deleted ([RETIRED]).
 */
enum class Channel(val id: String, val badges: Boolean = false) {
    REPLIES("replies_v2"),
    APPROVALS("approvals", badges = true),
    PAYMENTS("payments_v2"),
    SCHEDULES("schedules_v2"),
    /** A card of a task marked urgent at setup (a cancelled seat): time matters, so it may sound even in Do Not Disturb */
    URGENT("urgent", badges = true),
    /** The quiet ongoing line while a reply is followed in the background */
    WORKING("working");

    companion object {
        /** Channel ids of older versions, made with a launcher badge: deleted when the channels are made */
        val RETIRED = listOf("replies", "payments", "schedules")
    }
}

/** Where tapping a notification takes the user. Written into the intent as one short string ([encode] / [decode]). */
sealed interface OpenTarget {
    data object Chat : OpenTarget
    /** The chat, scrolled to one message (a follow-up asking again) */
    data class ChatAt(val messageId: Long) : OpenTarget
    /** The chat, scrolled to a secure entry's card and briefly marked (a row in 답이 필요해요) */
    data class Entry(val requestId: String) : OpenTarget
    data class Task(val id: Long) : OpenTarget
    data object Approvals : OpenTarget
    data class Schedule(val id: Long) : OpenTarget
    /** 마이 → 결제 카드 */
    data object Wallet : OpenTarget
    /** 마이 → Keychain (behind its own lock): a key was saved from the chat */
    data object Keychain : OpenTarget
    /** The 작업 tab's running and finished work */
    data object Activity : OpenTarget
    /** The 작업 tab's agenda */
    data object Schedules : OpenTarget
    /** The 작업 tab's watchers (a notification carries no watcher id) */
    data object Watchers : OpenTarget
    /**
     * One browser tab, live (closing it goes back to where the user was); [control]: take it over at once (a human check to solve);
     * [picture]: the last picture the opener had of it (a chat card's data: URL), shown when the tab has closed and the server kept none
     */
    data class Tab(val id: String, val control: Boolean = false, val picture: String? = null) : OpenTarget {
        companion object {
            /** What "보기" on a chat's browser card opens: the tab its pictures came from (an older server names none: this phone's own). */
            fun ofCard(card: chat.kelo.app.chat.ChatItem.Browser): OpenTarget =
                if (card.tab.isNotBlank()) Tab(card.tab, picture = card.image) else OwnTab
        }
    }
    /** The tab this phone's chat replies work in (found with GET /api/devices) */
    data object OwnTab : OpenTarget
    /** 마이 → 운영자 (a beta request for Google sign-in came in) */
    data object Operator : OpenTarget
    /** 마이 → 모델 (a newer CLI is out); [provider]: that CLI's provider, whose box the page shows ("" = just the page) */
    data class ModelSettings(val provider: String = "") : OpenTarget
    /** 마이 → 서버 업데이트 (an update finished, or a newer server release is out; owner only) */
    data object ServerUpdate : OpenTarget
    /** 마이 → Kelo 서버 자원 (storage is filling up) */
    data object Storage : OpenTarget
    /** Keyguard: Kelo is locked after a restart: the chat with the unlock check started */
    data object Unlock : OpenTarget
    /** 마이 → Kelo 서버 자원 → 정리하기 (the storage notification's action) */
    data object StorageCleanup : OpenTarget

    fun encode(): String = when (this) {
        Chat -> "chat"
        is ChatAt -> "chat:$messageId"
        is Entry -> "entry:$requestId"
        is Task -> "task:$id"
        Approvals -> "approvals"
        is Schedule -> "schedule:$id"
        Wallet -> "wallet"
        Activity -> "activity"
        Schedules -> "schedules"
        Watchers -> "watchers"
        is Tab -> "tab:$id"
        OwnTab -> "own-tab"
        Operator -> "operator"
        is ModelSettings -> if (provider.isEmpty()) "model-settings" else "model-settings:$provider"
        ServerUpdate -> "server-update"
        Storage -> "storage"
        StorageCleanup -> "storage-cleanup"
        Unlock -> "unlock"
        Keychain -> "keychain"   // not sent by a notification today; encoded anyway, so nothing can crash on it
    }

    companion object {
        fun decode(s: String?): OpenTarget? {
            val t = s?.trim().orEmpty()
            return when {
                t == "chat" -> Chat
                t.startsWith("chat:") -> t.removePrefix("chat:").toLongOrNull()?.let(::ChatAt) ?: Chat
                t == "approvals" -> Approvals
                t == "wallet" -> Wallet
                t == "activity" -> Activity
                t == "schedules" -> Schedules
                t == "watchers" -> Watchers
                t == "own-tab" -> OwnTab
                t == "operator" -> Operator
                t == "model-settings" -> ModelSettings()
                t == "server-update" -> ServerUpdate
                t == "storage" -> Storage
                t == "storage-cleanup" -> StorageCleanup
                t == "unlock" -> Unlock
                t == "keychain" -> Keychain
                t.startsWith("model-settings:") -> ModelSettings(t.removePrefix("model-settings:"))
                t.startsWith("tab:") && t.length > 4 -> Tab(t.removePrefix("tab:"))
                t.startsWith("entry:") && t.length > 6 -> Entry(t.removePrefix("entry:"))
                t.startsWith("task:") -> t.removePrefix("task:").toLongOrNull()?.let(::Task)
                t.startsWith("schedule:") -> t.removePrefix("schedule:").toLongOrNull()?.let(::Schedule)
                else -> null
            }
        }
    }
}

/** The server's notification types the app turns into phone notifications. */
object NoteTypes {
    const val TASK_DONE = "task_done"
    const val TASK_FAILED = "task_failed"
    const val APPROVAL_WAITING = "approval_waiting"
    /** Keyguard: the server restarted and holds no key until an owner phone unlocks it (urgent) */
    const val KEYGUARD_LOCKED = "keyguard_locked"
    const val PAYMENT_AUTO = "payment_auto"
    const val SCHEDULE_DISABLED = "schedule_disabled"
    /** A run paused its scheduled job (body: why) */
    const val SCHEDULE_PAUSED = "schedule_paused"
    /** A watcher's condition held */
    const val WATCH_FIRED = "watch_fired"
    /** A watcher kept failing and turned itself off */
    const val WATCH_DISABLED = "watch_disabled"
    /** A message the agent sent without being asked */
    const val PROACTIVE = "proactive"
    /** The agent asks again about something that waits on the user (a login, a job, an approval, an answer) */
    const val FOLLOWUP = "followup"
    /** Someone asked to try Google sign-in (only on the operator's server; title and body name the address) */
    const val BETA_REQUEST = "beta_request"
    /** A newer claude / agy CLI has been out for a few days (title and body from the server: the versions and the update command) */
    const val CLI_UPDATE = "cli_update"
    /** The Kelo server updated itself (done / failed / rolled back) or a newer release is out (owner only; title and body from the server) */
    const val SERVER_UPDATE = "server_update"
    /** Storage is filling up: 80% / 95% of the cap, or the server's own disk almost full (title and body from the server) */
    const val STORAGE = "storage"
    /** The morning briefing (title "아침 브리핑", body its one-line summary); its card waits in the chat */
    const val BRIEFING = "briefing"
    val ALL = setOf(TASK_DONE, TASK_FAILED, APPROVAL_WAITING, PAYMENT_AUTO, SCHEDULE_DISABLED, SCHEDULE_PAUSED, WATCH_FIRED, WATCH_DISABLED, PROACTIVE, FOLLOWUP,
        BETA_REQUEST, CLI_UPDATE, SERVER_UPDATE, STORAGE, BRIEFING)
}

/**
 * When and how a notification is posted. Nothing is posted while the app is on screen (the user sees it there), nothing the server
 * already counts as seen, and nothing twice.
 */
object NotifyRules {
    fun shouldPost(foreground: Boolean, seen: Boolean, alreadyPosted: Boolean): Boolean = !foreground && !seen && !alreadyPosted

    /** The channel of a server notification: an urgent one (a task marked urgent at setup) has its own, louder channel. */
    fun channelOf(type: String, scheduleId: Long?, urgent: Boolean): Channel = if (urgent) Channel.URGENT else channel(type, scheduleId)

    fun channel(type: String, scheduleId: Long?): Channel = when (type) {
        // asked again about something that waits for the owner: with the approvals (it may badge the icon)
        NoteTypes.APPROVAL_WAITING, NoteTypes.FOLLOWUP -> Channel.APPROVALS
        NoteTypes.PAYMENT_AUTO -> Channel.PAYMENTS
        NoteTypes.SCHEDULE_DISABLED, NoteTypes.SCHEDULE_PAUSED, NoteTypes.WATCH_FIRED, NoteTypes.WATCH_DISABLED -> Channel.SCHEDULES
        NoteTypes.PROACTIVE, NoteTypes.BRIEFING -> Channel.REPLIES
        else -> if (scheduleId != null) Channel.SCHEDULES else Channel.REPLIES
    }

    /**
     * Where a server notification opens: an approval → the chat, where it waits as a live card at the bottom (a background job's or a
     * scheduled run's too; payments are answered in the app, never from the notification); a
     * paused or switched-off schedule → that schedule; a watcher → the watchers; a message the agent sent by itself → the chat.
     */
    fun target(n: ServerNotification): OpenTarget = when (n.type) {
        NoteTypes.APPROVAL_WAITING -> OpenTarget.Chat
        NoteTypes.PAYMENT_AUTO -> OpenTarget.Wallet
        NoteTypes.SCHEDULE_DISABLED, NoteTypes.SCHEDULE_PAUSED -> n.scheduleId?.let(OpenTarget::Schedule) ?: OpenTarget.Schedules
        NoteTypes.WATCH_FIRED, NoteTypes.WATCH_DISABLED -> OpenTarget.Watchers
        NoteTypes.PROACTIVE, NoteTypes.BRIEFING -> OpenTarget.Chat
        NoteTypes.FOLLOWUP -> n.messageId?.let(OpenTarget::ChatAt) ?: OpenTarget.Chat
        NoteTypes.BETA_REQUEST -> OpenTarget.Operator
        NoteTypes.CLI_UPDATE -> OpenTarget.ModelSettings(n.provider)   // My → Model at that CLI's box (an older server names none)
        NoteTypes.SERVER_UPDATE -> OpenTarget.ServerUpdate             // My → 서버 업데이트
        NoteTypes.STORAGE -> OpenTarget.Storage                        // My → Kelo 서버 자원 (its 정리하기 action: the cleanup screen)
        NoteTypes.KEYGUARD_LOCKED -> OpenTarget.Unlock                 // Kelo is locked: unlock it with this phone
        else -> n.taskId?.let(OpenTarget::Task) ?: OpenTarget.Chat
    }

    /**
     * Of a page of server notifications (newest first), the ones to post: every type (also ones this app does not know yet), not seen,
     * newer than [lastPosted]; oldest first so they appear in order.
     */
    fun fresh(items: List<ServerNotification>, lastPosted: Long): List<ServerNotification> =
        items.filter { it.id > lastPosted && !it.seen }.sortedBy { it.id }

    /** Types whose notices carry their own title (a watcher's name, the agent's name, the versions...). */
    val OWN_TITLES = setOf(NoteTypes.WATCH_FIRED, NoteTypes.WATCH_DISABLED, NoteTypes.PROACTIVE, NoteTypes.BETA_REQUEST, NoteTypes.CLI_UPDATE,
        NoteTypes.SERVER_UPDATE, NoteTypes.STORAGE)

    /**
     * The title a notification is posted with: the app's own words for a type it knows ([own]); for a type it does not know yet
     * (a security warning, a task waiting for an answer...) the server's own title, or [hint] ("open Kelo") when it has none.
     */
    fun titleOf(n: ServerNotification, own: String, hint: String): String = when {
        n.type !in NoteTypes.ALL -> n.title.ifBlank { hint }
        n.type in OWN_TITLES && n.title.isNotBlank() -> n.title
        else -> own
    }

    /** The cursor after a check: never backwards. */
    fun nextCursor(lastPosted: Long, serverLast: Long, items: List<ServerNotification>): Long =
        maxOf(lastPosted, serverLast, items.maxOfOrNull { it.id } ?: 0)

    /**
     * The first line of an answer, for a notification: Markdown marks taken off, at most [max] characters. Empty when the answer
     * has no words.
     */
    fun firstLine(answer: String, max: Int = 120): String {
        var inCode = false
        var line = ""
        for (raw in answer.lineSequence()) {
            val t = raw.trim()
            if (t.startsWith("```")) { inCode = !inCode; continue }   // code is not a sentence to show
            if (inCode || t.isEmpty() || t.matches(Regex("^[-*_=|:\\s]+$"))) continue
            line = t
            break
        }
        val plain = line
            .replace(Regex("^#{1,6}\\s+"), "")
            .replace(Regex("^([-*+]|\\d+[.)])\\s+"), "")
            .replace(Regex("^>\\s*"), "")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_`~]+"), "")
            .trim()
        return if (plain.length <= max) plain else plain.take(max - 1).trimEnd() + "…"
    }

    /** Every Kelo notification is in one bundle: the phone stacks them, and tapping the bundle shows them all (owner 2026-11-12). */
    const val GROUP = "kelo"

    /** The bundle's own notification id (never one of a notification's). */
    val SUMMARY_ID: Int = idFor("summary", GROUP)

    /** Where tapping the bundle goes: the chat (approvals wait there; every other notice is in the chat too). */
    val SUMMARY_TARGET: OpenTarget = OpenTarget.Chat

    /** The bundle shows the newest titles, at most this many lines. */
    const val SUMMARY_LINES = 5

    /**
     * Notifications that only tell something (a reply, a finished job, a schedule's result, payment news): opening the app takes them
     * down. Ones that wait for an answer (an approval, an urgent card, a dial request) stay until they are answered.
     */
    fun isNews(channel: Channel): Boolean = channel == Channel.REPLIES || channel == Channel.SCHEDULES || channel == Channel.PAYMENTS

    /** The channel a posted notification is on, by its system channel id (null: not one of ours). */
    fun channelOfId(id: String?): Channel? = Channel.entries.firstOrNull { it.id == id }

    /** The buttons under a notification, besides tapping it: a storage warning offers 정리하기 (the cleanup screen). */
    fun actions(type: String): List<OpenTarget> = if (type == NoteTypes.STORAGE) listOf(OpenTarget.StorageCleanup) else emptyList()

    /** Notification ids, per kind, so one task or approval never shows twice and a newer one replaces the older. */
    fun idFor(kind: String, key: String): Int = (kind + ":" + key).hashCode() and 0x7fffffff
}

/** An FCM data message: {type, id, title?, body?, message_id?}. The server sends only these; the app fetches the rest itself. */
data class PushMessage(val type: String, val id: Long?, val title: String?, val body: String?, val messageId: Long? = null)

object PushData {
    private val ID_WORD = Regex("^[a-z_]{2,40}$")

    /** Null when it is not a Kelo message (no type, or a type that does not look like an id word). Types this app does not know pass. */
    fun parse(data: Map<String, String>): PushMessage? {
        val type = data["type"]?.trim().orEmpty()
        if (type != WAKE && type != DEVICE_TASK && !ID_WORD.matches(type)) return null
        return PushMessage(
            type = type,
            id = data["id"]?.trim()?.toLongOrNull(),
            title = data["title"]?.trim()?.takeIf { it.isNotEmpty() },
            body = data["body"]?.trim()?.takeIf { it.isNotEmpty() },
            messageId = data["message_id"]?.trim()?.toLongOrNull(),
        )
    }

    /** "something is new, check": no content of its own */
    const val WAKE = "check"
    /** Work this phone may have to do after an approval (a text, the dialer, a calendar change): only the task's id */
    const val DEVICE_TASK = "device_task"
}
