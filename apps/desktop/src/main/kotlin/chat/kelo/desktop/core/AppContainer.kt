package chat.kelo.desktop.core

import chat.kelo.app.chat.AgentActivity
import chat.kelo.app.AppServices
import chat.kelo.app.Caches
import chat.kelo.app.agent.AgentAvatar
import chat.kelo.app.core.AcceptLanguage
import chat.kelo.app.core.KeloApi
import chat.kelo.app.core.KeyValueStore
import chat.kelo.app.core.Languages
import chat.kelo.desktop.i18n.DesktopLocale
import chat.kelo.app.core.Profile
import chat.kelo.app.core.ScreenCache
import chat.kelo.app.core.SecureStore
import chat.kelo.app.core.Sessions
import chat.kelo.app.core.ValueCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

/** The app's choices that are not secret (kept in kelo.properties). Each one is a flow the screens watch. */
class Settings(private val kv: KeyValueStore) {
    /** "system" or a language code of chat.kelo.app.core.Languages */
    val language = MutableStateFlow(kv.getString(LANGUAGE)?.takeIf(Languages::isKnown) ?: Languages.SYSTEM)
    /** "system", "light" or "dark" */
    val theme = MutableStateFlow(kv.getString(THEME) ?: "system")
    /** Desktop notifications (finished replies, approvals waiting, the agent's own news) */
    val notifications = MutableStateFlow(kv.getString(NOTIFY) != "0")
    /** Closing the window leaves Kelo in the system tray */
    val keepInTray = MutableStateFlow(kv.getString(TRAY) != "0")
    val reduceMotion = MutableStateFlow(kv.getString(MOTION) == "1")

    fun setLanguage(v: String) { language.value = v; kv.putString(LANGUAGE, v) }
    fun setTheme(v: String) { theme.value = v; kv.putString(THEME, v) }
    fun setNotifications(v: Boolean) { notifications.value = v; kv.putString(NOTIFY, if (v) "1" else "0") }
    fun setKeepInTray(v: Boolean) { keepInTray.value = v; kv.putString(TRAY, if (v) "1" else "0") }
    fun setReduceMotion(v: Boolean) { reduceMotion.value = v; kv.putString(MOTION, if (v) "1" else "0") }
    /** The newest app version whose "새 버전이 있어요" banner was closed or used (it shows once per version; My keeps the card) */
    val dismissedUpdate = MutableStateFlow(kv.getString(UPDATE_SEEN).orEmpty())
    fun setDismissedUpdate(v: String) { dismissedUpdate.value = v; kv.putString(UPDATE_SEEN, v) }

    /** The newest server notification already shown for [server] (null: none yet, so old ones are not replayed). */
    fun lastNote(server: String): Long? = kv.getString(NOTE + hash(server))?.toLongOrNull()
    fun setLastNote(server: String, id: Long) = kv.putString(NOTE + hash(server), id.toString())

    private fun hash(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** The language the app is shown in: the pick, else the system's when the app has it, else English. */
    fun shownLanguage(): String = Languages.override(language.value) ?: Languages.suggested(listOf(DesktopLocale.system.toLanguageTag()))

    companion object {
        private const val LANGUAGE = "language.pick"
        private const val THEME = "theme"
        private const val NOTIFY = "notifications"
        private const val TRAY = "keep_in_tray"
        private const val MOTION = "reduce_motion"
        private const val NOTE = "last_note."
        private const val UPDATE_SEEN = "update.banner_seen"
    }
}

/** A desktop notification the tray shows. */
data class DesktopNote(
    val title: String, val body: String, val kind: Kind = Kind.INFO,
    /** What a click on the notification (the tray's balloon or icon right after it) opens, as on the phone (NotifyRules.target) */
    val target: chat.kelo.app.notify.OpenTarget? = null,
) {
    enum class Kind { INFO, WARNING }
}

/**
 * Everything the app shares: the saved sign-in ([sessions]), the settings, what the agent is doing ([activity]) and the
 * notifications for the tray. One per run.
 */
class AppContainer(
    val dataDir: File = DesktopPlatform.dataDir,
    kv: KeyValueStore = FileKeyValueStore(File(dataDir, "kelo.properties")),
    cipher: ValueCipher = StoreKey.cipher(dataDir),
    /**
     * KE2E always (spec 5.18: never a plaintext call; a server without KE2E gets the update-your-server screen). Only tests against
     * the plain mock server (tools/mock_server.py) pass false; nothing at run time can turn it off.
     */
    val requireE2e: Boolean = true,
) : AppServices {
    init {
        DesktopVersion.install()   // X-Kelo-App on every call, min_server for the answers (docs/versioning.md)
    }

    val store = SecureStore(kv, cipher)
    val settings = Settings(kv)
    private val cache = ScreenCache(File(dataDir, "cache"), cipher)
    /**
     * The HTTP client calls to the Kelo server start from (pairing, the key check). Its version interceptor runs first; each session
     * adds the KE2E interceptor after it (Sessions.apiFor), so the header travels inside the envelope.
     */
    val apiClient: okhttp3.OkHttpClient = KeloApi.defaultClient
    override val sessions = Sessions(store, apiClient, cache, requireE2e)
    override val appVersion: String = ReleaseInfo.VERSION
    override val sourceCommit: String = ReleaseInfo.SOURCE_COMMIT
    override val sourceDirty: Boolean = ReleaseInfo.SOURCE_DIRTY
    /** The agent's picture: one holder per run, shared by the header and the status poll */
    override val agentAvatar = AgentAvatar(sessions)
    /** Pairing and the key sign-in: a link's `k` pins the key (verified); otherwise the key held for that server, else TOFU. */
    override val pairing = chat.kelo.app.core.PairingClient(apiClient, store::pinFor)
    override fun deviceName(): String = DesktopPlatform.deviceName() + " · Kelo"
    override val allowLocalHttp: Boolean = true
    val activity = AgentActivity()
    override val agentActivity: AgentActivity get() = activity
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The signed-in server's profile (null until loaded) */
    val profile = MutableStateFlow<Profile?>(runCatching { sessions.cached(Caches.PROFILE) }.getOrNull())
    /** The main window is on screen (not hidden in the tray, not minimized) */
    val windowShown = MutableStateFlow(true)
    /** The tray line of a reply that runs (its step, else the site), or null */
    val liveStep = MutableStateFlow<String?>(null)
    /** The way to stop the reply that runs (the tray's Stop), or null */
    val stopReply = MutableStateFlow<(() -> Unit)?>(null)
    /** Notifications for the tray to show */
    val notes = MutableSharedFlow<DesktopNote>(extraBufferCapacity = 16)
    /** A request from the tray or a notification to bring the window up on a page */
    val openPage = MutableStateFlow<String?>(null)
    /** The browser tab whose live view is open over the page, or null */
    val liveTab = MutableStateFlow<LiveRequest?>(null)
    /**
     * Where the app should go (a notification clicked, a card's button, the tray): the phone's chat.kelo.app.notify.OpenTarget, handled
     * by the shell (the page, the live view, a My or Work sub page).
     */
    override val openRequests = MutableStateFlow<chat.kelo.app.notify.OpenTarget?>(null)
    /** The chat should scroll to this message (a follow-up notification) */
    override val chatFocus = MutableStateFlow<Long?>(null)
    /** The chat should scroll to this secure entry's card and mark it for a moment (a row in 답이 필요해요) */
    override val chatEntryFocus = MutableStateFlow<String?>(null)
    override val chatFollowTurn = MutableStateFlow<String?>(null)
    /** A My sub page to open (MY_PAGE_*), set with the My page switch */
    override val myPageRequests = MutableStateFlow<String?>(null)
    /** A Work target (a task, a schedule, a section) for the Work page to open, set with the Work page switch */
    val workRequests = MutableStateFlow<chat.kelo.app.notify.OpenTarget?>(null)
    // ---- what the shared My and wallet pages ask of the device: the computer has no card key, no phone settings, no location
    override val filePicker: chat.kelo.app.platform.FilePicker = DesktopFilePicker()
    override val systemSettings: chat.kelo.app.platform.SystemSettings = chat.kelo.app.platform.SystemSettings.None
    override val browser: chat.kelo.app.platform.ExternalBrowser = chat.kelo.app.platform.ExternalBrowser { url -> Browser.open(url) }
    override val phoneData: chat.kelo.app.platform.PhoneDataSection? = null
    override val connectorIcons = chat.kelo.app.connectors.ConnectorIcons(File(dataDir, "connector-icons"))
    override val notifications: chat.kelo.app.platform.PhoneNotifications? = null
    override val phoneLocation: chat.kelo.app.platform.PhoneLocation? = null
    override val appUpdates: chat.kelo.app.platform.AppUpdateSource? = null
    override val language: chat.kelo.app.platform.AppLanguage = object : chat.kelo.app.platform.AppLanguage {
        override val systemShownCode: String get() = Languages.suggested(listOf(DesktopLocale.system.toLanguageTag()))
        override val current: String? get() = settings.language.value
        override fun pick(code: String) { settings.setLanguage(code); applyLanguage(); tellServerLanguage(code) }
    }
    override val cardKeys: chat.kelo.app.wallet.CardKeys = chat.kelo.app.wallet.CardKeys.None
    override val keyguardKeys: chat.kelo.app.keyguard.KeyguardKeys = chat.kelo.app.keyguard.KeyguardKeys.None
    override val cardScanner: chat.kelo.app.wallet.CardScanner? = null
    override val walletConsent: chat.kelo.app.wallet.WalletConsent = object : chat.kelo.app.wallet.WalletConsent {
        override fun cardSaved(cardId: String, at: Long) {}
        override fun autoPayAccepted(cardId: String, at: Long) {}
        override fun forget(cardId: String) {}
    }
    override val walletPlaces = chat.kelo.app.wallet.WalletPlaces(scope) { System.nanoTime() / 1_000_000 }
    override val secretHandoff = chat.kelo.app.chat.SecretHandoff()
    /** The app's own update check (core/Updates.kt); tests put one pointed at a mock here */
    var updates: Updater = Updater.forThisApp(dataDir)
    /** What the newest desktop notification opens when clicked (the tray gives no per-notification click) */
    @Volatile var lastNoteTarget: chat.kelo.app.notify.OpenTarget? = null

    init {
        // KE2E: a session from before KE2E (or a typed sign-in) pins the server's key trust-on-first-use at start and after each
        // sign-in (spec 1.3); until then every call is refused here rather than sent in the clear
        if (requireE2e) scope.launch {
            sessions.api.collect { api -> if (api != null && api.keyPin == null) runCatching { sessions.upgradeToE2e() } }
        }
        applyLanguage()
    }

    /** Tells the server which language to answer in (Accept-Language), after a pick. */
    fun applyLanguage() {
        AcceptLanguage.current = AcceptLanguage.build(Languages.override(settings.language.value), listOf(DesktopLocale.system.toLanguageTag()))
    }

    /**
     * The server keeps the owner's language for what it says on its own (notifications, the briefing, scheduled runs) and changes
     * it only from a pick (PUT /api/language), never from a request's language. Only on a pick in 앱 → 언어, not at every start, so
     * one computer in another language does not flip it. Signed in only; errors (an older server's 404 too) are ignored.
     */
    fun tellServerLanguage(code: String) {
        val api = sessions.api.value ?: return
        val language = Languages.forServer(code, listOf(DesktopLocale.system.toLanguageTag()))
        val body = kotlinx.serialization.json.buildJsonObject { put("language", kotlinx.serialization.json.JsonPrimitive(language)) }
        scope.launch { runCatching { api.request("PUT", "/api/language", body) } }
    }

    fun setProfile(p: Profile) {
        profile.value = p
        sessions.keep(Caches.PROFILE, p)
    }

    fun notify(note: DesktopNote) {
        if (!settings.notifications.value) return
        lastNoteTarget = note.target
        notes.tryEmit(note)
    }

    /** Opens what the newest notification pointed at (a click on its balloon), once. */
    fun openLastNote() {
        val t = lastNoteTarget ?: return
        lastNoteTarget = null
        openRequests.value = t
    }
}

/** A browser tab to show live: [control] takes it over at once (a human check to solve); [picture]: the opener's last picture of it. */
data class LiveRequest(val id: String, val control: Boolean = false, val picture: String? = null)

/** What [AppContainer.myPageRequests] carries (the phone's MY_PAGE_* in shell/MainShell.kt). */
const val MY_PAGE_WALLET = "wallet"
const val MY_PAGE_AGENT = "agent"
const val MY_PAGE_MODEL = "model"
const val MY_PAGE_OPERATOR = "operator"
/** 마이 → 서버 업데이트 (a server update notification; owner only) */
const val MY_PAGE_SERVER_UPDATE = "server-update"
/** 마이 → Kelo 서버 자원 (a storage warning), and its cleanup screen */
const val MY_PAGE_RESOURCES = "resources"
const val MY_PAGE_CLEANUP = "cleanup"

