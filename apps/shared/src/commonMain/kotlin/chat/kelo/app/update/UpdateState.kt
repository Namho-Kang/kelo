package chat.kelo.app.update

/** Where a download stands. */
enum class UpdatePhase { IDLE, DOWNLOADING, VERIFYING, READY, FAILED, MISMATCH }

/**
 * [offer]: a newer build than the one running (null: none, or not checked yet). [checking] / [checkFailed]: the last check.
 * [needsPermission]: the APK is ready but Android does not yet let Kelo hand it to the installer (the user is sent to the setting).
 * [play]: the offer comes from Google Play (no version or size of its own to show; READY means "restart to apply").
 * [required]: the server says this build is too old (`min_app_version`): the required-update screen covers the app.
 */
data class UpdateState(
    val enabled: Boolean = false,
    val running: String = "",
    val checking: Boolean = false,
    val checked: Boolean = false,
    val checkFailed: Boolean = false,
    val offer: Release? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Int = 0,
    val needsPermission: Boolean = false,
    val bannerSeen: String? = null,
    val play: Boolean = false,
    val required: Boolean = false,
    /** A line for the app that must be left before its installer runs (the desktop's "quit Kelo to install"); null on the phone. */
    val installNote: String? = null,
    /** The app's own words for a failed download or install (the desktop: where the installer is); null = the general update error. */
    val failureDetail: String? = null,
) {
    val showBanner: Boolean get() = enabled && !required && UpdateRules.showBanner(offer?.version, bannerSeen)
}
