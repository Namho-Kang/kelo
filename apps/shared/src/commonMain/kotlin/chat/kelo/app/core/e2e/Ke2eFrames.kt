package chat.kelo.app.core.e2e

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * ChaCha20-Poly1305 with an exported key and a counting nonce: `nonce = 4 zero bytes ‖ u64 BE counter`, counter from 0, one per
 * frame. Used by the response frames (aad = "kelo-e2e v1 res" ‖ flag) and the WebSocket (aad = "kelo-e2e v1 ws").
 */
class FrameCipher(key: ByteArray) {
    private val key = KeyParameter(key.copyOf())

    init {
        require(key.size == 32) { "frame key must be 32 bytes" }
    }

    /** The next frame's counter. */
    var counter: Long = 0
        private set

    fun seal(aad: ByteArray, pt: ByteArray): ByteArray {
        val out = run(true, counter, aad, pt)
        counter++
        return out
    }

    /** Opens the next frame; on failure the counter does not move (the caller may try the other flag). */
    fun tryOpen(aad: ByteArray, ct: ByteArray): ByteArray? {
        if (ct.size < Ke2e.TAG_BYTES) return null
        val pt = try { run(false, counter, aad, ct) } catch (e: Exception) { return null }
        counter++
        return pt
    }

    private fun run(encrypt: Boolean, n: Long, aad: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(key, Ke2e.TAG_BYTES * 8, nonce(n), aad))
        val out = ByteArray(c.getOutputSize(input.size))
        var len = c.processBytes(input, 0, input.size, out, 0)
        len += c.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    companion object {
        fun nonce(n: Long): ByteArray = ByteArray(12).also { b -> for (i in 0 until 8) b[4 + i] = (n ushr (56 - 8 * i)).toByte() }
    }
}

private const val MORE: Byte = 0x00
private const val LAST: Byte = 0x01
private val RES_AAD = Ke2e.ascii(Ke2e.AAD_RESPONSE)
private fun resAad(flag: Byte): ByteArray = RES_AAD + flag

/**
 * Writes a response as frames (spec 1.4): `u32 BE length of c ‖ c`. Frame 0 is the head; the body follows in frames of at most
 * [Ke2e.BODY_FRAME_MAX] (a server-sent-events stream: one frame per event). The very last frame has flag 0x01.
 * The app never writes responses; this is the server's side for tests and a local mock.
 */
class ResponseWriter(private val out: OutputStream, key: ByteArray) {
    private val cipher = FrameCipher(key)
    private var done = false

    fun frame(pt: ByteArray, last: Boolean) {
        check(!done) { "the last frame was already written" }
        val c = cipher.seal(resAad(if (last) LAST else MORE), pt)
        out.write(Ke2eRequest.u32(c.size))
        out.write(c)
        if (last) done = true
        out.flush()
    }

    fun head(head: ResponseHead, last: Boolean = false) = frame(head.toJsonBytes(), last)

    /** The whole body in frames of at most 64 KiB, the final one marked last (an empty body: one empty last frame). */
    fun body(bytes: ByteArray) {
        if (bytes.isEmpty()) return frame(ByteArray(0), true)
        var at = 0
        while (at < bytes.size) {
            val end = minOf(bytes.size, at + Ke2e.BODY_FRAME_MAX)
            frame(bytes.copyOfRange(at, end), end == bytes.size)
            at = end
        }
    }
}

/**
 * Reads a response's frames from the outer body. [head] reads frame 0; then either [nextFrame] (one server-sent event per frame)
 * or [body] (a plain stream of the body's bytes, for downloads). Everything that is not exactly right is a [Ke2eException]: a
 * frame that does not open (wrong key, counter gap, reordering), a frame larger than [Ke2e.FRAME_READ_MAX], the stream ending
 * before the last frame (truncation), bytes after the last frame.
 */
class ResponseReader(private val input: InputStream, key: ByteArray) {
    private val cipher = FrameCipher(key)
    private var head: ResponseHead? = null
    /** The last frame has been read (and the stream checked to end there). */
    var finished: Boolean = false
        private set

    /** Frame 0: the real status and headers. */
    fun head(): ResponseHead {
        head?.let { return it }
        val pt = readFrame() ?: throw Ke2eException(Ke2eException.Kind.TRUNCATED, "no response head")
        return ResponseHead.parse(pt).also { head = it }
    }

    /** The next body frame's plaintext, or null after the last frame. */
    fun nextFrame(): ByteArray? {
        head()
        return readFrame()
    }

    /** The body as a stream (reads frames as it goes; throws [Ke2eException] on truncation or a bad frame). */
    val body: InputStream by lazy { BodyStream() }

    private fun readFrame(): ByteArray? {
        if (finished) return null
        val lenBytes = ByteArray(4)
        if (!readFully(lenBytes, atStart = true)) throw Ke2eException(Ke2eException.Kind.TRUNCATED, "stream ended before the last frame")
        val n = Ke2eRequest.readU32(lenBytes, 0)
        if (n < Ke2e.TAG_BYTES || n > Ke2e.FRAME_READ_MAX) throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "frame length out of range")
        val c = ByteArray(n.toInt())
        if (!readFully(c, atStart = false)) throw Ke2eException(Ke2eException.Kind.TRUNCATED, "stream ended inside a frame")
        // the flag is only in the aad: most frames are "more", so that is tried first
        cipher.tryOpen(resAad(MORE), c)?.let { return it }
        val last = cipher.tryOpen(resAad(LAST), c) ?: throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "frame did not open")
        finished = true
        if (input.read() != -1) throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "bytes after the last frame")
        return last
    }

    /** False when the stream ended before the first byte and [atStart]; throws on an end in the middle. */
    private fun readFully(b: ByteArray, atStart: Boolean): Boolean {
        var got = 0
        while (got < b.size) {
            val r = try { input.read(b, got, b.size - got) } catch (e: EOFException) { -1 }
            if (r < 0) {
                if (got == 0 && atStart) return false
                throw Ke2eException(Ke2eException.Kind.TRUNCATED, "stream ended inside a frame")
            }
            got += r
        }
        return true
    }

    private inner class BodyStream : InputStream() {
        private var buf = ByteArray(0)
        private var at = 0

        private fun fill(): Boolean {
            while (at >= buf.size) {
                buf = nextFrame() ?: return false
                at = 0
            }
            return true
        }

        override fun read(): Int = if (!fill()) -1 else buf[at++].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, buf.size - at)
            buf.copyInto(b, off, at, at + n)
            at += n
            return n
        }

        override fun available(): Int = buf.size - at
        override fun close() = input.close()
    }
}

/**
 * The live view's WebSocket after the first message (the request envelope): one WebSocket message = one sealed frame, no length
 * prefix, aad "kelo-e2e v1 ws", each direction with its own key and counter. The app sends with c2s and reads with s2c.
 */
class WsChannel(sendKey: ByteArray, receiveKey: ByteArray) {
    private val send = FrameCipher(sendKey)
    private val receive = FrameCipher(receiveKey)
    private val aad = Ke2e.ascii(Ke2e.AAD_WS)

    @Synchronized fun seal(pt: ByteArray): ByteArray = send.seal(aad, pt)

    /** Opens the next incoming message; a message that does not open (or comes out of order) is a [Ke2eException]: close the socket. */
    @Synchronized fun open(message: ByteArray): ByteArray =
        receive.tryOpen(aad, message) ?: throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "WebSocket message did not open")
}

/** One live-view WebSocket message after opening (spec 5.8: the first plaintext byte is the type). */
sealed interface WsMessage {
    class Text(val text: String) : WsMessage
    class Binary(val bytes: ByteArray) : WsMessage

    companion object {
        const val TYPE_TEXT: Byte = 0x01
        const val TYPE_BINARY: Byte = 0x02
    }
}

/** A text message: `0x01` ‖ UTF-8, sealed. */
fun WsChannel.sealText(text: String): ByteArray = seal(byteArrayOf(WsMessage.TYPE_TEXT) + text.toByteArray(Charsets.UTF_8))

/** A binary message: `0x02` ‖ bytes, sealed. */
fun WsChannel.sealBinary(bytes: ByteArray): ByteArray = seal(byteArrayOf(WsMessage.TYPE_BINARY) + bytes)

/** Opens the next incoming message and reads its type byte; an empty or unknown-type message is a [Ke2eException]. */
fun WsChannel.openMessage(message: ByteArray): WsMessage {
    val pt = open(message)
    if (pt.isEmpty()) throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "WebSocket message has no type")
    return when (pt[0]) {
        WsMessage.TYPE_TEXT -> WsMessage.Text(String(pt, 1, pt.size - 1, Charsets.UTF_8))
        WsMessage.TYPE_BINARY -> WsMessage.Binary(pt.copyOfRange(1, pt.size))
        else -> throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "unknown WebSocket message type")
    }
}

private val REQ_AAD = Ke2e.ascii(Ke2e.AAD_REQUEST)

/**
 * The body frames of a streamed request (spec 5.2, envelope 0x02): the response frame layout (`u32` length, counter nonce, flag
 * only in the aad) with key `Export("kelo-e2e v1 req body", 32)` and `aad = "kelo-e2e v1 req" ‖ flag`, at most 64 KiB plaintext
 * each, the last one flagged. The app writes them while the body is read from its source (a 2 GB video never sits in memory).
 */
class RequestBodyWriter internal constructor(private val out: OutputStream, key: ByteArray) {
    private val cipher = FrameCipher(key)
    private var done = false

    /** One frame; [pt] must be at most [Ke2e.BODY_FRAME_MAX] bytes. */
    fun frame(pt: ByteArray, last: Boolean) {
        check(!done) { "the last body frame was already written" }
        require(pt.size <= Ke2e.BODY_FRAME_MAX) { "body frames are at most 64 KiB" }
        val c = cipher.seal(REQ_AAD + (if (last) LAST else MORE), pt)
        out.write(Ke2eRequest.u32(c.size))
        out.write(c)
        if (last) done = true
    }
}
