package chat.kelo.app.core

/**
 * A request to the Kelo server that did not succeed.
 *
 * [status] is the HTTP status, or [NO_RESPONSE] when the server could not be reached at all (no network, wrong address,
 * TLS failure, a cleartext address the network security config blocks). [detail] is the server's `{"detail": "..."}` text when
 * it sent one as a string, otherwise "HTTP <status>" . [detailJson] is the server's `detail` when it is an object (e.g. the 409
 * `secret_in_message` answer the chat reads with [chat.kelo.app.chat.SecretGuard.parse]); null otherwise.
 */
class ApiError(
    val status: Int, val detail: String, cause: Throwable? = null,
    val detailJson: kotlinx.serialization.json.JsonObject? = null,
    /** The answer's `code` (top level, or inside an object `detail`): e.g. "app_too_old", "e2e_key_changed", "locked" */
    val code: String? = null,
) : Exception(detail, cause) {
    val isNetwork: Boolean get() = status == NO_RESPONSE
    val isUnauthorized: Boolean get() = status == 401
    /** This app build is older than the server works with (426): the update-required screen */
    val isAppTooOld: Boolean get() = status == 426 || code == CODE_APP_TOO_OLD
    /** KE2E: the server's key is not the one this phone pinned: pair again */
    val isKeyChanged: Boolean get() = code == CODE_KEY_CHANGED
    /** KE2E: a pinned server answered without KE2E: it must be updated (never a reason to talk in the clear) */
    val isE2eUnsupported: Boolean get() = code == CODE_E2E_UNSUPPORTED

    /**
     * Worth trying again (a network failure, a server error, a timeout or a rate limit). Not a 507: storage is full (the cap, or
     * the server's own disk, `"code": "disk_full"`), which trying again does not fix; its words say what to do.
     */
    val isTransient: Boolean get() = !isE2eUnsupported && !isKeyChanged &&
        (isNetwork || (status >= 500 && status != INSUFFICIENT_STORAGE) || status == 408 || status == 429)

    companion object {
        const val NO_RESPONSE = 0
        const val INSUFFICIENT_STORAGE = 507
        const val CODE_APP_TOO_OLD = "app_too_old"
        const val CODE_KEY_CHANGED = "e2e_key_changed"
        const val CODE_E2E_UNSUPPORTED = "e2e_unsupported"
        const val CODE_E2E_REQUIRED = "e2e_required"
        const val CODE_LOCKED = "locked"
    }
}
