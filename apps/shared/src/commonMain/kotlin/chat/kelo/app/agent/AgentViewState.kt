package chat.kelo.app.agent

import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*


import org.jetbrains.compose.resources.StringResource
import java.time.YearMonth

/** A short message for the snackbar: a string resource with its arguments, or the server's own words. */
data class AgentMessage(val text: StringResource, val args: List<Any> = emptyList(), val detail: String? = null)

/** One list's loading state. */
data class Loadable<T>(val data: T? = null, val loading: Boolean = false, val failed: Boolean = false) {
    val loaded: Boolean get() = data != null
}

data class TaskList(val items: List<AgentTask> = emptyList(), val nextBefore: Long? = null, val loadingMore: Boolean = false)

data class Permissions(
    val pending: List<PendingApproval> = emptyList(),
    val rules: List<ApprovalRule> = emptyList(),
    val history: List<ApprovalRecord> = emptyList(),
    val historyNext: Double? = null,
    /** What the user allowed for a running task as a whole (one card for many steps) */
    val taskGrants: List<TaskPermission> = emptyList(),
    /** Approvals being answered right now */
    val answering: Set<String> = emptySet(),
)

data class Journal(val month: YearMonth, val entries: List<JournalEntry> = emptyList(), val gaps: List<JournalGap> = emptyList())
