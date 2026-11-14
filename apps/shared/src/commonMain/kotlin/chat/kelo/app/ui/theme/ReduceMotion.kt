package chat.kelo.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** True when the system asks for no animation (read in MainActivity on every resume). */
val LocalReduceMotion = staticCompositionLocalOf { false }
