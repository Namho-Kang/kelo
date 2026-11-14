package chat.kelo.app.my

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import chat.kelo.app.ui.text.*

import chat.kelo.app.res.*

import chat.kelo.app.ui.components.SectionLabel
import chat.kelo.app.ui.components.MenuGroup
import chat.kelo.app.ui.components.MenuRow
import chat.kelo.app.ui.components.MyCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.kelo.app.LocalAppServices
import chat.kelo.app.ui.components.MutedText
import chat.kelo.app.ui.icons.KeloIcons
import chat.kelo.app.wallet.PaymentPin
import chat.kelo.app.wallet.PinCalls
import chat.kelo.app.wallet.PinForm
import chat.kelo.app.ui.platform.keloViewModel
import kotlinx.coroutines.launch

/**
 * "결제 비밀번호" in the Keychain: the payment PINs saved for sites (name, site, how many digits; never the PIN), adding one, and
 * deleting one. A PIN saved on another phone is marked: only that phone can release it.
 */
@Composable
fun PaymentPinSection(locked: Boolean) {
    val services = LocalAppServices.current
    val keyId = remember { services.cardKeys.keyId() }
    val vm = keloViewModel("my-payment-pins") { PaymentPinsViewModel(services.sessions) }
    val pins by vm.pins.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PaymentPin?>(null) }
    SectionLabel(string(Res.string.pin_section))
    MutedText(string(Res.string.pin_section_help), small = true, modifier = Modifier.padding(horizontal = 4.dp))
    if (pins.isNotEmpty()) {
        MenuGroup {
            pins.forEach { p ->
                row {
                    MenuRow(
                        KeloIcons.Lock, p.label.ifEmpty { p.site }, onClick = { deleting = p },
                        value = if (p.ours(keyId)) string(Res.string.pin_row_value, p.site, p.digits)
                        else string(Res.string.pin_row_other_phone, p.site),
                    )
                }
            }
        }
    }
    if (adding) {
        MyCard {
            PinForm(onSaved = { adding = false; vm.reload() })
        }
    } else if (!locked) {
        MenuGroup { row { MenuRow(KeloIcons.Plus, string(Res.string.pin_add), onClick = { adding = true }) } }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(p.label.ifEmpty { p.site }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(string(Res.string.pin_row_value, p.site, p.digits))
                    Text(string(Res.string.pin_delete_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.delete(p.id)
                }) { Text(string(Res.string.pin_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(string(Res.string.cancel)) } },
        )
    }
}
