package chat.kelo.app.upload

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.KeloApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The upload limits of ONE signed-in session: asks GET /api/uploads/limits once and keeps the answer. [Sessions][chat.kelo.app.core.Sessions]
 * makes one per [KeloApi], so signing out or pairing again (a new api) starts over.
 * An old server answers 404 (or something unreadable): [UploadLimits.LEGACY], kept. Any other failure (no network, a server error): LEGACY
 * for this upload only, nothing kept, so the next upload asks again.
 */
class UploadLimitsSource(private val api: KeloApi) {
    private val lock = Mutex()
    @Volatile private var known: UploadLimits? = null

    suspend fun get(): UploadLimits {
        known?.let { return it }
        return lock.withLock {
            known?.let { return@withLock it }
            try {
                val limits = UploadLimits.parse(api.request("GET", "/api/uploads/limits")) ?: UploadLimits.LEGACY
                known = limits
                limits
            } catch (e: ApiError) {
                // 404: an old server. A 2xx answer that is not JSON (KeloApi reports it as status 0 with an IllegalArgumentException): unreadable.
                val oldServer = e.status == 404 || (e.isNetwork && e.cause is IllegalArgumentException)
                UploadLimits.LEGACY.also { if (oldServer) known = it }
            }
        }
    }
}
