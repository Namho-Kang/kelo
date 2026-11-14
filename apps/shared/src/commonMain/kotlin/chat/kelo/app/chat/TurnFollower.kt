package chat.kelo.app.chat

import chat.kelo.app.core.ApiError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Web api.ts talk(): start a reply, then follow it with short requests instead of one long stream. A request that fails for a
 * passing reason (no signal, a gateway hiccup) is made again from the same place, so nothing is lost and the reply keeps running
 * on the server meanwhile. Returns when the reply is over.
 *
 * Cancelling the calling coroutine is the Stop button (or leaving the screen): the server is told to stop with DELETE /api/turns/{id}.
 */
class TurnFollower(
    private val api: TurnApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },   // last: callers pass it as the trailing lambda
) {
    suspend fun talk(
        text: String, timezone: String, answers: List<ReplyAnswer> = emptyList(), secrets: String? = null,
        attachments: List<String> = emptyList(), autoText: Boolean = false, onEvent: (TurnEvent) -> Unit,
    ) {
        val id = when {
            attachments.isNotEmpty() -> api.start(text, timezone, answers, secrets, attachments, autoText)   // files kept with the message
            secrets != null -> api.start(text, timezone, answers, secrets)   // the owner sent a held-back message as it is ([SecretGuard])
            answers.isEmpty() -> api.start(text, timezone)
            else -> api.start(text, timezone, answers)
        }
        follow(id, onEvent)
    }

    /**
     * Follows the reply [id] until it is over: one started here, or one the server started for this phone (a secure entry saved).
     * Over its WebSocket first (opened again from the last event after a break); where sockets do not get through, with the
     * long-poll for a while ([SocketGate]).
     */
    suspend fun follow(id: String, onEvent: (TurnEvent) -> Unit) {
        try {
            var after = 0
            if (SocketGate.open(clock())) {
                var tries = 0
                while (tries < SOCKET_TRIES) {
                    val before = after
                    val end = try {
                        api.socket(id, after) { seq, ev ->
                            if (seq == after) { onEvent(ev); after++ } else if (seq > after) throw SocketGap()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SocketEnd.Broken
                    }
                    when (end) {
                        is SocketEnd.Done -> return
                        SocketEnd.Gone -> throw ApiError(404, "that reply is gone (it was stopped, or finished a while ago)")
                        SocketEnd.Unavailable -> break
                        SocketEnd.Broken -> {
                            tries = if (after > before) 1 else tries + 1   // progress resets the count
                            if (tries == 2) onEvent(TurnEvent.Reconnecting)
                            sleep(backoff(tries))
                        }
                    }
                }
                if (tries >= SOCKET_TRIES) SocketGate.block(clock())   // sockets do not get through here: poll for a while
            }
            pollFrom(id, after, onEvent)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { api.stop(id) } }
            }
            throw e
        }
    }

    private suspend fun pollFrom(id: String, from: Int, onEvent: (TurnEvent) -> Unit) {
        var after = from
        var failures = 0
        while (true) {
            val r = try {
                api.poll(id, after)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransient(e) || ++failures >= GIVE_UP_AFTER) throw e
                if (failures == 2) onEvent(TurnEvent.Reconnecting)
                sleep(backoff(failures))
                continue
            }
            failures = 0
            r.events.forEach(onEvent)
            after = r.next
            if (r.done) return
        }
    }

    private class SocketGap : Exception()

    companion object {
        /** Failed requests in a row (about two minutes) before the connection counts as lost. */
        const val GIVE_UP_AFTER = 30

        /** Socket attempts in a row without a new event before the follower polls instead. */
        const val SOCKET_TRIES = 3
        private const val STOP_TIMEOUT_MS = 10_000L

        /** Anything that is not an answer from the server (a broken line, an unreadable body) counts as passing. */
        fun isTransient(e: Throwable): Boolean = e !is ApiError || e.isTransient

        fun backoff(failures: Int): Long = minOf(500L * failures, 4000L)
    }
}

/** Whether sockets are tried at all: one per app process. After a route refused them, they are skipped for [BLOCK_MS]. */
object SocketGate {
    const val BLOCK_MS = 10 * 60_000L
    @Volatile private var until = 0L
    fun open(now: Long): Boolean = now >= until
    fun block(now: Long) { until = now + BLOCK_MS }
    internal fun reset() { until = 0L }
}
