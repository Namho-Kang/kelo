package chat.kelo.app.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A pairing link as the server makes it (POST /api/devices/pair): "https://host[:port]/#pair=CODE", and since KE2E
 * "…/#pair=CODE&k=<base64url of the server's 32-byte public key>". [serverKey] is that `k` text (checked to be 32 bytes), null for
 * a link without it (an older server, or a typed code).
 */
data class PairingLink(val serverUrl: String, val code: String, val serverKey: String? = null)

object PairingLinks {
    const val MARKER = "#pair="

    /** The server checks the code's length too (PairIn in server.py). */
    val CODE_LENGTH = 16..128

    /**
     * Reads a scanned or pasted pairing link; null when [raw] is not one. The fragment is the code, then optional `&name=value`
     * parameters: `k` is the server's key, others are ignored (later additions must not break this app). A `k` that is not a
     * 32-byte base64url key, or two of them, makes the whole link invalid: a damaged or edited link must not pair without its key.
     */
    fun parse(raw: String): PairingLink? {
        val s = raw.trim()
        val at = s.indexOf(MARKER)
        if (at <= 0) return null
        val parts = s.substring(at + MARKER.length).split('&')
        val code = percentDecode(parts[0])?.trim() ?: return null
        if (code.length !in CODE_LENGTH || code.any { it.isWhitespace() || it.isISOControl() }) return null
        var key: String? = null
        for (p in parts.drop(1)) {
            if (p.substringBefore('=') != "k") continue
            if (key != null) return null
            key = p.substringAfter('=', "").takeIf(::isServerKey) ?: return null
        }
        val server = ServerAddress.normalize(s.substring(0, at), defaultScheme = null) ?: return null
        return PairingLink(server, code, key)
    }

    /** base64url (no padding) of exactly 32 bytes: 43 characters. */
    private fun isServerKey(k: String): Boolean =
        k.length == 43 && runCatching { java.util.Base64.getUrlDecoder().decode(k).size == 32 }.getOrDefault(false)

    /** Like decodeURIComponent: "%xx" sequences only ("+" stays "+"). */
    private fun percentDecode(s: String): String? = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}

object ServerAddress {
    /** Hosts the network security config lets the app reach over plain http (development only). */
    /** Plain http is allowed only to these, and only in debug builds (the release network config allows https only). */
    val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /**
     * "kelo.example.com/" -> "https://kelo.example.com"; "http://10.0.2.2:8000" stays. Null when it is not a usable address
     * (other schemes, no host, a user name or password, a query). A path is kept (without the trailing slash).
     * [defaultScheme] is added when the text has none; null means a scheme is required.
     */
    fun normalize(raw: String, defaultScheme: String? = "https"): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = (defaultScheme ?: return null) + "://" + s
        val uri = try {
            URI(s)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null) return null
        if (uri.rawFragment != null && uri.rawFragment.isNotEmpty()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }

    /**
     * https anywhere; plain http only to the hosts in [CLEARTEXT_HOSTS] and only when [allowLocalHttp] (Android: a debug build, the
     * same rule as the debug and release network_security_config.xml; the desktop app: always, its server may run on the same PC).
     */
    fun isAllowed(normalized: String, allowLocalHttp: Boolean): Boolean {
        val uri = URI(normalized)
        return uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && uri.host?.lowercase()?.trim('[', ']') in CLEARTEXT_HOSTS)
    }
}

/**
 * The two ways a phone joins a Kelo server. Neither touches the app's saved session; the caller saves it.
 *
 * KE2E (spec 1.3): a link with the server's key (`k`) pins it as verified and the pairing call itself already goes through KE2E.
 * A typed code or key has no `k`: the key is fetched from GET /api/e2e/info (trust on first use, not verified) before the first call;
 * a server without that endpoint never offered KE2E and is used as before. [pinned] is the key this phone already holds for that
 * server (never replaced by a fetched one: 5.5).
 */
class PairingClient(private val client: OkHttpClient = KeloApi.defaultClient, private val pinned: (String) -> chat.kelo.app.core.e2e.KeyPin? = { null }) {

    /** POST /api/pair {code, name} -> this device's own token. Throws [ApiError] (400: expired or used link, 429: too many tries). */
    suspend fun pair(link: PairingLink, deviceName: String): Session {
        val pin = link.serverKey?.let { k -> runCatching { chat.kelo.app.core.e2e.ServerKey.fromBase64Url(k) }.getOrNull() }
            ?.let { chat.kelo.app.core.e2e.KeyPin(it, verified = true) }
            ?: pinFor(link.serverUrl)
        val api = apiFor(link.serverUrl, token = "", pin)
        val result = api.post<PairResult>("/api/pair", buildJsonObject {
            put("code", link.code)
            put("name", deviceName)
        })
        return Session(link.serverUrl, result.token, pin)
    }

    /** Server address + access key, checked with GET /api/profile. Throws [ApiError] (401: wrong key). */
    suspend fun checkKey(serverUrl: String, key: String): Pair<Session, Profile> {
        val pin = pinFor(serverUrl)
        val api = apiFor(serverUrl, token = key, pin)
        return Session(serverUrl, key, pin) to api.get<Profile>("/api/profile")
    }

    /**
     * The key already pinned for [serverUrl], else the server's own answer to GET /api/e2e/info (TOFU). A server that offers none is
     * too old for this app (spec 5.18): [ApiError] with code [CODE_SERVER_OLD]; no answer at all is a network error.
     */
    private suspend fun pinFor(serverUrl: String): chat.kelo.app.core.e2e.KeyPin {
        pinned(serverUrl)?.let { return it }
        return when (val r = chat.kelo.app.core.e2e.E2eInfoCalls.lookup(client, serverUrl)) {
            is chat.kelo.app.core.e2e.E2eInfoCalls.Result.Found -> chat.kelo.app.core.e2e.KeyPin(r.key, verified = false)
            chat.kelo.app.core.e2e.E2eInfoCalls.Result.Missing -> throw ApiError(409, "the server must be updated", code = CODE_SERVER_OLD)
            chat.kelo.app.core.e2e.E2eInfoCalls.Result.Unreachable -> throw ApiError(ApiError.NO_RESPONSE, "server not reached")
        }
    }

    private fun apiFor(serverUrl: String, token: String, pin: chat.kelo.app.core.e2e.KeyPin): KeloApi {
        val http = client.newBuilder().addInterceptor(chat.kelo.app.core.e2e.Ke2eInterceptor(serverUrl, { pin })).build()
        return KeloApi(serverUrl, token = token, client = http, keyPin = pin)
    }

    companion object {
        /** The server offers no KE2E: it must be updated before this app can join it */
        const val CODE_SERVER_OLD = "e2e_server_old"
    }
}
