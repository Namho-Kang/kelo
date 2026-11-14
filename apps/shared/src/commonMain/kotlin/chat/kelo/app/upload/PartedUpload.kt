package chat.kelo.app.upload

import chat.kelo.app.core.ApiError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * One file sent in parts (POST start, PUT every part, POST finish). Each part is read from a fresh stream (skip to its start, then
 * its bytes): the file is never held in memory, and a part that must be sent again just opens the file again.
 *
 * Failures: a part is tried again up to [backoffMs].size times on a network error, a 5xx (not the 507 "no room"), a timeout or a
 * proxy's bare 404; after a broken connection the server is asked which parts it holds first (the part may have arrived). A 422
 * or 413 is never tried again. When finish says a part is missing, those parts are sent once and finish is asked again.
 * When anything fails or the coroutine is cancelled the part-upload is deleted on the server (best effort), so it does not use up one
 * of the three open ones.
 *
 * Resuming after the app was closed is out of scope: the id is kept only in memory, and the server removes open part-uploads that
 * were left alone for 24 hours.
 */
class PartedUpload(
    private val calls: PartCalls,
    private val backoffMs: List<Long> = BACKOFF_MS,
) {
    /** The stored path. [onProgress] gets (bytes of the file sent, [size]) and never goes backwards (a part sent again does not undo it). */
    suspend fun run(name: String, size: Long, open: () -> InputStream, onProgress: (Long, Long) -> Unit): String {
        val upload = calls.start(name, size)
        try {
            return transfer(upload, size, open, onProgress)
        } catch (e: Throwable) {
            discard(upload.id)
            throw e
        }
    }

    private suspend fun transfer(upload: PartUpload, size: Long, open: () -> InputStream, onProgress: (Long, Long) -> Unit): String {
        val sizes = UploadPlan.partSizes(size, upload.partSize)
        if (sizes.size != upload.parts) throw ApiError(ApiError.NO_RESPONSE, "the server cut the file in ${upload.parts} parts, not ${sizes.size}")
        var high = -1L   // so that the first report, (0, size), goes out
        fun report(sent: Long) {
            if (sent > high) { high = sent; onProgress(sent, size) }
        }
        report(0)
        val held = HashSet<Int>()

        suspend fun send(i: Int) {
            val start = i * upload.partSize
            var attempt = 0
            while (true) {
                val body = PartBody(open, skip = start, length = sizes[i], onProgress = { report(start + it) })
                try {
                    calls.put(upload.id, i, body)
                    held += i
                    report(start + sizes[i])
                    return
                } catch (e: ApiError) {
                    body.readFailure?.let { throw it }   // the file, not the connection: sending again would fail the same way
                    if (!retryable(e) || attempt >= backoffMs.size) throw e
                    delay(backoffMs[attempt++])
                    if (e.isNetwork) {
                        // the connection broke: the part may have arrived anyway
                        try { held += calls.received(upload.id) } catch (_: ApiError) {}
                        if (i in held) { report(start + sizes[i]); return }
                    }
                }
            }
        }

        for (i in sizes.indices) send(i)
        return finish(upload.id, sizes.indices, held, ::send)
    }

    /** Finish; when it says a part is missing (422), the parts the server does not hold are sent once and finish is asked again. */
    private suspend fun finish(id: String, parts: IntRange, held: MutableSet<Int>, send: suspend (Int) -> Unit): String {
        try {
            return finishOnce(id)
        } catch (e: ApiError) {
            if (e.status != 422) throw e
            val now = try { calls.received(id) } catch (_: ApiError) { throw e }
            held += now
            val missing = parts.filter { it !in now }
            if (missing.isEmpty()) throw e
            missing.forEach { send(it) }
            return finishOnce(id)
        }
    }

    /** POST finish, tried again on a transient failure (a finish that did arrive and is asked again answers 404: that error is then shown). */
    private suspend fun finishOnce(id: String): String {
        var attempt = 0
        while (true) {
            try {
                return calls.finish(id)
            } catch (e: ApiError) {
                if (!retryable(e) || attempt >= backoffMs.size) throw e
                delay(backoffMs[attempt++])
            }
        }
    }

    /** DELETE the part-upload, in the background of a failure or a cancel: at most [DISCARD_MS], and what goes wrong is ignored. */
    private suspend fun discard(id: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(DISCARD_MS) { try { calls.cancel(id) } catch (_: ApiError) {} }
        }
    }

    companion object {
        /** The waits before the 1st, 2nd and 3rd new try of one part. */
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L)
        const val DISCARD_MS = 5_000L

        /**
         * Worth sending the same bytes again: no answer, a server error (not 507), a timeout or rate limit, or a 404 that carries no words
         * of the server's (a proxy's). The server's own 404 ("unknown upload") has words: the upload is gone, nothing to retry.
         */
        fun retryable(e: ApiError): Boolean = e.isTransient || (e.status == 404 && e.detail == "HTTP 404")
    }
}
