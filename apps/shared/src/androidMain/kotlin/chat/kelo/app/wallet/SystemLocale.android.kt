package chat.kelo.app.wallet

import java.util.Locale

internal actual fun systemLocale(): Locale = android.content.res.Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
