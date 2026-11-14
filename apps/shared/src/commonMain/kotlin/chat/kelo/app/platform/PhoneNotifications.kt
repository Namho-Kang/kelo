package chat.kelo.app.platform

import androidx.compose.runtime.Composable

/** The phone's own notification permission, for the notification rows. Null in [chat.kelo.app.AppServices] = this device has no such switch. */
interface PhoneNotifications {
    /** Whether notifications are on now, re-read whenever the app comes back to the front. */
    @Composable fun isOn(): Boolean

    /** The action that turns them on: the system's question where it may still be asked, else the settings page. */
    @Composable fun rememberEnabler(): () -> Unit

    /** Signed in with the access key: the server keeps push tokens only for paired phones, so the row says so. */
    fun pushNeedsDevice(baseUrl: String): Boolean

    /** Removes the notifications that are showing (after the chat is cleared). */
    fun cancelAll()
}
