package chat.kelo.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/**
 * The app update check of the builds handed out as a file (sideload, full), apart from Android: which builds check, how versions
 * compare, what https://kelo.chat/download/latest.json says, and how a downloaded APK is checked. A Google Play build never checks
 * (the store updates it). The check is a plain GET of the public version list: no account, server, token or device data goes with it,
 * only a User-Agent naming the app and its version.
 */
object UpdateRules {
    const val LATEST_PATH = "/download/latest.json"
    const val PLATFORM = "android"
    /** Once a day while the app runs, and at every start */
    const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Builds handed out as a file check for updates; the Play build gets them from the store. */
    fun supported(flavor: String): Boolean = flavor != "play"

    /** "0.1.402322" -> [0, 1, 402322]; a part that does not start with digits counts as 0; "1.0.2-beta" -> [1, 0, 2]. */
    fun parts(version: String): List<Long> = version.trim().removePrefix("v").split('.').map { p ->
        p.takeWhile { it.isDigit() }.take(18).toLongOrNull() ?: 0L
    }

    /** True only when [remote] is strictly newer than [local] (missing parts are 0: "1.2" == "1.2.0"). A blank one is never newer. */
    fun newer(remote: String, local: String): Boolean {
        if (remote.isBlank()) return false
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    /** latest.json's entry for [platform], when it is complete and sane; null otherwise (nothing is offered). */
    fun parse(body: String, platform: String = PLATFORM): Release? {
        val all = runCatching { json.decodeFromString<Map<String, Release>>(body) }.getOrNull() ?: return null
        val r = all[platform] ?: return null
        if (r.version.isBlank() || r.size <= 0 || !SHA256.matches(r.sha256.lowercase()) || r.url.isBlank()) return null
        return r.copy(sha256 = r.sha256.lowercase())
    }

    /** The file's address: latest.json gives "/download/android" (relative to where it was read) or a full https address. */
    fun resolve(base: String, url: String): String = runCatching { URI(base.trimEnd('/') + "/").resolve(url).toString() }.getOrDefault(url)

    /**
     * A release worth offering: newer than what runs. latest.json's "version_code" (the rising build number) decides when it is
     * there, so 0.9.0 build 404800 is offered over 0.9.0 build 404700 ([runningCode]); without it the version names are compared.
     */
    fun offer(release: Release?, running: String, runningCode: Long = 0): Release? = release?.takeIf {
        if (it.code > 0 && runningCode > 0) it.code > runningCode else newer(it.version, running)
    }

    /** Due for a check: never checked in this run, or the last one is a day old. */
    fun due(lastCheckMs: Long?, nowMs: Long): Boolean = lastCheckMs == null || nowMs - lastCheckMs >= CHECK_EVERY_MS || nowMs < lastCheckMs

    /**
     * The server's `min_app_version` for this platform says this build is too old: [running] (this build's versionCode) is below
     * [min]. Null or 0 means no minimum; an unknown running build (0) is never held back.
     */
    fun belowMin(running: Long, min: Long?): Boolean = min != null && min > 0 && running > 0 && running < min

    /** The small banner shows once per offered version (closing it or tapping 받기 marks it seen). */
    fun showBanner(offered: String?, seen: String?): Boolean = offered != null && offered != seen

    /** The honest User-Agent of the check and the download. */
    fun userAgent(version: String): String = "Kelo-Android/$version"

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The downloaded file is the published one: same length and same SHA-256. */
    fun verified(actualSha: String, actualSize: Long, release: Release): Boolean =
        actualSize == release.size && actualSha.equals(release.sha256, ignoreCase = true)

    /** Download progress in whole percent (0..100); unknown total -> 0. */
    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

    /** "28981523" -> "27.6 MB" (as the download page shows it), with a no-break space so the number and unit never part on a wrap. */
    fun size(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 100) String.format(java.util.Locale.ROOT, "%.1f\u00A0MB", mb) else String.format(java.util.Locale.ROOT, "%.0f\u00A0MB", mb)
    }

    private val SHA256 = Regex("^[0-9a-f]{64}$")
}

/** One platform's row of latest.json. */
@Serializable
data class Release(
    val version: String = "",
    /** The build's versionCode (newer servers' latest.json); 0 = not given */
    @kotlinx.serialization.SerialName("version_code") val code: Long = 0,
    val size: Long = 0,
    val sha256: String = "",
    val date: String = "",
    val file: String = "",
    val url: String = "",
)
