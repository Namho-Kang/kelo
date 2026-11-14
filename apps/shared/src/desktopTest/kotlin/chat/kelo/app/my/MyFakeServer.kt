package chat.kelo.app.my

import chat.kelo.app.core.AesGcmCipher
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Session
import chat.kelo.app.core.Sessions
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections
import javax.crypto.KeyGenerator

/**
 * A local fake server (MockWebServer) for the My view model tests: [answer] maps "METHOD /path" to a body (or null = 404), and every
 * request is recorded. Nothing here reaches a real server.
 */
class MyFakeServer(private val answer: (method: String, path: String, body: String) -> MockResponse?) {
    private val server = MockWebServer()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path"
                return answer(request.method.orEmpty(), path, request.body.readUtf8()) ?: MockResponse().setResponseCode(404).setBody("""{"detail":"no"}""")
            }
        }
        server.start()
    }

    fun sessions(): Sessions {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureStore(MapStore(), AesGcmCipher { key }).apply { saveSession(Session(server.url("/").toString().trimEnd('/'), "tok")) }
        return Sessions(store, requireE2e = false)
    }

    fun saw(prefix: String) = requests.any { it.startsWith(prefix) }

    fun shutdown() = server.shutdown()

    companion object {
        fun ok(body: String) = MockResponse().setBody(body)
        fun fail(code: Int, detail: String) = MockResponse().setResponseCode(code).setBody("""{"detail":"$detail"}""")

        /** Waits until [cond] holds (the view model works on its own coroutines). */
        fun until(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 5000
            while (!cond()) {
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
                Thread.sleep(10)
            }
        }
    }
}
