package chat.kelo.app.core.e2e

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A WebSocket through KE2E (spec 1.4, 5.8, 5.17): `GET {base}/e2e/ws`; the first message is the request envelope whose inner path is
 * the target socket ([innerPath], with its query); the server's first message is the head `{"s": 101, ...}` (or an error status,
 * then a close 4400 / 4401 / 4409). After that every message is sealed with its type byte. The caller gets an ordinary [WebSocket]
 * and its own [WebSocketListener] is called as for a plain socket: onOpen once the head says 101, text and binary messages as
 * they were sent. A message that does not open closes the socket (onFailure).
 */
object Ke2eWebSocket {
    const val CLOSE_KEY_CHANGED = 4409

    fun open(
        client: OkHttpClient, baseUrl: String, key: ServerKey, innerPath: String, headers: Map<String, String>,
        listener: WebSocketListener, clock: Ke2eClock = Ke2eClock.shared, onKeyChanged: () -> Unit = {},
    ): WebSocket {
        val sealed = Ke2eRequest.seal(key, RequestHead.of("GET", innerPath, headers, clock.now()))
        val channel = sealed.webSocket()
        val socket = Sealed(channel)
        val req = Request.Builder().url(baseUrl.trimEnd('/') + Ke2e.WS_PATH).build()
        socket.raw = client.newWebSocket(req, object : WebSocketListener() {
            private var ready = false
            private var upgrade: Response? = null

            override fun onOpen(webSocket: WebSocket, response: Response) {
                upgrade = response
                webSocket.send(sealed.outerBody.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val msg = try { channel.openMessage(bytes.toByteArray()) } catch (e: Ke2eException) {
                    webSocket.cancel()
                    listener.onFailure(socket, e, null)
                    return
                }
                if (!ready) {
                    val head = (msg as? WsMessage.Text)?.let { runCatching { ResponseHead.parse(it.text.toByteArray()) }.getOrNull() }
                    if (head == null || head.status != 101) {
                        // the server closes next (4400 / 4401 / 4409); its close code says why
                        return
                    }
                    ready = true
                    val r = upgrade ?: Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(101).message("").build()
                    listener.onOpen(socket, r)
                    return
                }
                when (msg) {
                    is WsMessage.Text -> listener.onMessage(socket, msg.text)
                    is WsMessage.Binary -> listener.onMessage(socket, msg.bytes.toByteString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // nothing travels in the clear on this socket
                webSocket.cancel()
                listener.onFailure(socket, Ke2eException(Ke2eException.Kind.NOT_E2E, "plaintext WebSocket message"), null)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == CLOSE_KEY_CHANGED) onKeyChanged()
                listener.onClosing(socket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(socket, code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(socket, t, response)
        })
        return socket
    }

    /** The caller's side: sends are sealed with their type byte (text 0x01, binary 0x02). */
    private class Sealed(private val channel: WsChannel) : WebSocket {
        lateinit var raw: WebSocket
        override fun request(): Request = raw.request()
        override fun queueSize(): Long = raw.queueSize()
        override fun send(text: String): Boolean = raw.send(channel.sealText(text).toByteString())
        override fun send(bytes: ByteString): Boolean = raw.send(channel.sealBinary(bytes.toByteArray()).toByteString())
        override fun close(code: Int, reason: String?): Boolean = raw.close(code, reason)
        override fun cancel() = raw.cancel()
    }
}
