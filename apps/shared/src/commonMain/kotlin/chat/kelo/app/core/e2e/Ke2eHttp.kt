package chat.kelo.app.core.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.OutputStream

/*
 * KE2E on the wire for an OkHttp client (docs/security/e2e-and-phone-key.md 1.4 and 5): one application interceptor turns every
 * call of a session into POST /e2e and gives the caller back an ordinary Response (the real status, headers and a streamed body), so
 * nothing above it (KeloApi, the live view's frame polling, ExoPlayer, uploads with progress) changes. WebSockets go through
 * [Ke2eWebSocket]. Plain JVM + OkHttp: the desktop app shares this file.
 */

/** The server-corrected clock for the envelope's `ts` (5.13: one correction from a sealed e2e_clock answer). */
class Ke2eClock(private val nowMillis: () -> Long = System::currentTimeMillis) {
    @Volatile var offsetSeconds: Long = 0
    fun now(): Long = nowMillis() / 1000 + offsetSeconds
    fun correct(serverNow: Long) { offsetSeconds = serverNow - nowMillis() / 1000 }

    companion object {
        /** One clock for the app: every server on this phone shares the phone's clock error. */
        val shared = Ke2eClock()
    }
}

/**
 * The error answers the transport makes itself, as ordinary HTTP answers with a top-level `code` (5.10), so KeloApi turns them into
 * an ApiError like any other: [KEY_CHANGED] (the server's key is not the pinned one: pair again) and [UNSUPPORTED] (a pinned server
 * answered without KE2E: it must be updated; 5.5, never a reason to go plain).
 */
object Ke2eErrors {
    const val KEY_CHANGED = Ke2e.KEY_CHANGED_CODE
    const val UNSUPPORTED = "e2e_unsupported"
    const val REQUIRED = "e2e_required"
    const val CLOCK = "e2e_clock"
    const val REPLAY = "e2e_replay"
    /** The status of an [UNSUPPORTED] answer made here */
    const val UNSUPPORTED_STATUS = 502
}

/**
 * Seals every call to [baseUrl] for the pinned key ([pin]; null = not pinned: calls go as they are, for a server that never offered
 * KE2E). Paths that stay plain on purpose ([Ke2e.staysPlain]) and WebSocket upgrades ([Ke2eWebSocket]) pass through.
 *
 * Bodies up to [maxBuffered] bytes (and every body that can be written twice) go in envelope 0x01 and may be sent once more after a
 * clock or replay answer; larger or one-shot bodies (a 2 GB video) are streamed in envelope 0x02 frames as they are written, so the
 * caller's progress callback still follows the real upload.
 */
class Ke2eInterceptor(
    baseUrl: String,
    private val pin: () -> KeyPin?,
    private val clock: Ke2eClock = Ke2eClock.shared,
    private val onKeyChanged: () -> Unit = {},
    private val maxBuffered: Long = 1L shl 20,
) : Interceptor {
    private val base = baseUrl.trimEnd('/')
    private val basePath = runCatching { java.net.URI(base).rawPath.orEmpty().trimEnd('/') }.getOrDefault("")

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Upgrade") != null) return chain.proceed(req)
        val path = innerPath(req)
        if (path == null || Ke2e.staysPlain(path)) return chain.proceed(req)
        // no key (yet): nothing goes in the clear (spec 5.18); the caller fetches the key and tries again
        val key = pin()?.key ?: return made(req, null, Ke2eErrors.UNSUPPORTED_STATUS, Ke2eErrors.UNSUPPORTED)

        val headers = LinkedHashMap<String, String>()
        for (i in 0 until req.headers.size) headers[req.headers.name(i).lowercase()] = req.headers.value(i)
        val body = req.body
        body?.contentType()?.let { headers["content-type"] = it.toString() }

        val length = body?.contentLength() ?: 0L
        if (body == null || (!body.isOneShot() && length in 0..maxBuffered)) {
            val bytes = body?.let { Buffer().also(it::writeTo).readByteArray() } ?: ByteArray(0)
            var retried = false
            while (true) {
                val sealed = Ke2eRequest.seal(key, RequestHead.of(req.method, path, headers, clock.now()), bytes)
                val outer = chain.proceed(outerRequest(req, sealed.outerBody.toRequestBody(CONTENT_TYPE)))
                val answer = answer(req, outer) { sealed.responseReader(it) }
                if (!retried && answer.code == 400) {
                    val retry = retryCode(answer)
                    if (retry != null) { retried = true; answer.close(); continue }
                }
                return answer
            }
        }
        val streamed = Ke2eRequest.sealStreamed(key, RequestHead.of(req.method, path, headers, clock.now()))
        val outer = chain.proceed(outerRequest(req, StreamedBody(streamed, body)))
        return answer(req, outer) { streamed.responseReader(it) }
    }

    /** The path and query the server's app sees ("/api/goals?x=1"), or null for a call to another host. */
    private fun innerPath(req: Request): String? {
        val url = req.url
        if (!url.toString().startsWith(base)) return null
        val p = url.encodedPath.removePrefix(basePath).ifEmpty { "/" }
        return if (url.encodedQuery != null) "$p?${url.encodedQuery}" else p
    }

    /** POST {base}/e2e with no Authorization, no query and none of the call's headers (they travel inside). */
    private fun outerRequest(req: Request, body: RequestBody): Request =
        Request.Builder().url(base + Ke2e.PATH).post(body).header("Content-Type", Ke2e.CONTENT_TYPE).tag(req.tag()).build()

    /** The caller's answer from the outer one. */
    private fun answer(req: Request, outer: Response, reader: (java.io.InputStream) -> ResponseReader): Response {
        val type = outer.header("Content-Type").orEmpty()
        if (outer.code == 200 && type.startsWith(Ke2e.CONTENT_TYPE)) {
            val raw = outer.body ?: return made(req, outer, Ke2eErrors.UNSUPPORTED_STATUS, Ke2eErrors.UNSUPPORTED)
            val r = reader(raw.byteStream())
            val head = try { r.head() } catch (e: Exception) { outer.close(); throw e }
            val hs = Headers.Builder()
            for ((k, v) in head.headers) runCatching { hs.addUnsafeNonAscii(k, v) }
            val bodyType = head.headers["content-type"]?.toMediaTypeOrNull()
            val bodyLength = head.headers["content-length"]?.toLongOrNull() ?: -1L
            return Response.Builder().request(req).protocol(outer.protocol).code(head.status).message("").headers(hs.build())
                .body(StreamBody(r.body.source().buffer(), bodyType, bodyLength)).build()
        }
        val plain = runCatching { outer.peekBody(4096).string() }.getOrDefault("")
        val code = codeOf(plain)
        outer.close()
        if (outer.code == 409 && code == Ke2eErrors.KEY_CHANGED) {
            onKeyChanged()
            return made(req, outer, 409, Ke2eErrors.KEY_CHANGED)
        }
        // anything else in the clear from a pinned server: never handed on as an answer (5.5)
        return made(req, outer, Ke2eErrors.UNSUPPORTED_STATUS, Ke2eErrors.UNSUPPORTED)
    }

    /** A sealed 400 e2e_clock (the clock corrected from its "now") or e2e_replay: worth one more try. Reads only a small body. */
    private fun retryCode(answer: Response): String? {
        val text = runCatching { answer.peekBody(4096).string() }.getOrDefault("")
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when ((obj["code"] as? JsonPrimitive)?.content) {
            Ke2eErrors.CLOCK -> {
                (obj["now"] as? JsonPrimitive)?.longOrNull?.let(clock::correct) ?: return null
                Ke2eErrors.CLOCK
            }
            Ke2eErrors.REPLAY -> Ke2eErrors.REPLAY
            else -> null
        }
    }

    private fun made(req: Request, outer: Response?, status: Int, code: String): Response =
        Response.Builder().request(req).protocol(outer?.protocol ?: Protocol.HTTP_1_1).code(status).message("").header("X-Kelo-Local", "1")
            .body("""{"code":"$code","detail":"$code"}""".toResponseBody(JSON)).build()

    private class StreamBody(private val source: BufferedSource, private val type: MediaType?, private val length: Long) : ResponseBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun source() = source
    }

    /** Envelope 0x02: the sealed head, then [body] as frames of at most 64 KiB while it is written. */
    private class StreamedBody(private val streamed: StreamedRequest, private val body: RequestBody) : RequestBody() {
        override fun contentType() = CONTENT_TYPE
        override fun contentLength() = -1L
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.write(streamed.prefix)
            val frames = FramingStream(streamed.bodyWriter(sink.outputStream()))
            val inner = frames.sink().buffer()
            body.writeTo(inner)
            inner.flush()
            frames.finish()
            sink.flush()
        }
    }

    companion object {
        val CONTENT_TYPE = Ke2e.CONTENT_TYPE.toMediaType()
        private val JSON = "application/json".toMediaType()

        /** A top-level `code` of a small JSON answer, or null. */
        fun codeOf(text: String): String? =
            runCatching { ((Json.parseToJsonElement(text) as? JsonObject)?.get("code") as? JsonPrimitive)?.content }.getOrNull()
    }
}

/** Collects written bytes into frames of 64 KiB; a full frame goes once more bytes come (so the last one can be flagged). */
internal class FramingStream(private val writer: RequestBodyWriter) : OutputStream() {
    private val buf = ByteArray(Ke2e.BODY_FRAME_MAX)
    private var n = 0
    private var done = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        var left = len
        while (left > 0) {
            if (n == buf.size) { writer.frame(buf.copyOf(), last = false); n = 0 }
            val k = minOf(left, buf.size - n)
            System.arraycopy(b, at, buf, n, k)
            n += k; at += k; left -= k
        }
    }

    /** The last frame (possibly empty). */
    fun finish() {
        if (done) return
        done = true
        writer.frame(buf.copyOf(n), last = true)
        buf.fill(0)
    }
}

/** GET /api/e2e/info (plaintext on purpose, spec 1.5): the server's key for trust on first use. */
object E2eInfoCalls {
    sealed interface Result {
        /** The server offers KE2E with this key (kid and fingerprint checked against it) */
        class Found(val key: ServerKey) : Result
        /** The server answered without a usable key (404, or not the spec's answer): too old for this app (spec 5.18) */
        data object Missing : Result
        /** No answer at all (no network, a tunnel error): say nothing about the server */
        data object Unreachable : Result
    }

    suspend fun lookup(client: OkHttpClient, baseUrl: String): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + Ke2e.INFO_PATH).get().build()
        try {
            client.newCall(req).execute().use { r ->
                when {
                    r.code >= 500 -> Result.Unreachable
                    !r.isSuccessful -> Result.Missing
                    else -> parse(r.body?.string().orEmpty())?.let { Result.Found(it) } ?: Result.Missing
                }
            }
        } catch (e: java.io.IOException) {
            Result.Unreachable
        }
    }

    /** The key, or null for anything else. */
    suspend fun fetch(client: OkHttpClient, baseUrl: String): ServerKey? = (lookup(client, baseUrl) as? Result.Found)?.key

    fun parse(text: String): ServerKey? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ServerKey.fromInfo((o["v"] as? JsonPrimitive)?.content?.toIntOrNull(), s("pk"), s("kid"), s("fingerprint"))
    }
}
