package chat.kelo.app.my

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.MyCard
import chat.kelo.app.ui.components.KeyBox
import chat.kelo.app.ui.components.SubPage
import chat.kelo.app.ui.components.ConfirmDialog
import chat.kelo.app.ui.components.Tag
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import chat.kelo.app.LocalAppServices
import chat.kelo.app.core.ApiError
import chat.kelo.app.core.Device
import chat.kelo.app.core.DeviceList
import chat.kelo.app.core.ServerAddress
import chat.kelo.app.ui.components.ErrorText
import chat.kelo.app.ui.components.LinkButton
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.components.SecondaryButton
import chat.kelo.app.ui.theme.Kelo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import chat.kelo.app.platform.LocalQrScanner
import chat.kelo.app.ui.platform.LocalTextSharer
import chat.kelo.app.ui.platform.rememberViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every phone or computer that can open this assistant. Add one with a QR code (made here from the link the server
 * returns); remove one without touching the others. The server has no way to rename a device, so names are shown as they are.
 */
@Composable
fun DevicesPage(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val sessions = LocalAppServices.current.sessions
    val vm = rememberViewModel { DevicesViewModel(sessions) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val devices = ui.devices
    val current = ui.current
    var removing by remember { mutableStateOf<Device?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val api by sessions.api.collectAsStateWithLifecycle()
    val pin = api?.keyPin
    val scanner = LocalQrScanner.current
    val err = if (ui.noLinkError) string(Res.string.devices_no_link) else ui.error

    if (scanning && scanner != null) {
        // a pairing QR from the server machine: the same key as the pinned one marks it verified (the code is not used)
        scanner.Scan(
            onClose = { scanning = false },
            accept = { raw -> chat.kelo.app.core.PairingLinks.parse(raw) != null },
            onScanned = { raw -> scanning = false; vm.verifyScanned(raw) },
        )
        return
    }
    SubPage(string(Res.string.devices_title), onBack) {
        MyCard {
            Text(string(Res.string.devices_e2e_title), fontWeight = FontWeight.Bold, color = Kelo.colors.ink)
            MutedText(string(Res.string.devices_e2e_on), small = true)
            if (pin != null) {
                MutedText(string(Res.string.devices_e2e_fingerprint), small = true)
                SelectionContainer {
                    Text(pin.key.fingerprint, color = Kelo.colors.ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Tag(string(if (pin.verified) Res.string.devices_e2e_verified else Res.string.devices_e2e_not_verified))
                if (!pin.verified) {
                    MutedText(string(Res.string.devices_e2e_not_verified_body), small = true)
                    if (scanner != null) SecondaryButton(string(scanner.startLabel), onClick = { vm.clearKeyNote(); scanning = true })
                }
            }
            ui.keyNote?.let { n ->
                if (n == Res.string.devices_e2e_verify_done) MutedText(string(n), small = true) else ErrorText(string(n), small = true)
            }
        }
        MyCard {
            MutedText(string(Res.string.devices_intro), small = true)
            if (devices.isEmpty()) MutedText(string(Res.string.devices_empty), small = true)
            devices.forEachIndexed { i, d ->
                KeyBox(first = i == 0) {
                    Text(
                        chat.kelo.app.ui.components.TokenText.fit(if (d.id == current) string(Res.string.devices_this_one, d.name) else d.name),
                        fontWeight = FontWeight.Bold, color = Kelo.colors.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    // given on the server machine (assistant phone owner): this phone can open the operator settings
                    if (d.owner) Tag(string(Res.string.devices_owner_badge))
                    MutedText(string(Res.string.devices_meta, fullDate(d.createdAt), agoText(d.lastSeen)), small = true)
                    LinkButton(string(Res.string.my_disconnect_button), onClick = { removing = d }, danger = true)
                }
            }
            val p = ui.pairing
            if (p != null) {
                KeyBox(first = devices.isEmpty()) { PairingBox(p, ui.secondsLeft, ui.copied, onCopied = vm::copied, onClose = vm::closePairing) }
            } else {
                SecondaryButton(string(Res.string.devices_add), onClick = vm::add)
            }
            if (p != null && !p.link.startsWith("https://") && !isLocal(p.link)) ErrorText(string(Res.string.devices_insecure), small = true)
            if (err.isNotEmpty()) ErrorText(err, small = true)
        }
    }
    removing?.let { d ->
        ConfirmDialog(
            text = if (d.id == current) string(Res.string.my_disconnect_confirm) else string(Res.string.devices_remove_confirm, d.name),
            yes = string(Res.string.my_disconnect_button),
            onYes = { vm.remove(d, onSignedOut) }, onDismiss = { removing = null },
        )
    }
}

private fun isLocal(link: String): Boolean {
    val server = ServerAddress.normalize(link.substringBefore('#'), defaultScheme = null) ?: return false
    return runCatching { java.net.URI(server).host in setOf("localhost", "127.0.0.1") }.getOrDefault(false)
}

/** The QR for the new phone, the time left, and the link to copy or share (sharing only where the app has a share sheet). */
@Composable
private fun ColumnScope.PairingBox(p: Pairing, left: Long, copied: Boolean, onCopied: () -> Unit, onClose: () -> Unit) {
    val qr = remember(p.link) { qrModules(p.link) }
    val qrLabel = string(Res.string.devices_qr_label)
    if (qr != null) {
        Canvas(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(4.dp)
                .semantics { contentDescription = qrLabel },
        ) { drawQr(qr) }
    }
    MutedText(string(Res.string.devices_qr_help, left / 60, left % 60), small = true)
    SelectionContainer {
        Text(p.link, style = MaterialTheme.typography.bodySmall, color = Kelo.colors.muted)
    }
    val clipboard = LocalClipboardManager.current
    val sharer = LocalTextSharer.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(string(if (copied) Res.string.devices_copied else Res.string.devices_copy), onClick = {
            clipboard.setText(AnnotatedString(p.link))
            onCopied()
        })
        if (sharer != null) SecondaryButton(string(Res.string.devices_share), onClick = { sharer.share(p.link) })
        SecondaryButton(string(Res.string.close), onClick = onClose)
    }
}

/** The link as a QR code's modules (error correction M, like the server's own, a two-module margin); null when it does not fit. */
private fun qrModules(text: String): Array<BooleanArray>? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
} catch (_: WriterException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Draws the modules edge to edge without smoothing: each module covers whole pixels. */
private fun DrawScope.drawQr(modules: Array<BooleanArray>) {
    val n = modules.size
    val side = minOf(size.width, size.height)
    val cell = side / n
    modules.forEachIndexed { y, row ->
        row.forEachIndexed { x, dark ->
            if (!dark) return@forEachIndexed
            val left = kotlin.math.floor(x * cell)
            val top = kotlin.math.floor(y * cell)
            drawRect(Color.Black, Offset(left, top), Size(kotlin.math.ceil((x + 1) * cell) - left, kotlin.math.ceil((y + 1) * cell) - top))
        }
    }
}
