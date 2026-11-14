package chat.kelo.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Kelo server's HTTP API, for one server and one token.
 *
 * - Every call sends `Authorization: Bearer <token>` (left out when [token] is empty, e.g. for POST /api/pair).
 * - A failed call throws [ApiError]; a 401 also calls [onUnauthorized] (the app then signs this device out).
 * - Calls are cancellable: cancelling the coroutine cancels the HTTP call.
 *
 * Typed calls decode with kotlinx.serialization (unknown fields are ignored):
 * ```
 * val goals = api.get<List<Goal>>("/api/goals")
 * val goal = api.post<Goal>("/api/goals", buildJsonObject { put("title", "운동") })
 * api.delete<JsonElement>("/api/goals/3")          // when the answer does not matter
 * val p = api.put<Profile>("/api/profile", KeloApi.body(ProfileUpdate(...)))
 * ```
 */
class KeloApi(
    baseUrl: String,
    val token: String,
    val client: OkHttpClient = defaultClient,
    private val onUnauthorized: () -> Unit = {},
    /** The server's KE2E key this session is pinned to (null: a server that never offered KE2E); [client] already seals calls */
    val keyPin: chat.kelo.app.core.e2e.KeyPin? = null,
    /** Every failed answer (after [onUnauthorized] for a 401): 426 app_too_old, e2e_key_changed, e2e_required … are handled once */
    private val onFailure: (ApiError) -> Unit = {},
) {
    /** e.g. "https://kelo.example.com" (no trailing slash). */
    val baseUrl: String = baseUrl.trimEnd('/')

    /** Absolute URL for an API path such as "/api/goals". */
    fun url(path: String): String = baseUrl + (if (path.startsWith("/")) path else "/$path")

    /** A request builder with the address and the bearer token set, for calls the helpers below do not cover (streams, images). */
    fun requestBuilder(path: String): Request.Builder = Request.Builder().url(url(path)).apply {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }

    /** One JSON call. [body] is sent as application/json; an empty answer comes back as [JsonNull]. */
    suspend fun request(method: String, path: String, body: JsonElement? = null): JsonElement {
        val requestBody: RequestBody? = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_TYPE)
            method in BODY_METHODS -> EMPTY_BODY
            else -> null
        }
        val bytes = send(requestBuilder(path).method(method, requestBody).build())
        if (bytes.isEmpty()) return JsonNull
        // parsed off the main thread: a page of chat history or the connector list would otherwise cost frames
        return withContext(Dispatchers.Default) {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) JsonNull else readJson(text)
        }
    }

    /**
     * A JSON call whose body is given as bytes (a request that carries card details: they never become a String). The bytes are
     * sent as they are; the caller wipes them afterwards.
     */
    suspend fun requestBytes(method: String, path: String, body: ByteArray): JsonElement {
        val bytes = send(requestBuilder(path).method(method, body.toRequestBody(JSON_TYPE)).build())
        if (bytes.isEmpty()) return JsonNull
        val text = bytes.toString(Charsets.UTF_8)
        return if (text.isBlank()) JsonNull else readJson(text)
    }

    suspend inline fun <reified T> get(path: String): T = decodeOffMain(request("GET", path))
    suspend inline fun <reified T> post(path: String, body: JsonElement? = null): T = decodeOffMain(request("POST", path, body))
    suspend inline fun <reified T> put(path: String, body: JsonElement? = null): T = decodeOffMain(request("PUT", path, body))
    suspend inline fun <reified T> patch(path: String, body: JsonElement? = null): T = decodeOffMain(request("PATCH", path, body))
    suspend inline fun <reified T> delete(path: String, body: JsonElement? = null): T = decodeOffMain(request("DELETE", path, body))

    /**
     * Hand the agent a file: PUT /api/uploads/{name} with the raw bytes as the body.
     * Returns the stored path the server reports.
     */
    suspend fun upload(name: String, bytes: ByteArray, contentType: String? = null, onProgress: ((sent: Long, total: Long) -> Unit)? = null): String {
        val type = contentType?.toMediaTypeOrNull()
        val body = if (onProgress == null) bytes.toRequestBody(type) else ProgressBody(bytes, type, onProgress)
        val req = requestBuilder("/api/uploads/${segment(name)}")
            .put(body)
            .build()
        val answer = readJson(send(req).toString(Charsets.UTF_8))
        return ((answer as? JsonObject)?.get("path") as? JsonPrimitive)?.content.orEmpty()
    }

    /**
     * Hand the agent a file without holding it in memory: PUT /api/uploads/{name} with the body read from [open] a chunk at a time
     * (a 2 GB video goes up as it is read). [length] is the size when known (-1 sends it chunked). [onProgress] gets (sent, total).
     * Returns the stored path the server reports.
     */
    suspend fun uploadStream(
        name: String, length: Long, contentType: String?, open: () -> java.io.InputStream,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): String {
        val body = StreamBody(open, length, contentType?.toMediaTypeOrNull(), onProgress)
        val answer = readJson(send(requestBuilder("/api/uploads/${segment(name)}").put(body).build()).toString(Charsets.UTF_8))
        return ((answer as? JsonObject)?.get("path") as? JsonPrimitive)?.content.orEmpty()
    }

    /** Raw bytes of a GET (e.g. /api/uploads/{name}/file). Errors as for [request]. */
    suspend fun download(path: String): ByteArray = send(requestBuilder(path).get().build())

    /**
     * A GET written straight into [out] as it arrives (a large video is never held in memory). Errors as for [request]; a failed
     * answer's body is read only for its detail. Runs on the IO dispatcher; cancelling the coroutine cancels the call.
     */
    suspend fun downloadTo(path: String, out: java.io.OutputStream, onProgress: ((got: Long, total: Long) -> Unit)? = null) {
        val call = client.newCall(requestBuilder(path).get().build())
        withContext(Dispatchers.IO) {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val handle = job?.invokeOnCompletion { call.cancel() }
            try {
                val response = try { call.execute() } catch (e: IOException) {
                    if (call.isCanceled()) throw CancellationException("cancelled")
                    throw ApiError(ApiError.NO_RESPONSE, e.message ?: e.javaClass.simpleName, e)
                }
                response.use { r ->
                    val body = r.body ?: throw ApiError(ApiError.NO_RESPONSE, "empty answer")
                    if (r.code !in 200..299) throw failure(r.code, body.bytes())
                    val total = body.contentLength()
                    val buf = ByteArray(STREAM_CHUNK)
                    var got = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            onProgress?.invoke(got, total)
                        }
                    }
                }
            } finally {
                handle?.dispose()
            }
        }
    }

    /** Runs [req] and returns the body of a 2xx answer; anything else becomes an [ApiError]. */
    suspend fun send(req: Request): ByteArray {
        val (code, bytes) = execute(req)
        if (code !in 200..299) throw failure(code, bytes)
        return bytes
    }

    private fun failure(code: Int, bytes: ByteArray): ApiError {
        val top = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val raw = top?.get("detail")
        val detail = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: ((raw as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: "HTTP $code"
        // spec 5.10: `code` at the top level next to detail; older answers put it inside an object detail
        val errCode = ((top?.get("code") as? JsonPrimitive)?.takeIf { it.isString }
            ?: ((raw as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { it.isString })?.content
        if (code == 401) onUnauthorized()
        return ApiError(code, detail, detailJson = raw as? JsonObject, code = errCode).also { runCatching { onFailure(it) } }
    }

    /**
     * The live view's WebSocket to [path] (with its query): through KE2E when this session is pinned ([keyPin]: GET /e2e/ws, sealed
     * messages; the listener sees plain text and binary messages as before), else the plain socket without the bearer token (the
     * path's one-use ticket is enough). [withAuth]: the socket needs the bearer token (a reply's socket): kept on the plain
     * request, put into the inner request's headers through KE2E.
     */
    fun newWebSocket(path: String, listener: okhttp3.WebSocketListener, withAuth: Boolean = false): okhttp3.WebSocket {
        val pin = keyPin
        if (pin != null) {
            val hs = LinkedHashMap<String, String>()
            AppVersion.header.takeIf { it.isNotEmpty() }?.let { hs["x-kelo-app"] = it }
            AcceptLanguage.current.takeIf { it.isNotEmpty() }?.let { hs["accept-language"] = it }
            if (withAuth && token.isNotEmpty()) hs["authorization"] = "Bearer $token"
            return chat.kelo.app.core.e2e.Ke2eWebSocket.open(client, baseUrl, pin.key, path, hs, listener, onKeyChanged = {
                runCatching { onFailure(ApiError(409, ApiError.CODE_KEY_CHANGED, code = ApiError.CODE_KEY_CHANGED)) }
            })
        }
        val req = requestBuilder(path).apply { if (!withAuth) removeHeader("Authorization") }.build()
        return client.newWebSocket(req, listener)
    }

    private suspend fun execute(req: Request): Pair<Int, ByteArray> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) cont.resumeWithException(CancellationException("cancelled"))
                else cont.resumeWithException(ApiError(ApiError.NO_RESPONSE, e.message ?: e.javaClass.simpleName, e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use { it.body?.bytes() ?: ByteArray(0) }
                    cont.resume(response.code to bytes)
                } catch (e: IOException) {
                    cont.resumeWithException(ApiError(ApiError.NO_RESPONSE, e.message ?: e.javaClass.simpleName, e))
                }
            }
        })
    }

    /** [bytes] as a request body that reports how much of it has been written, a chunk at a time. */
    private class ProgressBody(
        private val bytes: ByteArray,
        private val type: okhttp3.MediaType?,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: okio.BufferedSink) {
            val total = bytes.size.toLong()
            var at = 0
            onProgress(0, total)
            while (at < bytes.size) {
                val n = minOf(CHUNK, bytes.size - at)
                sink.write(bytes, at, n)
                sink.flush()
                at += n
                onProgress(at.toLong(), total)
            }
        }
    }

    /** A body read from a stream as it is sent (never all in memory); one shot, since the stream cannot be read twice. */
    private class StreamBody(
        private val open: () -> java.io.InputStream,
        private val length: Long,
        private val type: okhttp3.MediaType?,
        private val onProgress: ((Long, Long) -> Unit)?,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: okio.BufferedSink) {
            val buf = ByteArray(STREAM_CHUNK)
            var sent = 0L
            onProgress?.invoke(0, length)
            open().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    onProgress?.invoke(sent, length)
                }
            }
        }
    }

    companion object {
        private const val CHUNK = 32 * 1024
        private const val STREAM_CHUNK = 128 * 1024

        /** Lenient about what the server adds: unknown fields are ignored, missing ones take the model's defaults. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
        }

        /** Long enough read timeout for the chat's 20-second follow-up requests (GET /api/turns/{id}?wait=20). */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .addInterceptor(AcceptLanguage.interceptor)   // the server answers in the app's language
                .addInterceptor(AppVersion.interceptor)       // X-Kelo-App out, X-Kelo-Server back (docs/versioning.md)
                .build()
        }

        private val JSON_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /** Encode any @Serializable value as a request body. */
        inline fun <reified B> body(value: B): JsonElement = json.encodeToJsonElement(value)

        /** [decode] on a background thread (the caller is usually a view model on the main thread). */
        @PublishedApi
        internal suspend inline fun <reified T> decodeOffMain(element: JsonElement): T =
            if (element is JsonNull) decode(element) else withContext(Dispatchers.Default) { decode<T>(element) }

        inline fun <reified T> decode(element: JsonElement): T =
            try { json.decodeFromJsonElement(element) } catch (e: IllegalArgumentException) { throw ApiError(0, "unreadable answer", e) }

        /** A 2xx answer that is not JSON (a wrong address, a captive portal) counts as "server not reached", never a crash. */
        fun readJson(text: String): JsonElement =
            try { json.parseToJsonElement(text) } catch (e: IllegalArgumentException) { throw ApiError(0, "unreadable answer", e) }

        /** One path segment, encoded like encodeURIComponent (spaces as %20, not +). */
        fun segment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
