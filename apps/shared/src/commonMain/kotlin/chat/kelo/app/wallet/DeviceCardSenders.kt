package chat.kelo.app.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import chat.kelo.app.LocalAppServices
import chat.kelo.app.chat.CardSenders
import chat.kelo.app.core.Sessions
import chat.kelo.app.res.*
import chat.kelo.app.ui.text.*

/**
 * Sends an end-to-end card or PIN from this device for an approved payment, only after the owner's check: fetch the blob, open it
 * with the device's key, send it to its slot, wipe. On a device without a card key ([CardKeys.None]) every send answers "not on this
 * device", so the card says the phone does it.
 */
class DeviceCardSenders(
    private val sessions: Sessions, private val cardKeys: CardKeys,
    private val payTitle: String, private val paySubtitle: String, private val pinTitle: String, private val pinSubtitle: String,
) : CardSenders {
    override fun card(approvalId: String, cardId: String): suspend () -> CardSendError? = {
        deliverCard(WalletCalls(sessions.requireApi()), cardKeys, approvalId, cardId, payTitle, paySubtitle)
    }

    override fun pin(slotId: String, pinId: String): suspend () -> CardSendError? = {
        releasePin(sessions.requireApi(), cardKeys, slotId, pinId, pinTitle, pinSubtitle)
    }
}

/** The card senders of this device, with the check's words in the app's language. */
@Composable
fun rememberCardSenders(): CardSenders {
    val services = LocalAppServices.current
    val payTitle = string(Res.string.wallet_auth_pay_title)
    val paySubtitle = string(Res.string.wallet_auth_pay_subtitle)
    val pinTitle = string(Res.string.pin_auth_title)
    val pinSubtitle = string(Res.string.pin_auth_subtitle)
    return remember(services.sessions, services.cardKeys, payTitle, paySubtitle, pinTitle, pinSubtitle) {
        DeviceCardSenders(services.sessions, services.cardKeys, payTitle, paySubtitle, pinTitle, pinSubtitle)
    }
}
