package chat.kelo.app.platform

/** LOCATION: the phone's own location switch (Android's location settings), not Kelo's permission. */
enum class SettingsPage { APP_DETAILS, NOTIFICATIONS, SECURITY, LOCATION }

/**
 * Opens the system's own settings ("turn it on there"). A platform without such a page answers false from [canOpen] and the rows
 * that need it are not shown.
 */
interface SystemSettings {
    fun canOpen(page: SettingsPage): Boolean
    fun open(page: SettingsPage)

    companion object {
        /** No settings pages (the desktop). */
        val None: SystemSettings = object : SystemSettings {
            override fun canOpen(page: SettingsPage) = false
            override fun open(page: SettingsPage) {}
        }
    }
}
