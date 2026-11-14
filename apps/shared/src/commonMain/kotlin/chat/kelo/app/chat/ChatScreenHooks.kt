package chat.kelo.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.kelo.app.chat.vault.SaveAnswer
import chat.kelo.app.chat.vault.SecretResult
import chat.kelo.app.wallet.CardSendError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** One row of the "+" sheet: something to add to the message. Built-in ones come first; a Nest chat_card entry may be appended later. */
class AddEntry(val id: String, val icon: ImageVector, val label: String, val hint: String, val onPick: () -> Unit)

/** What sends a card or a PIN from this device after the owner's check (the phone: biometric; the desktop never holds a card). */
interface CardSenders {
    fun card(approvalId: String, cardId: String): suspend () -> CardSendError?
    fun pin(slotId: String, pinId: String): suspend () -> CardSendError?

    companion object {
        /** No card or PIN leaves this device. */
        val None: CardSenders = object : CardSenders {
            override fun card(approvalId: String, cardId: String): suspend () -> CardSendError? = { CardSendError.NOT_ON_PHONE }
            override fun pin(slotId: String, pinId: String): suspend () -> CardSendError? = { CardSendError.NOT_ON_PHONE }
        }
    }
}

/**
 * Everything the shared chat screen ([ChatScreenContent]) takes from its app: one object, so the screen has no platform `if`. What
 * only one app has (the phone's pickers, voice and keyboard, the card release behind the owner's check, the
 * tab bar) comes in as slots; the connect and location cards are the shared ones.
 */
class ChatScreenHooks(
    /** "+" sheet entries, in order. */
    val addEntries: List<AddEntry>,
    /** A modifier for the composer's text field (Android: pictures pasted from the keyboard; desktop: key handling for paste). */
    val fieldModifier: Modifier = Modifier,
    /** The mic button next to send (Android voice): it reads and sets the field's text; null = none. */
    val mic: (@Composable (field: () -> TextFieldValue, setField: (TextFieldValue) -> Unit) -> Unit)? = null,
    /** Sends a card or a PIN after the owner's check. */
    val cardSenders: @Composable () -> CardSenders = { CardSenders.None },
    /** The app's own chrome while the keyboard is up for the composer (the phone hides its tab bar). */
    val keyboardChrome: @Composable (imeVisible: Boolean, composerFocused: Boolean) -> Unit = { _, _ -> },
    /** How much of the bottom the app's own bar covers (the phone's tab bar). */
    val bottomInset: Dp = 0.dp,
    /** Tab re-select: scroll to the newest message. */
    val reselect: Flow<Unit> = emptyFlow(),
    /** Opens the calendar at a time (a briefing's event); null = no calendar app here, so an event chip is not clickable. */
    val openCalendarAt: ((atMs: Long) -> Unit)? = {},
    /** A line under a payment step whose approval happens in another app (the desktop says it happens on the phone); nothing on the phone. */
    val approvalNote: @Composable () -> Unit = {},
    /** A payment PIN waiting for this device (the phone's card has a send button); null = the shared phone card. The desktop says the phone releases it. */
    val pinCard: (@Composable (need: PinNeed, onSend: () -> Unit) -> Unit)? = null,
    /** A message held back for a card number: it goes on to the card form. */
    val secretToCard: () -> Unit = {},
)
