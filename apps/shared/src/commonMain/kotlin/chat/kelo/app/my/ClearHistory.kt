package chat.kelo.app.my

import chat.kelo.app.Caches
import chat.kelo.app.core.Sessions

/**
 * 마이 → 대화 기록 삭제. The server (DELETE /api/messages) deletes the chat and what was made from it: the task log, background jobs,
 * approval history, notifications, follow-ups, day summaries and pictures of browser steps, and stops work in progress. Goals,
 * files, schedules, connectors and the keychain stay.
 */
object ClearHistory {
    /**
     * After the server said yes: this device forgets the same things. The kept chat is emptied (the next open must not show it), the
     * screens holding tasks, jobs, approvals or messages drop them ([Sessions.historyCleared]), and the phone's notifications, which
     * name tasks that are gone, are taken down.
     */
    fun forgetLocally(sessions: Sessions, cancelNotifications: () -> Unit) {
        sessions.keep(Caches.MESSAGES, emptyList())
        sessions.historyWasCleared()
        runCatching(cancelNotifications)
    }
}
