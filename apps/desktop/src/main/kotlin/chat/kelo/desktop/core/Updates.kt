package chat.kelo.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One platform's row of the operator's public build list, https://kelo.chat/download/latest.json (src/assistant/downloads.py):
 * `url` may be relative ("/download/mac"); it is resolved against the address the list came from.
 */
@Serializable
data class LatestBuild(
    val version: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val date: String = "",
    val file: String = "",
    val url: String = "",
)

/**
 * The update check's pure rules (the Android sideload build has the same ones): which row of latest.json is this app's, whether
 * it is newer, where to fetch it, and that the downloaded file is the published one. The check sends nothing about the user: a
 * plain GET with an honest User-Agent.
 */
object UpdateRules {
    /** The operator's site; [base] lets tests point the check at a local mock (never used to publish anything). */
    const val DEFAULT_BASE = "https://kelo.chat"
    const val DAY_MS = 24L * 60 * 60 * 1000
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** The system property kelo.update.base, else the environment's KELO_UPDATE_BASE, else [DEFAULT_BASE]. */
    fun base(property: String?, env: String?): String =
        (property?.trim()?.takeIf { it.isNotEmpty() } ?: env?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE).trimEnd('/')

    /** The latest.json key of this computer's installer: the .exe on Windows, the .dmg on a Mac; Linux has none (no check). */
    fun platformKey(os: DesktopPlatform.Os): String? = when (os) {
        DesktopPlatform.Os.WINDOWS -> "windows"
        DesktopPlatform.Os.MAC -> "mac"
        DesktopPlatform.Os.LINUX -> null
    }

    private fun extension(key: String): String = if (key == "mac") ".dmg" else ".exe"

    /** Every platform row of latest.json (rows that are not objects are left out); an unreadable body is an empty map. */
    fun parse(body: String): Map<String, LatestBuild> = runCatching {
        json.parseToJsonElement(body) as JsonObject
    }.getOrNull()?.mapNotNull { (k, v) -> runCatching { k to json.decodeFromJsonElement(LatestBuild.serializer(), v) }.getOrNull() }
        ?.toMap().orEmpty()

    /** This platform's row when it is complete (a version, a size, a sha256, a plain file name with the right extension). */
    fun pick(all: Map<String, LatestBuild>, key: String?): LatestBuild? {
        val b = all[key ?: return null] ?: return null
        val ok = b.version.isNotBlank() && b.size > 0 && SHA.matches(b.sha256) && FILE.matches(b.file) &&
            b.file.lowercase(Locale.ROOT).endsWith(extension(key))
        return b.takeIf { ok }
    }

    /** The leading number of each dot-separated part ("1.0.2-beta" -> 1, 0, 2); a part without one counts as 0. */
    private fun parts(v: String): List<Long> =
        v.trim().removePrefix("v").split('.').map { p -> p.takeWhile(Char::isDigit).take(18).toLongOrNull() ?: 0L }

    /** Negative, zero or positive as [a] is older than, the same as, or newer than [b]; missing parts count as 0 (1.0 == 1.0.0). */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0L }).compareTo(y.getOrElse(i) { 0L })
            if (d != 0) return d
        }
        return 0
    }

    /** Only a strictly greater version is offered. */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    /** Where to download [b]: its url resolved against [base]; only http(s) (a mock on this computer is plain http). */
    fun downloadUrl(base: String, b: LatestBuild): String? {
        val raw = b.url.ifBlank { "/download/${b.file}" }
        val u = runCatching { URI("$base/").resolve(raw) }.getOrNull() ?: return null
        return u.toString().takeIf { u.scheme == "https" || u.scheme == "http" }
    }

    fun userAgent(version: String, os: DesktopPlatform.Os): String =
        "Kelo-Desktop/$version (${when (os) { DesktopPlatform.Os.WINDOWS -> "Windows"; DesktopPlatform.Os.MAC -> "macOS"; DesktopPlatform.Os.LINUX -> "Linux" }})"

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The downloaded file is the published one: its sha256 is the one latest.json names. */
    fun verify(file: File, expected: String): Boolean = file.isFile && sha256(file).equals(expected.trim(), ignoreCase = true)

    /** At start (never checked in this run) and then once a day. */
    fun due(lastCheckMs: Long?, nowMs: Long): Boolean = lastCheckMs == null || nowMs - lastCheckMs >= DAY_MS

    private val PLAIN_VERSION = Regex("^(\\d{1,9})(?:\\.(\\d{1,3})(?:\\.(\\d{1,5}))?)?$")

    /**
     * The server's number for a desktop version (`min_app_version.desktop`, ASSISTANT_MIN_APP_DESKTOP in src/assistant/server.py):
     * major*100000000 + minor*100000 + patch, so "1.0.3" -> 100000003, "1.1" -> 100100000. Null for anything else (a run from Gradle
     * has no version; a version with a suffix is never compared).
     */
    fun versionCode(version: String?): Long? {
        val m = PLAIN_VERSION.matchEntire(version?.trim() ?: return null) ?: return null
        val (major, minor, patch) = m.destructured
        return major.toLong() * 100_000_000L + (minor.toLongOrNull() ?: 0L) * 100_000L + (patch.toLongOrNull() ?: 0L)
    }

    /** This app is older than the server's minimum ([min]: `min_app_version.desktop`); an unknown version is never stopped. */
    fun belowMin(running: String?, min: Long?): Boolean {
        val code = versionCode(running) ?: return false
        return min != null && min > 0 && code > 0 && code < min
    }
}

/** Where the update check is: what My and the banner show. */
data class UpdateState(
    val phase: Phase = Phase.IDLE,
    val build: LatestBuild? = null,
    /** 0..1 while downloading */
    val progress: Float = 0f,
    /** The installer on disk (opened, or could not be opened) */
    val path: String? = null,
) {
    enum class Phase { IDLE, CHECKING, LATEST, CHECK_FAILED, AVAILABLE, DOWNLOADING, VERIFYING, MISMATCH, DOWNLOAD_FAILED, OPENED, OPEN_FAILED }

    /** A newer build is known (the banner and the My card show) */
    val newer: Boolean get() = build != null && phase !in setOf(Phase.IDLE, Phase.CHECKING, Phase.LATEST, Phase.CHECK_FAILED)
}

/**
 * The update check of the downloaded desktop app (the Microsoft Store copy and Linux get updates elsewhere; a run from Gradle has no
 * version): GET {base}/download/latest.json, and on "받기" the installer is downloaded into [dir], its sha256 checked, and only
 * then opened for the user (Windows: the .exe runs; macOS: the .dmg opens). Nothing is ever installed without the user.
 */
class Updater(
    val currentVersion: String?,
    private val os: DesktopPlatform.Os = DesktopPlatform.os,
    val base: String = UpdateRules.base(System.getProperty("kelo.update.base"), System.getenv("KELO_UPDATE_BASE")),
    private val dir: File,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val opener: (File) -> Boolean = ::openInstaller,
    private val store: Boolean = DesktopPlatform.isMsix,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val key = UpdateRules.platformKey(os)
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    @Volatile private var lastCheck: Long? = null
    private val _required = MutableStateFlow(false)
    /** The server says this version is too old (`min_app_version.desktop`): the shell covers the app with the required-update screen. */
    val required: StateFlow<Boolean> = _required.asStateFlow()

    /** This copy checks for updates at all. */
    val enabled: Boolean get() = currentVersion != null && key != null && !store

    /** How the required-update screen's button updates this copy. */
    enum class Way { DOWNLOAD, STORE, WEBSITE }

    /** The downloaded app updates itself; the Microsoft Store copy through the Store; Linux (no installer here) on the website. */
    val way: Way get() = when {
        store -> Way.STORE
        enabled -> Way.DOWNLOAD
        else -> Way.WEBSITE
    }

    /** The server's oldest supported version ([minCode]: `min_app_version.desktop`); below it a fresh check finds the installer. */
    suspend fun requireAtLeast(minCode: Long?) {
        val below = forced || UpdateRules.belowMin(currentVersion, minCode)
        val was = _required.value
        _required.value = below
        if (below && !was && enabled && _state.value.phase !in BUSY) check()
    }

    @Volatile private var forced = false

    /** The server refused this release (426 app_too_old): required from now on in this run, whatever min_app_version says. */
    suspend fun requireNow() {
        forced = true
        val was = _required.value
        _required.value = true
        if (!was && enabled && _state.value.phase !in BUSY) check()
    }

    /**
     * The required-update screen's button: the installer that is already known is downloaded at once (checked, then opened); without
     * one, a check first, then the download. The Store copy opens the Store's updates page; Linux opens the website.
     */
    suspend fun updateNow(openLink: (String) -> Boolean = ::openLink) {
        when (way) {
            Way.STORE -> openLink(STORE_UPDATES)
            Way.WEBSITE -> openLink(base)
            Way.DOWNLOAD -> {
                if (_state.value.phase in BUSY) return
                if (_state.value.phase == UpdateState.Phase.OPENED || _state.value.phase == UpdateState.Phase.OPEN_FAILED) { reopen(); return }
                if (!_state.value.newer) check()
                if (_state.value.newer) download()
            }
        }
    }

    /** The automatic check: at start, then once a day (the shell calls it every hour). */
    suspend fun checkIfDue() {
        if (enabled && UpdateRules.due(lastCheck, clock())) check()
    }

    /** The manual "업데이트 확인" too. A download in progress or done is left as it is. */
    suspend fun check() {
        val version = currentVersion ?: return
        if (!enabled || _state.value.phase in BUSY) return
        lastCheck = clock()
        _state.value = _state.value.copy(phase = UpdateState.Phase.CHECKING)
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("$base/download/latest.json").header("User-Agent", UpdateRules.userAgent(version, os)).build()
                client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}") else r.body?.string().orEmpty() }
            }.map { UpdateRules.pick(UpdateRules.parse(it), key) }
        }
        _state.value = found.fold(
            onSuccess = { b ->
                if (b != null && UpdateRules.isNewer(b.version, version)) UpdateState(UpdateState.Phase.AVAILABLE, b)
                else UpdateState(UpdateState.Phase.LATEST)
            },
            onFailure = { UpdateState(UpdateState.Phase.CHECK_FAILED) },
        )
    }

    /** "받기": download with progress, check the sha256 (a mismatch deletes the file), then open the installer. */
    suspend fun download() {
        val b = _state.value.build ?: return
        if (_state.value.phase in BUSY) return
        val url = UpdateRules.downloadUrl(base, b) ?: run { _state.value = UpdateState(UpdateState.Phase.DOWNLOAD_FAILED, b); return }
        _state.value = UpdateState(UpdateState.Phase.DOWNLOADING, b, 0f)
        val folder = File(dir, "updates").apply { mkdirs() }
        val target = File(folder, b.file)
        val part = File(folder, b.file + ".part")
        val got = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", UpdateRules.userAgent(currentVersion ?: "", os)).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body ?: error("empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: b.size
                    var done = 0L
                    var shown = -1
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != shown) { shown = pct; _state.value = UpdateState(UpdateState.Phase.DOWNLOADING, b, pct / 100f) }
                            }
                        }
                    }
                }
            }
        }
        if (got.isFailure) {
            part.delete()
            _state.value = UpdateState(UpdateState.Phase.DOWNLOAD_FAILED, b)
            return
        }
        _state.value = UpdateState(UpdateState.Phase.VERIFYING, b, 1f)
        val ok = withContext(Dispatchers.IO) { UpdateRules.verify(part, b.sha256) }
        if (!ok) {
            part.delete()
            _state.value = UpdateState(UpdateState.Phase.MISMATCH, b)
            return
        }
        target.delete()
        if (!part.renameTo(target)) part.copyTo(target, overwrite = true).also { part.delete() }
        open(b, target)
    }

    /** Opens the checked installer again ("설치" after it was closed). */
    suspend fun reopen() {
        val s = _state.value
        val f = s.path?.let(::File) ?: return
        val b = s.build ?: return
        if (withContext(Dispatchers.IO) { UpdateRules.verify(f, b.sha256) }) open(b, f) else _state.value = UpdateState(UpdateState.Phase.MISMATCH, b)
    }

    private suspend fun open(b: LatestBuild, f: File) {
        val opened = withContext(Dispatchers.IO) { runCatching { opener(f) }.getOrDefault(false) }
        _state.value = UpdateState(if (opened) UpdateState.Phase.OPENED else UpdateState.Phase.OPEN_FAILED, b, 1f, f.absolutePath)
    }

    companion object {
        private val BUSY = setOf(UpdateState.Phase.CHECKING, UpdateState.Phase.DOWNLOADING, UpdateState.Phase.VERIFYING)
        /** The Microsoft Store's own "Downloads and updates" page (the Store copy is updated there, never by this app). */
        const val STORE_UPDATES = "ms-windows-store://downloadsandupdates"

        /** The website in the browser; the Store page through Windows itself. */
        fun openLink(url: String): Boolean =
            if (url.startsWith("ms-windows-store:")) runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }.isSuccess
            else Browser.open(url)

        /** This app's own updater: the version the installer stamped (none when run from Gradle: then no check). */
        fun forThisApp(dataDir: File): Updater =
            Updater(System.getProperty("jpackage.app-version") ?: System.getProperty("kelo.version"), dir = File(dataDir, "cache"))

        /** Windows runs the .exe (its own installer window asks the user); macOS opens the .dmg in Finder. */
        fun openInstaller(f: File): Boolean = when (DesktopPlatform.os) {
            DesktopPlatform.Os.WINDOWS -> { ProcessBuilder(f.absolutePath).start(); true }
            DesktopPlatform.Os.MAC -> ProcessBuilder("open", f.absolutePath).start().let { pr -> pr.waitFor(20, TimeUnit.SECONDS) && pr.exitValue() == 0 }
            DesktopPlatform.Os.LINUX -> false
        }
    }
}
