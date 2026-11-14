package chat.kelo.app.core.e2e

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.hpke.HPKEContext
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/*
 * KE2E: the apps talk to their own Kelo server end to end (docs/security/e2e-and-phone-key.md, section 1).
 *
 * This package is the crypto core only: plain Kotlin on the JVM with BouncyCastle's lightweight API (no JCA provider is
 * registered) and kotlinx.serialization for the request head. It has nothing from Android or the desktop and nothing from OkHttp,
 * so both apps use the same files (the apps/shared/ module, package chat.kelo.app.core.e2e).
 *
 * Why BouncyCastle and not Tink: Tink's public HPKE is HybridEncrypt/HybridDecrypt (one message per setup, no Export, no
 * second Seal on the same context); its HpkeContext is internal API. KE2E needs Export (response, WebSocket keys), which
 * BouncyCastle's org.bouncycastle.crypto.hpke.HPKEContext has. Its ChaCha20-Poly1305 also works on every Android version the app
 * supports (minSdk 26), where the platform's JCA "ChaCha20-Poly1305" is not guaranteed.
 */

/** Everything fixed by the spec (version 1). */
object Ke2e {
    /** First byte of the outer request body (and of the WebSocket's first message). */
    const val VERSION: Byte = 0x01
    /** Envelope 0x02 (spec 5.2): only the head is in `ct`, the body follows as frames (large uploads). */
    const val VERSION_STREAMED: Byte = 0x02
    const val KID_BYTES = 8
    const val ENC_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val TAG_BYTES = 16

    const val INFO_PREFIX = "kelo-e2e v1 "
    const val AAD_REQUEST = "kelo-e2e v1 req"
    const val AAD_RESPONSE = "kelo-e2e v1 res"
    const val AAD_WS = "kelo-e2e v1 ws"
    const val EXPORT_RESPONSE = "kelo-e2e v1 res"
    const val EXPORT_WS_C2S = "kelo-e2e v1 ws c2s"
    const val EXPORT_WS_S2C = "kelo-e2e v1 ws s2c"
    const val EXPORT_REQUEST_BODY = "kelo-e2e v1 req body"

    /** The outer request: `POST /e2e` with this content type, no Authorization, no query. */
    const val PATH = "/e2e"
    const val WS_PATH = "/e2e/ws"
    const val CONTENT_TYPE = "application/x-kelo-e2e"
    /** Plaintext: the server's public key for typed codes and devices paired before KE2E (trust on first use). */
    const val INFO_PATH = "/api/e2e/info"
    /** Plaintext 409 body code: the server's key is not the pinned one (the app says "pair again"). */
    const val KEY_CHANGED_CODE = "e2e_key_changed"

    /** Response frames of a body are at most this much plaintext (spec: large uploads and downloads, 64 KiB). */
    const val BODY_FRAME_MAX = 64 * 1024
    /** The most a reader accepts in one frame (head, one server-sent event): bounds memory against a broken or hostile peer. */
    const val FRAME_READ_MAX = 1024 * 1024 + TAG_BYTES

    /** The headers that go inside the envelope; every other request header is dropped. */
    private val INNER_HEADERS = setOf("authorization", "content-type", "accept", "accept-language", "if-none-match", "range")

    fun isInnerHeader(name: String): Boolean = name.lowercase().let { it in INNER_HEADERS || it.startsWith("x-kelo-") }

    /** The paths that stay plaintext on purpose (spec 1.5); everything else under /api goes through the envelope once pinned. */
    fun staysPlain(path: String): Boolean {
        val p = path.substringBefore('?')
        return p == "/healthz" || p == INFO_PATH || p.startsWith("/download/") || p.startsWith("/broker/")
    }

    internal fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /** HPKE base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / ChaCha20-Poly1305. */
    internal fun hpke(): HPKE = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)

    fun info(kid: String): ByteArray = ascii(INFO_PREFIX + kid)
}

/** Why a KE2E exchange failed. An [IOException], so an HTTP client treats it as a failed call, never as an answer. */
class Ke2eException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind {
        /** The server answered 409 e2e_key_changed, or its key is not the pinned one: pair again. */
        KEY_CHANGED,
        /** Not a KE2E answer (wrong content type, a plaintext answer from a pinned server): the server must be updated. */
        NOT_E2E,
        /** A frame did not open, a counter gap, a frame too large, bytes after the last frame. */
        BAD_FRAME,
        /** The stream ended before the last frame. */
        TRUNCATED,
        /** A malformed key, link or envelope. */
        MALFORMED,
    }
}

/** The server's static X25519 public key with what is derived from it (spec 1.2). */
class ServerKey(publicKey: ByteArray) {
    val publicKey: ByteArray = publicKey.copyOf()

    init {
        if (publicKey.size != Ke2e.PUBLIC_KEY_BYTES) throw Ke2eException(Ke2eException.Kind.MALFORMED, "public key must be 32 bytes")
    }

    private val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(this.publicKey)

    /** First 8 bytes of SHA-256(pkS), as sent in the outer request. */
    val kidBytes: ByteArray get() = digest.copyOf(Ke2e.KID_BYTES)

    /** First 8 bytes of SHA-256(pkS), lowercase hex (16 characters): goes into `info`. */
    val kid: String = kidBytes.joinToString("") { "%02x".format(it) }

    /** For people: SHA-256(pkS), first 15 bytes, base32 without padding, groups of 4 (`ABCD-EFGH-…`, 6 groups). */
    val fingerprint: String = Base32.encode(digest.copyOf(15)).chunked(4).joinToString("-")

    /** base64url without padding, as in the pairing link's `k=` and `/api/e2e/info`'s `pk`. */
    val base64Url: String get() = B64Url.encode(publicKey)

    override fun equals(other: Any?): Boolean = other is ServerKey && MessageDigest.isEqual(publicKey, other.publicKey)
    override fun hashCode(): Int = kid.hashCode()
    override fun toString(): String = "ServerKey(kid=$kid)"

    companion object {
        fun fromBase64Url(s: String): ServerKey = ServerKey(
            B64Url.decode(s) ?: throw Ke2eException(Ke2eException.Kind.MALFORMED, "public key is not base64url"),
        )

        /**
         * GET /api/e2e/info's answer `{"v": 1, "kid", "pk", "fingerprint"}`, checked: version 1, and `kid`/`fingerprint` (when
         * present) must match what `pk` gives. Null when it does not hold together.
         */
        fun fromInfo(v: Int?, pk: String?, kid: String?, fingerprint: String?): ServerKey? {
            if (v != 1 || pk == null) return null
            val key = runCatching { fromBase64Url(pk) }.getOrNull() ?: return null
            if (kid != null && kid != key.kid) return null
            if (fingerprint != null && fingerprint.replace("-", "").uppercase() != key.fingerprint.replace("-", "")) return null
            return key
        }
    }
}

/**
 * A pinned server key and how it was learned. [verified]: it came from the QR / link the owner's screen showed (`k=`), or the
 * fingerprint was confirmed by scanning once. False: trust on first use (typed code, devices paired before KE2E) — My → Devices
 * shows "key not verified" with the fingerprint.
 */
data class KeyPin(val key: ServerKey, val verified: Boolean) {
    /** What to keep in the app's sealed store (one line; the key is public, the pin is not secret but must not be swapped). */
    fun encode(): String = (if (verified) "v1:verified:" else "v1:tofu:") + key.base64Url

    companion object {
        fun decode(s: String): KeyPin? {
            val parts = s.split(':')
            if (parts.size != 3 || parts[0] != "v1") return null
            val verified = when (parts[1]) { "verified" -> true; "tofu" -> false; else -> return null }
            val key = runCatching { ServerKey.fromBase64Url(parts[2]) }.getOrNull() ?: return null
            return KeyPin(key, verified)
        }
    }
}

/**
 * One HPKE context after setup, and the secrets KE2E exports from it. Sender side (the app) seals the request with [seal]; the
 * receiver side (the server, tests, a mock) opens it with [open]. The context counts its own sequence numbers.
 */
class Ke2eContext internal constructor(private val ctx: HPKEContext, val enc: ByteArray) {
    fun seal(aad: ByteArray, pt: ByteArray): ByteArray = ctx.seal(aad, pt)

    fun open(aad: ByteArray, ct: ByteArray): ByteArray = try {
        ctx.open(aad, ct)
    } catch (e: Exception) {
        throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "envelope did not open")
    }

    fun export(label: String, length: Int = 32): ByteArray = ctx.export(Ke2e.ascii(label), length)

    internal fun exportRaw(exporterContext: ByteArray, length: Int = 32): ByteArray = ctx.export(exporterContext, length)

    /** The key of the response frames. */
    fun responseKey(): ByteArray = export(Ke2e.EXPORT_RESPONSE)

    /** The WebSocket's two keys, app to server and server to app. */
    fun wsKeys(): Pair<ByteArray, ByteArray> = export(Ke2e.EXPORT_WS_C2S) to export(Ke2e.EXPORT_WS_S2C)

    /** The key of a streamed request's body frames (envelope 0x02). */
    fun requestBodyKey(): ByteArray = export(Ke2e.EXPORT_REQUEST_BODY)

    companion object {
        /** SetupBaseS(pkS, info). [ephemeral] only for test vectors (a fixed skE); the app always leaves it null. */
        fun sender(server: ServerKey, ephemeral: X25519KeyPair? = null): Ke2eContext =
            senderWithInfo(server.publicKey, Ke2e.info(server.kid), ephemeral)

        /** SetupBaseR(enc, skS, info): the server's side, here for tests and a local mock server. */
        fun receiver(enc: ByteArray, server: X25519KeyPair): Ke2eContext =
            receiverWithInfo(enc, server, Ke2e.info(ServerKey(server.publicKey).kid))

        /** Any `info` (the RFC 9180 test vectors use their own). */
        internal fun senderWithInfo(pkR: ByteArray, info: ByteArray, ephemeral: X25519KeyPair?): Ke2eContext {
            val h = Ke2e.hpke()
            val pk = try {
                h.deserializePublicKey(pkR)
            } catch (e: Exception) {
                throw Ke2eException(Ke2eException.Kind.MALFORMED, "bad public key")
            }
            val c = try {
                if (ephemeral == null) h.setupBaseS(pk, info) else h.setupBaseS(pk, info, ephemeral.toBc(h))
            } catch (e: Exception) {   // e.g. a low-order point: the X25519 result would be all zeros
                throw Ke2eException(Ke2eException.Kind.MALFORMED, "key agreement failed")
            }
            return Ke2eContext(c, c.encapsulation.copyOf())
        }

        internal fun receiverWithInfo(enc: ByteArray, server: X25519KeyPair, info: ByteArray): Ke2eContext {
            val h = Ke2e.hpke()
            val c = try {
                h.setupBaseR(enc, server.toBc(h), info)
            } catch (e: Exception) {
                throw Ke2eException(Ke2eException.Kind.BAD_FRAME, "envelope did not open")
            }
            return Ke2eContext(c, enc.copyOf())
        }
    }
}

/** A raw X25519 key pair (32-byte private and public keys, RFC 7748 encoding). */
class X25519KeyPair(privateKey: ByteArray, publicKey: ByteArray) {
    internal val privateKey: ByteArray = privateKey.copyOf()
    val publicKey: ByteArray = publicKey.copyOf()

    internal fun toBc(h: HPKE): AsymmetricCipherKeyPair = h.deserializePrivateKey(privateKey, publicKey)

    /** Forget the private key's bytes (best effort on the JVM). */
    fun wipe() = privateKey.fill(0)

    override fun toString(): String = "X25519KeyPair(pk)"

    companion object {
        fun generate(): X25519KeyPair = fromBc(Ke2e.hpke().generatePrivateKey())

        /** DeriveKeyPair(ikm) of RFC 9180 (test vectors give ikmE / ikmR). */
        fun derive(ikm: ByteArray): X25519KeyPair = fromBc(Ke2e.hpke().deriveKeyPair(ikm))

        /** From a raw private key (the public key is computed). */
        fun fromPrivate(sk: ByteArray): X25519KeyPair {
            val p = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(sk, 0)
            return X25519KeyPair(sk, p.generatePublicKey().encoded)
        }

        private fun fromBc(kp: AsymmetricCipherKeyPair): X25519KeyPair {
            val h = Ke2e.hpke()
            return X25519KeyPair(h.serializePrivateKey(kp.private), h.serializePublicKey(kp.public))
        }
    }
}

/** RFC 4648 base64url without padding (decoding also accepts padding). */
object B64Url {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun encode(b: ByteArray): String = enc.encodeToString(b)
    fun decode(s: String): ByteArray? = runCatching { dec.decode(s.trim().trimEnd('=')) }.getOrNull()
}

/** RFC 4648 base32 (upper case, no padding). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun encode(b: ByteArray): String {
        val out = StringBuilder((b.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (x in b) {
            buffer = (buffer shl 8) or (x.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
