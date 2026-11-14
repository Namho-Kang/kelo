package chat.kelo.app.my

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The owner updating the Kelo server itself from the app (My → 서버 업데이트). The server hands the work to its updater, which
 * fetches the new release, checks it is the official one, backs up, restarts, checks the server works and goes back to the
 * previous version by itself when it does not. Owner only: any other device gets 403, a server from before this feature 404, and a
 * server whose updater is not set up says state "unavailable"; in all three the app shows nothing at all.
 *
 *   GET  /api/server-update             the status below
 *   POST /api/server-update/check       look at the release list again
 *   POST /api/server-update/update      202 with a running job; 409 {"detail"} (no update, one is running, built from source)
 *   POST /api/server-update/rollback    202 with a running job (action "rollback"); 409 when there is no rollback point
 *   PUT  /api/server-update/settings    {"auto_update": bool}
 *
 * During an update the server restarts, so for a few minutes the status cannot be read (connection errors, 502/503): [follow]
 * keeps asking every [POLL_MS] through those and gives up only after [GIVE_UP_MS] without an answer.
 * Plain Kotlin (shared with the desktop app); the screens only turn [ServerUpdateRules] answers into words.
 */
@Serializable
data class ServerUpdateStatus(
    /** ok | local (built from source on the server machine: no button, show [message]) | unavailable (show nothing) */
    val state: String = ServerUpdateRules.UNAVAILABLE,
    /** Already in the user's language; for "local", what to do instead */
    val message: String? = null,
    val current: String? = null,
    val latest: ServerRelease? = null,
    @SerialName("update_available") val updateAvailable: Boolean = false,
    /** Epoch seconds of the last look at the release list */
    @SerialName("checked_at") val checkedAt: Double? = null,
    @SerialName("auto_update") val autoUpdate: Boolean = false,
    /** Tonight's planned local time ("03:50") while [autoUpdate] is on */
    @SerialName("auto_time") val autoTime: String? = null,
    val job: ServerUpdateJob? = null,
    val rollback: ServerRollbackPoint? = null,
)

@Serializable
data class ServerRelease(
    val version: String = "",
    /** ISO date ("2026-11-01T00:00:00Z") */
    val date: String? = null,
    /** Release notes: plain text, may have line breaks */
    val notes: String? = null,
)

@Serializable
data class ServerUpdateJob(
    val id: String = "",
    /** update | rollback (the owner pressed "go back to …") */
    val action: String = ServerUpdateRules.ACTION_UPDATE,
    /** Started by the nightly automatic update */
    val auto: Boolean = false,
    /** running | done | failed | rolled_back | rollback_failed */
    val state: String = ServerUpdateRules.RUNNING,
    /** preflight | download | verify | backup | restart | health | rollback | self_update | done */
    val step: String? = null,
    val progress: Double? = null,
    val from: String? = null,
    val to: String? = null,
    /** English technical detail, shown small under the result */
    val error: String? = null,
    /** rolled_back: the data was put back from the backup made just before the update */
    @SerialName("data_restored") val dataRestored: Boolean = false,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("finished_at") val finishedAt: Double? = null,
)

@Serializable
data class ServerRollbackPoint(
    val available: Boolean = false,
    val version: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
)

object ServerUpdateRules {
    const val PATH = "/api/server-update"
    const val OK = "ok"
    const val LOCAL = "local"
    const val UNAVAILABLE = "unavailable"
    const val ACTION_UPDATE = "update"
    const val ACTION_ROLLBACK = "rollback"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val ROLLED_BACK = "rolled_back"
    const val ROLLBACK_FAILED = "rollback_failed"

    const val POLL_MS = 2_000L
    /** While the server restarts it cannot answer; only this long without any answer means something is wrong. */
    const val GIVE_UP_MS = 15 * 60_000L
    /** After giving up, ask now and then, so the result still shows once the server is back. */
    const val SLOW_POLL_MS = 30_000L
    /** A job that keeps saying "running" (the server answers) is followed at most this long. */
    const val MAX_FOLLOW_MS = 60 * 60_000L
    /** A failed or undone update keeps its card at the top of My for a day. */
    const val RECENT_S = 24 * 3600.0
    /** The page shows the last result for a week. */
    const val RESULT_S = 7 * 24 * 3600.0

    /** The 서버 업데이트 row and page exist: an owner sign-in on a server whose updater is set up. */
    fun shown(st: ServerUpdateStatus?): Boolean = st != null && (st.state == OK || st.state == LOCAL)

    /** GET's answer as the app keeps it: null (show nothing) for "unavailable" and for a refusal (403) or an older server (404). */
    suspend fun load(api: KeloApi): ServerUpdateStatus? = try {
        api.get<ServerUpdateStatus>(PATH).takeIf(::shown)
    } catch (e: ApiError) {
        if (e.status == 403 || e.status == 404) null else throw e
    }

    fun running(st: ServerUpdateStatus?): Boolean = st?.job?.state == RUNNING

    /** The 업데이트 button: a newer release is out, the server runs released images and nothing else runs. */
    fun canUpdate(st: ServerUpdateStatus?): Boolean =
        st != null && st.state == OK && st.updateAvailable && !st.latest?.version.isNullOrBlank() && !running(st)

    /** The quieter "go back to <version>": a rollback point is kept and nothing runs. */
    fun canRollback(st: ServerUpdateStatus?): Boolean =
        st != null && st.state == OK && st.rollback?.available == true && !st.rollback.version.isNullOrBlank() && !running(st)

    /** The nightly switch (not for a server built from source). */
    fun showAuto(st: ServerUpdateStatus?): Boolean = st?.state == OK

    /** "Tonight around 03:50": only while automatic updates are on and the server named the time. */
    fun tonight(st: ServerUpdateStatus?): String? {
        if (st == null || !st.autoUpdate || st.state != OK) return null
        return st.autoTime?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 0..100 for the progress bar. */
    fun percent(job: ServerUpdateJob?): Int = (job?.progress ?: 0.0).coerceIn(0.0, 100.0).toInt()

    /** When the job ended (or started, from a server that leaves the end out). */
    private fun endedAt(job: ServerUpdateJob): Double? = job.finishedAt ?: job.startedAt

    private fun within(job: ServerUpdateJob, nowS: Double, span: Double): Boolean = endedAt(job)?.let { nowS - it < span } ?: true

    /** The highlighted card at the top of My home. */
    sealed interface Card {
        data class Available(val version: String) : Card
        data class Running(val rollback: Boolean, val percent: Int, val step: Step) : Card
        data object Failed : Card
        data object RolledBack : Card
        data object RollbackFailed : Card
    }

    /**
     * Which card My home shows, if any: a job running; else a failed / undone update (or a go-back that did not finish) from the
     * last [RECENT_S]; else a newer release. A server built from source shows none.
     */
    fun card(st: ServerUpdateStatus?, nowS: Double): Card? {
        if (st == null || st.state != OK) return null
        val job = st.job
        if (job != null && job.state == RUNNING) return Card.Running(job.action == ACTION_ROLLBACK, percent(job), step(job))
        if (job != null && within(job, nowS, RECENT_S)) {
            when (job.state) {
                ROLLBACK_FAILED -> return Card.RollbackFailed
                ROLLED_BACK -> return Card.RolledBack
                FAILED -> return Card.Failed
            }
        }
        if (st.updateAvailable) st.latest?.version?.takeIf { it.isNotBlank() }?.let { return Card.Available(it) }
        return null
    }

    /** What the running job is doing, for its label. */
    enum class Step { PREFLIGHT, DOWNLOAD, VERIFY, BACKUP, RESTART, HEALTH, ROLLBACK, SELF_UPDATE, DONE, WORKING }

    fun step(job: ServerUpdateJob?): Step = when (job?.step) {
        "preflight" -> Step.PREFLIGHT
        "download" -> Step.DOWNLOAD
        "verify" -> Step.VERIFY
        "backup" -> Step.BACKUP
        "restart" -> Step.RESTART
        "health" -> Step.HEALTH
        "rollback" -> Step.ROLLBACK
        "self_update" -> Step.SELF_UPDATE
        "done" -> Step.DONE
        else -> Step.WORKING
    }

    /** How a finished job ended, for the page's result card. */
    sealed interface Result {
        val auto: Boolean
        val error: String?
        /** Updated and healthy; [version] null when the server named none */
        data class Updated(val version: String?, override val auto: Boolean) : Result { override val error: String? get() = null }
        /** The owner's go-back finished */
        data class WentBack(val version: String?, val dataRestored: Boolean, override val auto: Boolean) : Result {
            override val error: String? get() = null
        }
        /** Stopped before changing anything ([goBack]: it was a go-back that could not start) */
        data class Failed(val goBack: Boolean, override val error: String?, override val auto: Boolean) : Result
        /** The new version failed its check and the server went back to [version] by itself */
        data class RolledBack(val version: String?, val dataRestored: Boolean, override val error: String?, override val auto: Boolean) : Result
        /** Going back did not finish: the owner has to look at the server machine */
        data class RollbackFailed(override val error: String?, override val auto: Boolean) : Result
    }

    /** The last job's result, while it is recent enough to show ([RESULT_S]); null while running or when there is none. */
    fun result(st: ServerUpdateStatus?, nowS: Double): Result? {
        val job = st?.job ?: return null
        if (job.state == RUNNING || !within(job, nowS, RESULT_S)) return null
        val error = job.error?.trim()?.takeIf { it.isNotEmpty() }
        val goBack = job.action == ACTION_ROLLBACK
        return when (job.state) {
            DONE -> if (goBack) Result.WentBack(job.to.clean(), job.dataRestored, job.auto) else Result.Updated(job.to.clean() ?: st.current.clean(), job.auto)
            FAILED -> Result.Failed(goBack, error, job.auto)
            ROLLED_BACK -> Result.RolledBack(job.from.clean(), job.dataRestored, error, job.auto)
            ROLLBACK_FAILED -> Result.RollbackFailed(error, job.auto)
            else -> null
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** The release date in epoch seconds ("2026-11-01T00:00:00Z", "2026-11-01T09:00:00+09:00" or "2026-11-01"); null if unreadable. */
    fun releaseSeconds(date: String?): Double? {
        val d = date?.trim().orEmpty()
        if (d.isEmpty()) return null
        return runCatching { java.time.OffsetDateTime.parse(d).toEpochSecond().toDouble() }
            .recoverCatching { java.time.Instant.parse(d).epochSecond.toDouble() }
            .recoverCatching { java.time.LocalDate.parse(d.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble() }
            .getOrNull()
    }

    /**
     * Whether an answer while following tells how the followed job ([id]) stands. Only the first GET decides whether the feature
     * shows; while a job is followed, a server that is coming back may say "unavailable" or name no job (or another one): that is
     * waited through like a connection error.
     */
    fun answers(st: ServerUpdateStatus, id: String): Boolean {
        val job = st.job ?: return false
        if (st.state != OK) return false
        return id.isEmpty() || job.id.isEmpty() || job.id == id
    }

    /** A failure the follower waits through: the server restarting (no connection, 5xx, timeouts) or its route briefly missing. */
    fun passing(e: ApiError): Boolean = e.isTransient || e.status == 404

    /** Where a followed job stands: [waiting] while the server does not answer, [gaveUp] after [GIVE_UP_MS] of that (or a refusal). */
    data class Follow(val status: ServerUpdateStatus, val waiting: Boolean = false, val gaveUp: Boolean = false)

    /**
     * Ask for the status every [POLL_MS] until the job is no longer running. Connection errors and 5xx answers are expected while the
     * server restarts: they only set [Follow.waiting], as do a 404, state "unavailable" and an answer without the followed job (by
     * its id); after [GIVE_UP_MS] without any answer [Follow.gaveUp] is set and the asking
     * slows to [SLOW_POLL_MS], so the result still shows when the server comes back. A refusal (401, 403) stops at once (gaveUp).
     * The last [Follow] is returned; [onUpdate] gets every step.
     */
    suspend fun follow(
        start: ServerUpdateStatus,
        fetch: suspend () -> ServerUpdateStatus,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
        onUpdate: (Follow) -> Unit = {},
    ): Follow {
        var cur = Follow(start)
        val began = now()
        var lastAnswer = began
        val id = start.job?.id.orEmpty()
        while (running(cur.status)) {
            if (now() - began >= MAX_FOLLOW_MS) return cur.copy(waiting = false, gaveUp = true).also(onUpdate)
            sleep(if (cur.gaveUp) SLOW_POLL_MS else POLL_MS)
            val st = try {
                fetch()
            } catch (e: ApiError) {
                if (!passing(e)) return cur.copy(waiting = false, gaveUp = true).also(onUpdate)
                null
            }
            cur = if (st != null && answers(st, id)) {
                lastAnswer = now()
                Follow(st)
            } else {
                cur.copy(waiting = true, gaveUp = now() - lastAnswer >= GIVE_UP_MS)
            }
            onUpdate(cur)
        }
        return cur
    }

    /** [follow] against the server. */
    suspend fun follow(api: KeloApi, start: ServerUpdateStatus, onUpdate: (Follow) -> Unit): Follow =
        follow(start, fetch = { api.get<ServerUpdateStatus>(PATH) }, onUpdate = onUpdate)

    private val EMPTY = JsonObject(emptyMap())

    /** POST …/update or …/rollback; a refusal (409 with the reason in the user's language) comes back as an [ApiError]. */
    suspend fun start(api: KeloApi, rollback: Boolean): ServerUpdateStatus =
        api.post(if (rollback) "$PATH/rollback" else "$PATH/update", EMPTY)

    suspend fun check(api: KeloApi): ServerUpdateStatus = api.post("$PATH/check", EMPTY)

    suspend fun setAuto(api: KeloApi, on: Boolean): ServerUpdateStatus =
        api.put("$PATH/settings", JsonObject(mapOf("auto_update" to JsonPrimitive(on))))
}
