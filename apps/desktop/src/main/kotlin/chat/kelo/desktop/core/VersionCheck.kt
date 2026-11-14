package chat.kelo.desktop.core

import chat.kelo.app.core.AppVersion

/**
 * The desktop's side of the min version contract (docs/versioning.md). The mechanism itself is shared with the phone
 * (chat.kelo.app.core.AppVersion: its interceptor is in KeloApi.defaultClient, before the KE2E interceptor each session adds, so the
 * header also travels inside the envelope and the answer's X-Kelo-Server is read after it is opened; 426 app_too_old reaches
 * Sessions.appTooOld through ApiError.code). Here only what the desktop says about itself.
 */
object DesktopVersion {
    /** e.g. "desktop/0.9.0+100000008": the release, then the package number's version code (major*100000000 + minor*100000 + patch). */
    fun header(version: String = ReleaseInfo.VERSION, packageVersion: String = ReleaseInfo.PACKAGE): String {
        val code = UpdateRules.versionCode(packageVersion)
        return "desktop/$version" + (code?.let { "+$it" } ?: "")
    }

    /** Once at start-up (AppContainer): every call to the Kelo server says this release; answers are compared with min_server. */
    fun install() {
        AppVersion.header = header()
        AppVersion.minServer = ReleaseInfo.MIN_SERVER
    }
}

/**
 * "Verify with a link" on My → My devices (the phone scans a QR; this device has no camera, so the owner pastes the connection link
 * from another device or `assistant phone add`): the same key as the pinned one marks it verified. The link's code is never used.
 */
object LinkVerify {
    enum class Result { DONE, MISMATCH, NO_KEY, NOT_A_LINK }

    fun check(text: String, verifyWith: (chat.kelo.app.core.e2e.ServerKey) -> Boolean): Result {
        val link = chat.kelo.app.core.PairingLinks.parse(text) ?: return Result.NOT_A_LINK
        val k = link.serverKey ?: return Result.NO_KEY
        val key = runCatching { chat.kelo.app.core.e2e.ServerKey.fromBase64Url(k) }.getOrNull() ?: return Result.NO_KEY
        return if (verifyWith(key)) Result.DONE else Result.MISMATCH
    }
}
