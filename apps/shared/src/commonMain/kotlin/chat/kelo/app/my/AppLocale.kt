package chat.kelo.app.my

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import chat.kelo.app.ui.text.textLocale
import java.util.Locale

/** The language the app is shown in (each app root provides it as the text locale). */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale = textLocale()
