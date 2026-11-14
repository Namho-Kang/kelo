package chat.kelo.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import chat.kelo.app.platform.QrScanner
import chat.kelo.app.res.*
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.KeloTextField
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.text.*
import org.jetbrains.compose.resources.StringResource

/**
 * The computer has no camera: where the phone scans a pairing QR to check the server's key, this takes the pairing link pasted
 * (the same link the QR holds), through the shared scan seam.
 */
object DesktopLinkScanner : QrScanner {
    override val startLabel: StringResource get() = Res.string.desktop_e2e_verify_paste

    @Composable
    override fun Scan(onClose: () -> Unit, accept: (String) -> Boolean, onScanned: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        SubPage(string(Res.string.devices_e2e_title), onClose) {
            MutedText(string(Res.string.desktop_e2e_not_verified_body), small = true)
            KeloTextField(text, { text = it; invalid = false }, string(Res.string.desktop_e2e_paste_hint))
            SecondaryButton(string(Res.string.desktop_e2e_verify_paste), enabled = text.isNotBlank(), onClick = {
                if (accept(text.trim())) onScanned(text.trim()) else invalid = true
            })
            if (invalid) ErrorText(string(Res.string.connect_link_invalid), small = true)
        }
    }
}
