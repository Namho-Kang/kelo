package chat.kelo.app.upload

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/** The server's upload routes in memory (limits, single PUT, the five part routes), with switches to make things go wrong. */
class FakeUploadServer(
    /** the limits answer: a JSON object text, or null for an old server (404) */
    var limits: String? = """{"single_max": 10, "part_size": 10, "image_max": 1000}""",
) : AutoCloseable {
    val http = MockWebServer()
    /** "METHOD path" of every request, in arrival order */
    val log = CopyOnWriteArrayList<String>()
    /** (size, crc32) of every part received, by index; the bytes too when [keepBytes] */
    val parts = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Long>>()
    val partBytes = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
    var keepBytes = true
    val singles = CopyOnWriteArrayList<Long>()   // bodies of single PUTs (sizes)
    var startAnswer: MockResponse? = null          // when set, answers POST …/parts
    /** (index, nth try of that index) -> a response to give instead (a status, or a dropped connection after the part arrived / before) */
    var putFault: (Int, Int) -> Fault? = { _, _ -> null }
    /** at finish time, forget this part once and answer 422 */
    var forgetAtFinish: Int? = null
    var finishFaults = 0
    var stallPart: Int? = null   // a part whose answer never comes (for cancelling)
    var partSize = 10L
    private val tries = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile var finished: String? = null
    @Volatile var started = 0L

    sealed interface Fault {
        data class Status(val code: Int, val detail: String? = "oops") : Fault
        data object DropAfterReceiving : Fault
        data object DropBeforeReceiving : Fault
    }

    fun url(): String = http.url("/").toString()

    fun count(prefix: String) = log.count { it.startsWith(prefix) }

    init {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        http.start()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.path.orEmpty()
        log += "${r.method} $path"
        val m = r.method
        return when {
            m == "GET" && path == "/api/uploads/limits" -> limits?.let { json(200, it) } ?: json(404, """{"detail":"Not Found"}""")
            m == "POST" && path.endsWith("/parts") && path.startsWith("/api/uploads/") -> {
                startAnswer?.let { return it }
                val size = Regex("\"size\":\\s*(\\d+)").find(r.body.readUtf8())!!.groupValues[1].toLong()
                started = size
                json(200, """{"upload_id":"u1","part_size":$partSize,"parts":${(size + partSize - 1) / partSize}}""")
            }
            m == "PUT" && path.startsWith("/api/uploads/parts/u1/") -> {
                val index = path.substringAfterLast('/').toInt()
                val nth = tries.merge(index, 1, Int::plus)!!
                val body = r.body.readByteArray()
                val expected = if (index < (started + partSize - 1) / partSize - 1) partSize else started - partSize * (index)
                when (val f = putFault(index, nth)) {
                    is Fault.Status -> return json(f.code, if (f.detail == null) "" else """{"detail":"${f.detail}"}""")
                    Fault.DropBeforeReceiving -> return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    Fault.DropAfterReceiving -> { store(index, body); return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                    null -> {}
                }
                if (stallPart == index) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (body.size.toLong() != expected) return json(422, """{"detail":"part $index must be $expected bytes"}""")
                store(index, body)
                json(200, """{"ok":true,"received":${parts.size}}""")
            }
            m == "GET" && path == "/api/uploads/parts/u1" -> json(200, """{"name":"x","size":$started,"part_size":$partSize,"received":[${parts.keys.sorted().joinToString(",")}]}""")
            m == "POST" && path == "/api/uploads/parts/u1/finish" -> {
                if (finishFaults > 0) { finishFaults--; return json(503, """{"detail":"busy"}""") }
                forgetAtFinish?.let { parts.remove(it); forgetAtFinish = null; return json(422, """{"detail":"part $it is missing"}""") }
                val need = ((started + partSize - 1) / partSize).toInt()
                val missing = (0 until need).filter { !parts.containsKey(it) }
                if (missing.isNotEmpty()) return json(422, """{"detail":"parts missing: $missing"}""")
                finished = "uploads/x"
                json(200, """{"path":"uploads/x","ok":true}""")
            }
            m == "DELETE" && path == "/api/uploads/parts/u1" -> json(200, """{"ok":true}""")
            m == "PUT" && path.startsWith("/api/uploads/") -> {
                singles += r.bodySize
                json(200, """{"path":"uploads/${path.substringAfterLast('/')}","ok":true}""")
            }
            else -> json(404, """{"detail":"no route"}""")
        }
    }

    private fun store(index: Int, body: ByteArray) {
        parts[index] = body.size.toLong() to CRC32().also { it.update(body) }.value
        if (keepBytes) partBytes[index] = body
    }

    /** All parts joined, in order (only with [keepBytes]) */
    fun joined(): ByteArray = parts.keys.sorted().map { partBytes.getValue(it) }.fold(ByteArray(0)) { a, b -> a + b }

    override fun close() { runCatching { http.shutdown() } }
}
