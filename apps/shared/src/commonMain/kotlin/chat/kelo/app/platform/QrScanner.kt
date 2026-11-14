package chat.kelo.app.platform

import androidx.compose.runtime.Composable
import chat.kelo.app.res.*
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Scans a QR code with the camera, full screen: [accept] says whether a reading is wanted, [onScanned] gets it, [onClose] backs out.
 * The phone provides one; the desktop has none (null: the scan button is not shown).
 */
fun interface QrScanner {
    @Composable fun Scan(onClose: () -> Unit, accept: (String) -> Boolean, onScanned: (String) -> Unit)

    /** The text of the button that starts it (a camera scan by default; a scanner that takes a pasted link says so). */
    val startLabel: org.jetbrains.compose.resources.StringResource get() = Res.string.devices_e2e_verify_scan
}

val LocalQrScanner = staticCompositionLocalOf<QrScanner?> { null }
