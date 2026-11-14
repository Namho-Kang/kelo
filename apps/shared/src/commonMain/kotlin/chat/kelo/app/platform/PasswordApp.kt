package chat.kelo.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The device's password app (an autofill service) that can fill a login form. [available] only when one is set up; [request] asks it to
 * fill the form that has the focus. The Android root provides it; the desktop has none (null).
 */
interface PasswordApp {
    val available: Boolean
    fun request()
}

val LocalPasswordApp = staticCompositionLocalOf<PasswordApp?> { null }
