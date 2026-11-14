package chat.kelo.app.core.e2e

import java.io.InputStream

/**
 * The server's side of a streamed request (envelope 0x02), for the tests and mock servers: the app itself only seals
 * ([Ke2eRequest.sealStreamed], [RequestBodyWriter]), it never opens such a request.
 */
private val REQ_AAD = Ke2e.ascii(Ke2e.AAD_REQUEST)
private const val MORE: Byte = 0x00
private const val LAST: Byte = 0x01

/** The server's side of [Ke2eRequest.sealStreamed]: the opened head and a reader for the body frames. */
fun Ke2eRequest.openStreamed(input: InputStream, server: X25519KeyPair): Pair<OpenedRequest, RequestBodyReader> {
    val fixed = ByteArray(1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES + 4)
    if (readFully(input, fixed) != fixed.size || fixed[0] != Ke2e.VERSION_STREAMED) {
        throw Ke2eException(Ke2eException.Kind.MALFORMED, "not a KE2E 0x02 envelope")
    }
    val kid = fixed.copyOfRange(1, 1 + Ke2e.KID_BYTES)
    if (!java.security.MessageDigest.isEqual(kid, ServerKey(server.publicKey).kidBytes)) {
        throw Ke2eException(Ke2eException.Kind.KEY_CHANGED, "unknown kid")
    }
    val n = Ke2eRequest.readU32(fixed, 1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES)
    if (n < Ke2e.TAG_BYTES || n > Ke2e.FRAME_READ_MAX) throw Ke2eException(Ke2eException.Kind.MALFORMED, "head length out of range")
    val ct = ByteArray(n.toInt())
    if (readFully(input, ct) != ct.size) throw Ke2eException(Ke2eException.Kind.TRUNCATED, "envelope cut short")
    val ctx = Ke2eContext.receiver(fixed.copyOfRange(1 + Ke2e.KID_BYTES, 1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES), server)
    val (h, body) = Ke2eRequest.splitInner(ctx.open(Ke2e.ascii(Ke2e.AAD_REQUEST), ct))
    if (body.isNotEmpty()) throw Ke2eException(Ke2eException.Kind.MALFORMED, "a 0x02 envelope carries its body in frames")
    return OpenedRequest(RequestHead.parse(h), ByteArray(0), ctx) to RequestBodyReader(input, ctx.requestBodyKey())
}

/** Reads until [b] is full or the stream ends. */
private fun readFully(input: InputStream, b: ByteArray): Int {
    var got = 0
    while (got < b.size) {
        val r = input.read(b, got, b.size - got)
        if (r < 0) break
        got += r
    }
    return got
}

/**
 * Copies [input] to the end as frames of at most 64 KiB; the final frame is marked last (an empty body: one empty last frame).
 * [onFrame] gets the plaintext bytes sent so far.
 */
fun RequestBodyWriter.copyFrom(input: InputStream, onFrame: (Long) -> Unit = {}) {
    val a = ByteArray(Ke2e.BODY_FRAME_MAX)
    val b = ByteArray(Ke2e.BODY_FRAME_MAX)
    var cur = a
    var curLen = readFully(input, cur)
    var sent = 0L
    while (true) {
        val next = if (cur === a) b else a
        val nextLen = if (curLen < Ke2e.BODY_FRAME_MAX) 0 else readFully(input, next)
        val last = nextLen == 0
        frame(cur.copyOf(curLen), last)
        sent += curLen
        onFrame(sent)
        if (last) break
        cur = next
        curLen = nextLen
    }
    a.fill(0); b.fill(0)
}

/** The server's side of a streamed request body: frames until the one flagged last. */
class RequestBodyReader internal constructor(private val input: InputStream, key: ByteArray) {
    private val cipher = FrameCipher(key)
    var finished = false
        private set

    /** The next body frame's plaintext, null after the last one (and the stream must end there). */
    fun next(): ByteArray? {
        if (finished) return null
        val len = ByteArray(4)
        if (!readFully(len, true)) throw Ke2eException(Ke2eException.Kind.TRUNCATED, "body ended before the last frame")
        val n = Ke2eRequest.readU32(len, 0)
        if (n < Ke2e.TAG_BYTES || n > Ke2e.BODY_FRAME_MAX + Ke2e.TAG_BYTES) throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "body frame length out of range")
        val c = ByteArray(n.toInt())
        if (!readFully(c, false)) throw Ke2eException(Ke2eException.Kind.TRUNCATED, "body ended inside a frame")
        cipher.tryOpen(REQ_AAD + MORE, c)?.let { return it }
        val last = cipher.tryOpen(REQ_AAD + LAST, c) ?: throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "body frame did not open")
        finished = true
        if (input.read() != -1) throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "bytes after the last body frame")
        return last
    }

    fun readAll(): ByteArray = java.io.ByteArrayOutputStream().also { o -> while (true) o.write(next() ?: break) }.toByteArray()

    private fun readFully(b: ByteArray, atStart: Boolean): Boolean {
        var got = 0
        while (got < b.size) {
            val r = input.read(b, got, b.size - got)
            if (r < 0) {
                if (got == 0 && atStart) return false
                throw Ke2eException(Ke2eException.Kind.TRUNCATED, "body ended inside a frame")
            }
            got += r
        }
        return true
    }
}
