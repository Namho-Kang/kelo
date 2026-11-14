package chat.kelo.app.upload

import okhttp3.MediaType
import okhttp3.RequestBody
import java.io.IOException
import java.io.InputStream

/**
 * One part of a file as a request body: a FRESH stream from [open] is opened, [skip] bytes are passed over, and exactly [length] bytes
 * are written, a chunk at a time (never the whole part in memory). [onProgress] gets the bytes of this part written so far.
 * A failure of the file itself (it ended early or could not be read) is kept in [readFailure]: it is not the connection's fault, so
 * the caller does not retry it.
 */
class PartBody(
    private val open: () -> InputStream,
    private val skip: Long,
    private val length: Long,
    private val onProgress: (Long) -> Unit = {},
    private val type: MediaType? = null,
) : RequestBody() {
    @Volatile var readFailure: IOException? = null
        private set

    override fun contentType() = type
    override fun contentLength() = length
    override fun isOneShot() = true

    override fun writeTo(sink: okio.BufferedSink) {
        val buf = ByteArray(CHUNK)
        var sent = 0L
        onProgress(0)
        val input = fromFile { open() }
        try {
            fromFile { skipFully(input, skip) }
            while (sent < length) {
                val n = fromFile { input.read(buf, 0, minOf(buf.size.toLong(), length - sent).toInt()) }
                if (n < 0) throw IOException("the file ended before the part did").also { readFailure = it }
                sink.write(buf, 0, n)   // a failure here is the connection's
                sent += n
                onProgress(sent)
            }
        } finally {
            runCatching { input.close() }
        }
    }

    /** Runs a step that touches the file: an IOException from it is the file's, not the connection's. */
    private inline fun <T> fromFile(step: () -> T): T = try { step() } catch (e: IOException) { readFailure = e; throw e }

    companion object {
        const val CHUNK = 128 * 1024

        /** Passes [count] bytes: skip() where the stream can, one read() where it cannot (so it also finds a stream that is too short). */
        fun skipFully(input: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) { left -= skipped; continue }
                if (input.read() < 0) throw IOException("the file ended before the part did")
                left--
            }
        }
    }
}
