package chat.kelo.app.core

import chat.kelo.app.core.e2e.E2eInfoCalls
import chat.kelo.app.core.e2e.Ke2eInterceptor
import chat.kelo.app.core.e2e.KeyPin
import chat.kelo.app.core.e2e.ServerKey
import chat.kelo.app.upload.FileUploader
import chat.kelo.app.upload.UploadLimitsSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/**
 * The signed-in state of this device. [api] is the [KeloApi] for the current session (null when signed out).
 * Any call that gets a 401 signs the device out.
 */
class Sessions(
    private val store: SecureStore,
    private val client: OkHttpClient = KeloApi.defaultClient,
    /** Each screen's last good data, per server (null in tests that do not need it) */
    private val cache: ScreenCache? = null,
    /** Never talk to a server in the clear (spec 5.18). Only tests against a plain stand-in server turn it off. */
    private val requireE2e: Boolean = true,
) {
    private val _api = MutableStateFlow(store.session()?.let(::apiFor))
    val api: StateFlow<KeloApi?> = _api.asStateFlow()

    private val _keyChanged = MutableStateFlow(false)
    /** KE2E: the server's key is not the one this phone pinned (409 e2e_key_changed): the app asks to pair again */
    val keyChanged: StateFlow<Boolean> = _keyChanged.asStateFlow()

    private val _appTooOld = MutableStateFlow(false)
    /** The server answered 426 app_too_old: this build is older than the server works with (the update-required screen) */
    val appTooOld: StateFlow<Boolean> = _appTooOld.asStateFlow()

    private val _locked = MutableStateFlow(false)
    /**
     * Keyguard: the server holds no key for the owner's passwords, keys and cards (423 {"code": "locked"}, /api/agent/status "locked"):
     * the app shows "Kelo is locked" with the unlock prompt.
     */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun setLocked(on: Boolean) { _locked.value = on }

    private val _serverOld = MutableStateFlow(false)
    /** The server offers no KE2E (GET /api/e2e/info missing): too old for this app, which never talks in the clear (spec 5.18) */
    val serverOld: StateFlow<Boolean> = _serverOld.asStateFlow()

    /** The signed-in server's pinned KE2E key (null: signed out, or not fetched yet) */
    val pin: KeyPin? get() = _api.value?.keyPin

    private val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val upgrading = kotlinx.coroutines.sync.Mutex()

    private val _historyCleared = MutableStateFlow(0)
    /**
     * Goes up each time the chat history was cleared on the server in this run (마이 → 대화 기록 삭제). Screens that hold messages,
     * tasks, background jobs, approvals or day summaries in memory drop them when it changes.
     */
    val historyCleared: StateFlow<Int> = _historyCleared.asStateFlow()

    /** The server cleared the chat history and what came of it: tells the screens (see [historyCleared]). */
    fun historyWasCleared() {
        _historyCleared.update { it + 1 }
    }

    /** The current API; throws when signed out (screens behind sign-in can use it). */
    fun requireApi(): KeloApi = _api.value ?: throw ApiError(401, "signed out")

    private var uploaderOf: Pair<KeloApi, FileUploader>? = null

    /**
     * The uploader of the signed-in session (the chat's tray and My → Documents both use it). It asks the server's upload limits once
     * per [KeloApi]: signing out or pairing again makes a new api (and clears this), so the answer is asked again.
     */
    @Synchronized
    fun uploader(): FileUploader {
        val api = requireApi()
        uploaderOf?.takeIf { it.first === api }?.let { return it.second }
        return FileUploader(api, UploadLimitsSource(api)).also { uploaderOf = api to it }
    }

    fun signIn(session: Session) {
        // a session without a key keeps the one pinned for that server before (5.5: once seen, never plain again)
        val s = if (session.pin == null) session.copy(pin = store.pinFor(session.serverUrl)) else session
        store.saveSession(s)
        _keyChanged.value = false
        _appTooOld.value = false
        _serverOld.value = false
        _locked.value = false
        AppVersion.reset()
        _api.value = apiFor(s)
    }

    /**
     * Pins [pin] for the signed-in server (TOFU upgrade, or the owner verified it) and switches every call to KE2E at once.
     */
    fun pinKey(pin: KeyPin) {
        val api = _api.value ?: return
        val session = Session(api.baseUrl, api.token, pin)
        store.saveSession(session)
        _api.value = apiFor(session)
    }

    /**
     * A scanned pairing link for the signed-in server carries [scanned] (its `k`): the pinned key is now verified when it is the same.
     * Returns false for another key (the caller says so; pairing again with that link replaces it).
     */
    fun verifyWith(scanned: ServerKey): Boolean {
        val pin = pin ?: return false
        if (pin.key != scanned) return false
        if (!pin.verified) pinKey(KeyPin(pin.key, verified = true))
        return true
    }

    /**
     * A device that has no key yet for its server (paired before KE2E, or signed in with a typed key or code): asks the server for
     * its key in the clear (GET /api/e2e/info) and pins it trust-on-first-use (key not verified, spec 1.3). A server without the
     * endpoint (404, or no answer) never offered KE2E: nothing changes, and the next start asks again. Safe to call often.
     */
    suspend fun upgradeToE2e() = upgrading.withLock {
        val api = _api.value ?: return@withLock
        if (api.keyPin != null) return@withLock
        when (val r = E2eInfoCalls.lookup(client, api.baseUrl)) {
            is E2eInfoCalls.Result.Found -> if (_api.value === api) { _serverOld.value = false; pinKey(KeyPin(r.key, verified = false)) }
            E2eInfoCalls.Result.Missing -> if (_api.value === api) _serverOld.value = true
            E2eInfoCalls.Result.Unreachable -> {}
        }
    }

    /** "Try again" on the update-your-server screen: the version and the key are asked for again. */
    fun recheckServer() {
        _serverOld.value = false
        AppVersion.reset()
        work.launch { runCatching { upgradeToE2e() } }
        _api.value?.let { api -> work.launch { runCatching { api.get<kotlinx.serialization.json.JsonElement>("/api/about") } } }
    }

    /** Pair again: forget the session and this server's old key (a new QR brings the new one). */
    fun pairAgain() {
        _api.value?.baseUrl?.let(store::forgetPin)
        signOut()
    }

    /**
     * Signing out from the My page: first takes this device off the server's device list (GET /api/devices for the current id, then
     * DELETE it), then signs out. Best effort: any failure, or no answer within [timeoutMs], still signs out here.
     */
    suspend fun signOutAndForget(timeoutMs: Long = 5000) {
        val api = _api.value
        if (api != null) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                runCatching {
                    val me = api.get<DeviceList>("/api/devices").current
                    if (me != null) api.delete<kotlinx.serialization.json.JsonElement>("/api/devices/$me")
                }
            }
        }
        signOut()
    }

    /** Signing out also forgets every screen's kept data. */
    fun signOut() {
        synchronized(this) { uploaderOf = null }
        store.clearSession()
        cache?.clear()
        _keyChanged.value = false
        _appTooOld.value = false
        _api.value = null
    }

    /** The kept value of [slot] for the signed-in server (null when signed out or nothing is kept). */
    fun <T> cached(slot: CacheSlot<T>): T? {
        val api = _api.value ?: return null
        return cache?.get(api.baseUrl, slot)
    }

    /** Keeps [value] as [slot]'s last good data, unless [api] (the session it was fetched with) is no longer the signed-in one. */
    fun <T> keep(api: KeloApi, slot: CacheSlot<T>, value: T) {
        if (_api.value === api) cache?.put(api.baseUrl, slot, value)
    }

    /** [keep] for the signed-in session. */
    fun <T> keep(slot: CacheSlot<T>, value: T) {
        _api.value?.let { keep(it, slot, value) }
    }

    /** Reads [slots] of the signed-in server into memory (call off the main thread, at start-up). */
    fun warmCache(slots: List<CacheSlot<*>>) {
        val api = _api.value ?: return
        cache?.warm(api.baseUrl, slots)
    }

    fun lastServerUrl(): String? = store.lastServerUrl()

    private fun apiFor(session: Session): KeloApi {
        lateinit var made: KeloApi
        val pin = session.pin
        // always through KE2E: without a key yet the calls are refused here (not sent in the clear) until upgradeToE2e pins one
        val http = if (pin == null && !requireE2e) client else client.newBuilder()
            .addInterceptor(Ke2eInterceptor(session.serverUrl, { pin }, onKeyChanged = { if (_api.value === made) _keyChanged.value = true }))
            .build()
        made = KeloApi(session.serverUrl, session.token, http, onUnauthorized = {
            if (_api.value === made) signOut()   // an old call finishing late must not sign out a newer session
        }, keyPin = pin, onFailure = { e -> if (_api.value === made) problem(e) })
        return made
    }

    /** Answers the app handles as a whole, whichever screen made the call. */
    private fun problem(e: ApiError) {
        when {
            e.isAppTooOld -> _appTooOld.value = true
            e.status == 423 || e.code == ApiError.CODE_LOCKED -> _locked.value = true
            e.isKeyChanged -> _keyChanged.value = true
            // a token marked KE2E-only was used in the clear (this phone lost its key): fetch it again and go on through KE2E
            e.status == 403 && e.code == ApiError.CODE_E2E_REQUIRED -> work.launch { runCatching { upgradeToE2e() } }
            e.isE2eUnsupported && pin == null -> work.launch { runCatching { upgradeToE2e() } }
        }
    }
}
