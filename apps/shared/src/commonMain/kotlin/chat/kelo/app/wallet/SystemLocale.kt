package chat.kelo.app.wallet

import java.util.Locale

/** The locale of the device's own settings (its region says which currencies are "home"), not the language the app was set to. */
internal expect fun systemLocale(): Locale
