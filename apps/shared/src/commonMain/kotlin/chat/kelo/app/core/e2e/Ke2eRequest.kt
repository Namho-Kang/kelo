package chat.kelo.app.core.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * The inner request's head `H` (spec 1.4): method, path with query, the allowed headers (lower-case names), time and a random
 * nonce against replay.
 */
data class RequestHead(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val ts: Long,
    val nonce: String,
) {
    /** UTF-8 JSON in the spec's key order: m, p, h, ts, n. */
    fun toJsonBytes(): ByteArray {
        val o = buildJsonObject {
            put("m", method)
            put("p", path)
            put("h", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
            put("ts", ts)
            put("n", nonce)
        }
        return json.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8)
    }

    override fun toString(): String = "RequestHead($method ${path.substringBefore('?')})"   // never the token or the query

    companion object {
        private val json = Json { encodeDefaults = true }
        private val random = SecureRandom()

        /**
         * A head for a call now: keeps only the headers that may go inside ([Ke2e.isInnerHeader]), lower-cases their names, a fresh
         * 16-byte nonce.
         */
        fun of(method: String, path: String, headers: Map<String, String>, nowSeconds: Long = System.currentTimeMillis() / 1000): RequestHead {
            val kept = LinkedHashMap<String, String>()
            for ((k, v) in headers) if (Ke2e.isInnerHeader(k)) kept[k.lowercase()] = v
            val n = ByteArray(16).also(random::nextBytes)
            return RequestHead(method.uppercase(), path, kept, nowSeconds, B64Url.encode(n))
        }

        fun parse(bytes: ByteArray): RequestHead {
            val o = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                ?: throw Ke2eException(Ke2eException.Kind.MALFORMED, "head is not a JSON object")
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw Ke2eException(Ke2eException.Kind.MALFORMED, "head has no $k")
            val h = (o["h"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() } ?: emptyMap()
            val ts = (o["ts"] as? JsonPrimitive)?.longOrNull ?: throw Ke2eException(Ke2eException.Kind.MALFORMED, "head has no ts")
            return RequestHead(str("m"), str("p"), h, ts, str("n"))
        }
    }
}

/** A sealed request: the outer body to POST to /e2e and the context to open the answer (or run the WebSocket) with. */
class SealedRequest(val outerBody: ByteArray, val context: Ke2eContext) {
    /** The response frames' reader over the answer's body. */
    fun responseReader(input: java.io.InputStream): ResponseReader = ResponseReader(input, context.responseKey())

    /** The WebSocket channel after this request was sent as the first message on /e2e/ws. */
    fun webSocket(): WsChannel = context.wsKeys().let { (c2s, s2c) -> WsChannel(sendKey = c2s, receiveKey = s2c) }
}

/**
 * A streamed request (envelope 0x02, spec 5.2): send [prefix] first, then the body through [bodyWriter] on the same outer body.
 */
class StreamedRequest(val prefix: ByteArray, val context: Ke2eContext) {
    fun bodyWriter(out: java.io.OutputStream): RequestBodyWriter = RequestBodyWriter(out, context.requestBodyKey())
    fun responseReader(input: java.io.InputStream): ResponseReader = ResponseReader(input, context.responseKey())
}

/** What the server opened (receiver side: tests, a local mock). */
class OpenedRequest(val head: RequestHead, val body: ByteArray, val context: Ke2eContext) {
    fun responseWriter(out: java.io.OutputStream): ResponseWriter = ResponseWriter(out, context.responseKey())
    fun webSocket(): WsChannel = context.wsKeys().let { (c2s, s2c) -> WsChannel(sendKey = s2c, receiveKey = c2s) }
}

/** The request envelope (spec 1.4). */
object Ke2eRequest {
    /** Inner request = u32 BE length of H ‖ H ‖ body. */
    fun inner(head: ByteArray, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(4 + head.size + body.size)
        out.write(u32(head.size))
        out.write(head)
        out.write(body)
        return out.toByteArray()
    }

    /** Splits an inner request into (H, body). */
    fun splitInner(inner: ByteArray): Pair<ByteArray, ByteArray> {
        if (inner.size < 4) throw Ke2eException(Ke2eException.Kind.MALFORMED, "inner request too short")
        val n = readU32(inner, 0)
        if (n < 0 || n > inner.size - 4) throw Ke2eException(Ke2eException.Kind.MALFORMED, "head length out of range")
        return inner.copyOfRange(4, 4 + n.toInt()) to inner.copyOfRange(4 + n.toInt(), inner.size)
    }

    /**
     * Seals [head] and [body] for [server]: `0x01 ‖ kid (8) ‖ enc (32) ‖ Seal(aad = "kelo-e2e v1 req", inner)`.
     * [ephemeral] is for test vectors only.
     */
    fun seal(server: ServerKey, head: RequestHead, body: ByteArray = ByteArray(0), ephemeral: X25519KeyPair? = null): SealedRequest =
        sealInner(server, inner(head.toJsonBytes(), body), ephemeral)

    fun sealInner(server: ServerKey, inner: ByteArray, ephemeral: X25519KeyPair? = null): SealedRequest {
        val ctx = Ke2eContext.sender(server, ephemeral)
        val ct = ctx.seal(Ke2e.ascii(Ke2e.AAD_REQUEST), inner)
        val out = ByteArray(1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES + ct.size)
        out[0] = Ke2e.VERSION
        server.kidBytes.copyInto(out, 1)
        ctx.enc.copyInto(out, 1 + Ke2e.KID_BYTES)
        ct.copyInto(out, 1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES)
        return SealedRequest(out, ctx)
    }

    /**
     * The server's side: checks the version and the kid (a different kid is [Ke2eException.Kind.KEY_CHANGED]: the server answers
     * plaintext 409 e2e_key_changed), opens and splits. Freshness (ts ±300 s) and replay (nonce) are the server's job.
     */
    fun open(outer: ByteArray, server: X25519KeyPair): OpenedRequest {
        val header = 1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES
        if (outer.size < header + Ke2e.TAG_BYTES || outer[0] != Ke2e.VERSION) {
            throw Ke2eException(Ke2eException.Kind.MALFORMED, "not a KE2E v1 envelope")
        }
        val kid = outer.copyOfRange(1, 1 + Ke2e.KID_BYTES)
        if (!java.security.MessageDigest.isEqual(kid, ServerKey(server.publicKey).kidBytes)) {
            throw Ke2eException(Ke2eException.Kind.KEY_CHANGED, "unknown kid")
        }
        val enc = outer.copyOfRange(1 + Ke2e.KID_BYTES, header)
        val ctx = Ke2eContext.receiver(enc, server)
        val inner = ctx.open(Ke2e.ascii(Ke2e.AAD_REQUEST), outer.copyOfRange(header, outer.size))
        val (h, body) = splitInner(inner)
        return OpenedRequest(RequestHead.parse(h), body, ctx)
    }

    /**
     * Seals only [head] (with an empty body) for a streamed request: `0x02 ‖ kid ‖ enc ‖ u32 BE len(ct) ‖ ct`; the body frames follow
     * ([StreamedRequest.bodyWriter]).
     */
    fun sealStreamed(server: ServerKey, head: RequestHead, ephemeral: X25519KeyPair? = null): StreamedRequest {
        val ctx = Ke2eContext.sender(server, ephemeral)
        val ct = ctx.seal(Ke2e.ascii(Ke2e.AAD_REQUEST), inner(head.toJsonBytes(), ByteArray(0)))
        val out = ByteArray(1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES + 4 + ct.size)
        out[0] = Ke2e.VERSION_STREAMED
        server.kidBytes.copyInto(out, 1)
        ctx.enc.copyInto(out, 1 + Ke2e.KID_BYTES)
        u32(ct.size).copyInto(out, 1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES)
        ct.copyInto(out, 1 + Ke2e.KID_BYTES + Ke2e.ENC_BYTES + 4)
        return StreamedRequest(out, ctx)
    }

    internal fun u32(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    internal fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
}

/** Frame 0 of a response: the real status and the answer's headers (lower-case names). */
data class ResponseHead(val status: Int, val headers: Map<String, String>) {
    fun toJsonBytes(): ByteArray = buildJsonObject {
        put("s", status)
        put("h", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): ResponseHead {
            val o = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
                ?: throw Ke2eException(Ke2eException.Kind.MALFORMED, "response head is not a JSON object")
            val s = (o["s"] as? JsonPrimitive)?.intOrNull ?: throw Ke2eException(Ke2eException.Kind.MALFORMED, "response head has no s")
            val h = (o["h"] as? JsonObject)?.entries?.associate { (k, v) -> k.lowercase() to (v as? JsonPrimitive)?.content.orEmpty() }
                ?: emptyMap()
            return ResponseHead(s, h)
        }
    }
}
