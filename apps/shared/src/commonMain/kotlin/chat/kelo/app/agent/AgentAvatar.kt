package chat.kelo.app.agent

import androidx.compose.ui.graphics.ImageBitmap
import chat.kelo.app.ui.platform.decodeImage
import chat.kelo.app.Caches
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/** The agent's picture as kept on the phone (encrypted, like every screen's data): its version and the bytes in base64. */
@Serializable
data class AvatarBlob(val version: Long = 0, val data: String = "")

/**
 * The agent's picture (PUT/GET/DELETE /api/agent/avatar). [image] is null while there is none (Kelo's character shows then). The
 * server's avatar_version (in GET /api/agent/status and the profile) says when it changed; the picture is fetched only then, and
 * kept for the next launch.
 */
class AgentAvatar(private val sessions: Sessions) {
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()
    private var shown = -1L
    private val lock = Mutex()

    /** Brings the picture in step with [version] (0 = none). */
    suspend fun sync(version: Long) = lock.withLock {
        if (version == shown) return@withLock
        if (version <= 0) {
            _image.value = null
            shown = 0L
            sessions.keep(Caches.AVATAR, AvatarBlob())
            return@withLock
        }
        // kept from last time?
        val kept = withContext(Dispatchers.IO) { runCatching { sessions.cached(Caches.AVATAR) }.getOrNull() }
        if (kept != null && kept.version == version && kept.data.isNotEmpty()) {
            decode(java.util.Base64.getDecoder().decode(kept.data))?.let { _image.value = it; shown = version; return@withLock }
        }
        val api = sessions.api.value ?: return@withLock
        try {
            val bytes = api.download("/api/agent/avatar")
            val img = decode(bytes) ?: return@withLock
            _image.value = img
            shown = version
            sessions.keep(api, Caches.AVATAR, AvatarBlob(version, java.util.Base64.getEncoder().encodeToString(bytes)))
        } catch (e: ApiError) {
            if (e.status == 404) { _image.value = null; shown = 0 }
        }
    }

    /** Shows the kept picture at once on launch (before the server is asked). */
    suspend fun warm() = lock.withLock {
        if (shown >= 0) return@withLock
        val kept = withContext(Dispatchers.IO) { runCatching { sessions.cached(Caches.AVATAR) }.getOrNull() } ?: return@withLock
        if (kept.version > 0 && kept.data.isNotEmpty()) {
            decode(java.util.Base64.getDecoder().decode(kept.data))?.let { _image.value = it; shown = kept.version }
        }
    }

    /** PUT the picture (JPEG/PNG/WebP, ≤ 2 MB); the server makes it 512×512. Returns the new version. */
    suspend fun upload(api: KeloApi, bytes: ByteArray, type: String): Long {
        val req = api.requestBuilder("/api/agent/avatar").put(bytes.toRequestBody(type.toMediaTypeOrNull())).build()
        val answer = KeloApi.readJson(api.send(req).toString(Charsets.UTF_8))
        val v = KeloApi.json.decodeFromJsonElement(AvatarSaved.serializer(), answer).avatarVersion
        lock.withLock { shown = -1L }
        sync(v)
        return v
    }

    suspend fun remove(api: KeloApi) {
        api.delete<JsonElement>("/api/agent/avatar")
        sync(0)
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        decodeImage(bytes)
    }

    companion object {
        const val MAX_BYTES = 2_000_000L
    }
}
